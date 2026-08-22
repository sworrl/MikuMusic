package com.miku.launcher.lockscreen

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.miku.launcher.AudiowideFont
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MikuOS Always-On Display — the default AOD face: big clock, date, weather line, and the
 * currently-playing track. Opt-in via [MikuLockscreenPrefs] (off / while-charging / always).
 *
 * The M500's panel is an LCD (no per-pixel OLED self-emission), so "always on" here means the
 * panel held awake at minimum window brightness on a pure-black face — the cheapest possible
 * LCD state. The face drifts a few dp every minute anyway so the habit carries to any future
 * OLED hardware. A tap anywhere hands off to the real lockscreen.
 */
class MikuAodActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Live above the (stock) keyguard, keep the panel awake, at minimum brightness.
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }

        setContent {
            val ctx = LocalContext.current
            val showWeather = remember { MikuLockscreenPrefs.getAodShowWeather(ctx) }
            val showNp = remember { MikuLockscreenPrefs.getAodShowNowPlaying(ctx) }

            var now by remember { mutableStateOf(Date()) }
            var driftStep by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = Date()
                    driftStep = (driftStep + 1) % 5
                    delay(60_000L)
                }
            }

            val weatherState by com.miku.launcher.weather.MikuWeatherService.state.collectAsState()
            val bpmState by com.miku.launcher.bpm.MikuBpmEngine.state.collectAsState()

            // Now-playing metadata published by Miku Music into Settings.Global.
            var npTitle by remember { mutableStateOf("") }
            var npArtist by remember { mutableStateOf("") }
            LaunchedEffect(bpmState.isPlaying) {
                while (true) {
                    npTitle = runCatching {
                        android.provider.Settings.Global.getString(ctx.contentResolver, "miku_now_playing_title") ?: ""
                    }.getOrDefault("")
                    npArtist = runCatching {
                        android.provider.Settings.Global.getString(ctx.contentResolver, "miku_now_playing_artist") ?: ""
                    }.getOrDefault("")
                    delay(5_000L)
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Hand off to the real lockscreen — it is authoritative.
                        try {
                            startActivity(
                                android.content.Intent(this@MikuAodActivity, MikuLockscreenActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        } catch (_: Throwable) {}
                        finish()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Gentle positional drift (a few dp per minute) — burn-in hygiene.
                    modifier = Modifier.padding(top = (driftStep * 6).dp)
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
                        color = MikuCyan.copy(alpha = 0.85f),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now),
                        color = Color(0xFF6F8F8C),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )

                    if (showWeather) {
                        val w = weatherState.weather
                        if (w.tempF != 0f) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "${w.icon} ${w.tempF.toInt()}°F · ${w.summary}",
                                color = Color(0xFF89ACA7),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (showNp && bpmState.isPlaying && npTitle.isNotBlank()) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "♪ $npTitle",
                            color = MikuNeonPink.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (npArtist.isNotBlank()) {
                            Text(
                                text = npArtist,
                                color = Color(0xFF6F8F8C),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
