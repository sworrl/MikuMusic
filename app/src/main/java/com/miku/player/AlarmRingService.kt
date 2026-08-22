package com.miku.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/** What was playing (if anything) right before an alarm took over the shared player, so a
 *  dismissed alarm can hand playback back exactly where the user left it instead of leaving them
 *  on the alarm's shuffled queue. Process-lifetime singleton, same pattern as [[PlayerHolder]] —
 *  deliberately NOT persisted to disk: if the process dies mid-alarm there's nothing meaningful
 *  left to restore anyway (the player itself would be rebuilt from scratch). */
private object AlarmQueueSnapshot {
    data class Snapshot(val items: List<androidx.media3.common.MediaItem>, val index: Int, val positionMs: Long, val wasPlaying: Boolean)
    @Volatile var saved: Snapshot? = null

    fun capture(player: androidx.media3.exoplayer.ExoPlayer) {
        if (saved != null) return // already have one from an earlier alarm in this same ring session (e.g. snoozed then re-fired)
        if (player.mediaItemCount == 0) return
        saved = Snapshot(
            items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            wasPlaying = player.isPlaying
        )
    }

    fun restoreAndClear(player: androidx.media3.exoplayer.ExoPlayer) {
        val s = saved ?: return
        saved = null
        player.setMediaItems(s.items, s.index.coerceIn(0, s.items.lastIndex), s.positionMs)
        player.volume = 1f
        player.prepare()
        if (s.wasPlaying) player.play() else player.pause()
    }
}

/**
 * Foreground service that actually rings an alarm: builds the track queue on the shared
 * [[PlayerHolder]] player, fades volume in over the alarm's configured duration, vibrates
 * throughout as a fallback that doesn't depend on the library having anything to play, posts a
 * full-screen-intent notification so [[AlarmRingActivity]] shows over the lockscreen, and holds a
 * wake lock sized to the alarm's own fade-in length. Multiple alarms can legitimately overlap
 * (two close-together trigger times) — every piece of per-ring state (wake lock, fade ticker,
 * notification id, auto-dismiss timeout) is keyed by alarm id so a second alarm firing never stomps
 * the first one's in-flight state or steals its notification.
 */
class AlarmRingService : Service() {
    private data class RingState(
        val alarm: Alarm,
        var wakeLock: PowerManager.WakeLock?,
        val fadeHandler: Handler,
        val autoDismissHandler: Handler
    )
    private val rings = HashMap<Int, RingState>()
    private var vibrator: android.os.Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // startForegroundService() demands startForeground() within seconds of THIS call or the
            // OS kills the process with a fatal exception — call it unconditionally, immediately,
            // before any lookup that could bail out early, so a missing/already-deleted alarm id
            // (TOCTOU against AlarmScheduler.cancel, or a corrupt alarms.json falling back to
            // emptyList in AlarmPreferences.loadAlarms) degrades to "notification briefly appears
            // then clears" instead of a crash.
            ACTION_RING -> {
                val alarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)
                startForeground(NOTIF_ID_BASE, placeholderNotification())
                startRinging(alarmId)
            }
            ACTION_SNOOZE -> stopOneRing(intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1), snoozing = true)
            ACTION_DISMISS -> stopOneRing(intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1), snoozing = false)
        }
        return START_NOT_STICKY
    }

    private fun startRinging(alarmId: Int) {
        // Re-entry guard: a duplicate ACTION_RING for an id that's already ringing (broadcast
        // redelivery, possible on a custom OEM ROM) used to blindly overwrite rings[alarmId] with
        // a fresh RingState — dropping the OLD wake lock/handlers with nothing left to cancel them,
        // and leaving the old auto-dismiss Runnable (captured `alarmId` by value, not the state
        // object) armed to force-stop whatever ring is CURRENTLY in the map when its timer fires,
        // potentially cutting the real (second) ring short.
        if (rings.containsKey(alarmId)) return
        val alarm = AlarmPreferences.loadAlarms(this).firstOrNull { it.id == alarmId }
        if (alarm == null) {
            // The placeholder notification (posted unconditionally in onStartCommand, before this
            // lookup, so a crash-avoiding startForeground() always happens in time) is only ever
            // superseded by a REAL per-alarm notification if startRinging finds a valid alarm.
            // With another alarm already ringing, that supersession never happens here, so the
            // placeholder would otherwise sit in the shade forever with no content/actions.
            if (rings.isEmpty()) stopForegroundAndSelfIfIdle()
            else (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID_BASE)
            return
        }
        AlarmPreferences.saveActiveSnooze(this, null)

        // Wake-lock duration must cover the whole fade-in (editable up to 300s) plus real margin
        // for the screen/keyguard-dismiss/full-screen-intent launch to land — a fixed 60s lock (the
        // old behavior) would expire mid-fade for anything past ~55s and let the screen go back to
        // sleep before AlarmRingActivity ever got shown.
        val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "miku:alarm:$alarmId")
        wl.acquire((alarm.fadeInSeconds + 45) * 1000L)

        val state = RingState(alarm, wl, Handler(Looper.getMainLooper()), Handler(Looper.getMainLooper()))
        rings[alarmId] = state

        startForeground(notifId(alarmId), buildNotification(alarm))
        startVibrating()
        ringPlayer(alarm, state)

        // Safety net: an alarm nobody responds to (device out of reach, user asleep through it)
        // shouldn't ring/vibrate/hold a foreground notification forever — auto-dismiss same as a
        // manual dismiss after a generous timeout, still re-arming the next occurrence if it repeats.
        state.autoDismissHandler.postDelayed({ stopOneRing(alarmId, snoozing = false) }, AUTO_DISMISS_AFTER_MS)
    }

    private fun ringPlayer(alarm: Alarm, state: RingState) {
        val player = PlayerHolder.ensure(this)
        AlarmQueueSnapshot.capture(player)
        // AlarmLibrary.tracksFor() is a MediaStore query + shuffle over the whole matching pool —
        // real work that shouldn't run on the main thread during an alarm fire (it's exactly the
        // moment that most wants to be responsive: fade-in should start promptly, not wait behind
        // a query). Queried on a background thread, then the actual ExoPlayer calls (must happen
        // on the player's own Looper thread, i.e. main) are posted back.
        Thread {
            val tracks = AlarmLibrary.tracksFor(this, alarm.source, alarm.sourceRef)
            state.fadeHandler.post {
                if (!rings.containsKey(alarm.id)) return@post // dismissed/snoozed already while this query was running
                if (tracks.isEmpty()) return@post // library empty/not scanned yet — vibration (already started) is the fallback so this alarm still isn't silent
                player.setMediaItems(tracks.map { mediaItemFor(it) }, 0, 0L)
                player.volume = 0f
                player.prepare()
                player.play()

                val steps = (alarm.fadeInSeconds * 4).coerceAtLeast(1) // one tick per 250ms
                var step = 0
                val tick = object : Runnable {
                    override fun run() {
                        step++
                        player.volume = (step.toFloat() / steps).coerceIn(0f, 1f)
                        if (step < steps) state.fadeHandler.postDelayed(this, 250L)
                    }
                }
                state.fadeHandler.postDelayed(tick, 250L)
            }
        }.start()
    }

    private fun startVibrating() {
        if (vibrator != null) return // already vibrating for an earlier concurrent alarm — one shared pattern is enough
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        vibrator = v
        val pattern = longArrayOf(0, 700, 400) // wait, buzz, pause — repeats from index 1
        v.vibrate(VibrationEffect.createWaveform(pattern, 1))
    }

    private fun stopVibratingIfNoneRinging() {
        if (rings.isEmpty()) { vibrator?.cancel(); vibrator = null }
    }

    private fun snooze(alarm: Alarm) = AlarmScheduler.scheduleSnooze(this, alarm)

    private fun dismiss(alarm: Alarm) {
        AlarmPreferences.saveActiveSnooze(this, null)
        if (alarm.repeatDays.isNotEmpty()) {
            AlarmScheduler.scheduleNext(this, alarm)
        } else {
            AlarmPreferences.updateAlarm(this, alarm.id) { it.copy(enabled = false) }
            AlarmScheduler.cancel(this, alarm.id)
        }
    }

    private fun stopOneRing(alarmId: Int, snoozing: Boolean) {
        val state = rings.remove(alarmId)
        state?.fadeHandler?.removeCallbacksAndMessages(null)
        state?.autoDismissHandler?.removeCallbacksAndMessages(null)
        state?.wakeLock?.let { if (it.isHeld) it.release() }
        if (state != null) {
            if (snoozing) snooze(state.alarm) else dismiss(state.alarm)
        }
        // Pausing only makes sense once NO alarm is still actively ringing — with two alarms
        // overlapping, dismissing/snoozing the first one used to unconditionally pause the player
        // even while the second was still mid-ring, yanking its music away.
        if (rings.isEmpty()) {
            PlayerHolder.player?.let { it.pause(); it.volume = 1f }
        }
        // Restoring the pre-alarm queue only makes sense once the alarm is truly OVER — no other
        // ring in flight, AND this particular stop was a real dismiss, not a snooze (a snoozed
        // alarm will take the player back over again in a few minutes; restoring now would just
        // get immediately clobbered).
        if (rings.isEmpty() && !snoozing) {
            PlayerHolder.player?.let { AlarmQueueSnapshot.restoreAndClear(it) }
        }
        stopVibratingIfNoneRinging()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(alarmId))
        stopForegroundAndSelfIfIdle()
    }

    private fun stopForegroundAndSelfIfIdle() {
        if (rings.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun placeholderNotification(): android.app.Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Miku Music Alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Miku Music alarm ringing"
                    setBypassDnd(true)
                }
            )
        }
    }

    private fun buildNotification(alarm: Alarm): android.app.Notification {
        ensureChannel()
        val fullScreenIntent = PendingIntent.getActivity(
            this, alarm.id,
            Intent(this, AlarmRingActivity::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snoozePi = PendingIntent.getService(
            this, alarm.id * 10 + 1,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_SNOOZE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissPi = PendingIntent.getService(
            this, alarm.id * 10 + 2,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_DISMISS).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alarm.label.ifBlank { "Alarm" })
            .setContentText("Tap to open Miku Music")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .setOngoing(true)
            .addAction(0, "Snooze", snoozePi)
            .addAction(0, "Dismiss", dismissPi)
            .build()
    }

    private fun notifId(alarmId: Int) = NOTIF_ID_BASE + alarmId

    override fun onDestroy() {
        rings.values.forEach {
            it.fadeHandler.removeCallbacksAndMessages(null)
            it.autoDismissHandler.removeCallbacksAndMessages(null)
            it.wakeLock?.let { wl -> if (wl.isHeld) wl.release() }
        }
        rings.clear()
        vibrator?.cancel()
        vibrator = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.miku.player.alarm.RING"
        const val ACTION_SNOOZE = "com.miku.player.alarm.SNOOZE"
        const val ACTION_DISMISS = "com.miku.player.alarm.DISMISS"
        private const val CHANNEL_ID = "miku_alarms"
        private const val NOTIF_ID_BASE = 7100
        private const val AUTO_DISMISS_AFTER_MS = 10 * 60_000L
    }
}
