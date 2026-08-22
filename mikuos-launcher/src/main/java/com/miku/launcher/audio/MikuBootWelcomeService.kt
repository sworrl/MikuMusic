package com.miku.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.miku.launcher.R
import kotlinx.coroutines.*

/**
 * Plays the MikuOS welcome jingle once per cold boot, using the launcher's res/raw
 * asset. Gate-controlled by the same Settings.Global key HiBy uses
 * ("hiby_miku_sounds_enable"), so the user's boot-sound toggle in Miku Settings
 * silences both the stock bootanimation audio AND this jingle.
 *
 * The jingle is a short (<5s) audio clip bundled as R.raw.miku_welcome_jingle.
 * If the raw resource doesn't exist yet (hasn't been created), the service
 * silently no-ops — no crash, no log spam.
 */
object MikuBootWelcomeService {

    private const val TAG = "MikuBootWelcome"
    private const val PREFS = "miku_boot_welcome"
    private const val KEY_LAST_BOOT_UPTIME = "last_boot_played_uptime_epoch"
    private const val SETTINGS_KEY = "hiby_miku_sounds_enable"

    // The stock bootanimation MP4s are ~12.5s. We wait a short beat after
    // launcher creation so the boot animation is definitely done.
    private const val DELAY_MS = 2000L

    private var played = false

    /**
     * Call from MikuLauncherActivity.onCreate (on a background thread).
     * Plays at most once per device boot cycle.
     */
    fun maybePlay(context: Context) {
        if (played) return

        // Check the user's boot-sound toggle (default: enabled)
        val enabled = try {
            android.provider.Settings.Global.getInt(
                context.contentResolver, SETTINGS_KEY, 1
            ) == 1
        } catch (_: Throwable) { true }
        if (!enabled) { played = true; return }

        // Dedup across process restarts within the same boot cycle.
        // SystemClock.elapsedRealtime() resets on reboot, so if the stored
        // uptime-epoch is close to current elapsed time, we already played.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastPlayed = prefs.getLong(KEY_LAST_BOOT_UPTIME, -1L)
        val uptime = SystemClock.elapsedRealtime()
        // If the device has been up long enough that our stored timestamp is
        // still valid (within this boot), skip.
        if (lastPlayed > 0 && uptime > lastPlayed) {
            played = true
            return
        }

        played = true

        CoroutineScope(Dispatchers.IO).launch {
            delay(DELAY_MS)
            try {
                // Try to resolve R.raw.miku_welcome_jingle — if it doesn't
                // exist the resource lookup returns 0 / throws.
                val resId = try {
                    R.raw::class.java.getField("miku_welcome_jingle").getInt(null)
                } catch (_: Throwable) { 0 }
                if (resId == 0) {
                    Log.d(TAG, "No R.raw.miku_welcome_jingle found, skipping")
                    return@launch
                }

                val player = MediaPlayer.create(context, resId) ?: run {
                    Log.w(TAG, "MediaPlayer.create returned null")
                    return@launch
                }

                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )

                // Play at a moderate volume so it's not jarring
                player.setVolume(0.6f, 0.6f)
                player.setOnCompletionListener { mp ->
                    mp.release()
                }
                player.start()
                Log.i(TAG, "Welcome jingle playing")

                prefs.edit().putLong(KEY_LAST_BOOT_UPTIME, SystemClock.elapsedRealtime()).apply()
            } catch (t: Throwable) {
                Log.e(TAG, "Welcome jingle failed", t)
            }
        }
    }
}
