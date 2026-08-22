package com.miku.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen alarm-firing UI — launched either directly by AlarmManager's showIntent (tapping
 * the status bar alarm icon) or by [[AlarmRingService]]'s full-screen notification (the real
 * "device was asleep/locked" path). `setShowWhenLocked`/`setTurnScreenOn` are the modern (API 27+)
 * replacements for the old WindowManager flags — this is the same mechanism real alarm-clock and
 * incoming-call apps use to draw over the lockscreen.
 */
class AlarmRingActivity : ComponentActivity() {
    private var alarmId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.requestDismissKeyguard(this, null)

        val alarm = AlarmPreferences.loadAlarms(this).firstOrNull { it.id == alarmId }
        val player = PlayerHolder.ensure(this)

        setContent {
            AlarmRingScreen(
                label = alarm?.label?.ifBlank { "Alarm" } ?: "Alarm",
                player = player,
                onSnooze = { sendAction(AlarmRingService.ACTION_SNOOZE) },
                onDismiss = { sendAction(AlarmRingService.ACTION_DISMISS) }
            )
        }
    }

    // M500 physical button integration: any single press of the hardware transport keys
    // snoozes (the low-risk default — "make it stop for now"); MEDIA_PREVIOUS dismisses outright,
    // matching how a long-press-to-confirm skip-back already reads as "more deliberate" elsewhere
    // in this app's key handling (see MainActivity.onKeyDown for the same key set).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { sendAction(AlarmRingService.ACTION_DISMISS); true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { sendAction(AlarmRingService.ACTION_SNOOZE); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun sendAction(action: String) {
        startService(
            Intent(this, AlarmRingService::class.java)
                .setAction(action)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        )
        finish()
    }
}

@Composable
private fun AlarmRingScreen(label: String, player: androidx.media3.exoplayer.ExoPlayer, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Same fullscreen-viz-as-backdrop treatment as the regular Now Playing screen — the alarm's
    // own fade-in feeds real audio into the shared player, so the visualizer has something to
    // react to from the first frame, not just a static preset sitting idle at zero volume.
    val preset = remember { ProjectMPreset.entries.getOrElse(PlayerPreferences.loadProjectMPreset(ctx)) { ProjectMPreset.CYBER_TUNNEL } }
    Box(Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.Center) {
        ProjectMVisualizerView(
            sessionId = player.audioSessionId,
            preset = preset,
            modifier = Modifier.fillMaxSize()
        )
        // Scrim behind the label/buttons so they stay legible over whatever the preset is doing —
        // same reasoning as every other control overlay drawn on top of the visualizer elsewhere
        // in the app (NowPlaying's overlay controls use the same treatment).
        Box(Modifier.fillMaxSize().background(Color(0x80000000)))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Alarm, "Alarm", tint = MikuPink, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(18.dp))
            Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(6.dp))
            Text("Miku Music Alarm", color = Muted, fontSize = 13.sp)

            Spacer(Modifier.height(56.dp))

            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .clickable(onClick = onSnooze)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Snooze, "Snooze", tint = MikuTealBright, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Snooze", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MikuPink)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Close, "Dismiss", tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Dismiss", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(28.dp))
            Text("Physical keys work too — Prev dismisses, Play/Next snoozes", color = Muted, fontSize = 11.sp)
        }
    }
}
