package com.miku.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.CyberDarkBg
import com.miku.player.CyberGlassBorder
import com.miku.player.MikuCyan
import com.miku.player.MikuNeonPink
import com.miku.player.MikuTextPrimary
import com.miku.player.MikuTextSecondary
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class PulsarSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent {
            PulsarSettingsScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulsarSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pulsarMode by remember { mutableStateOf(PulsarLight.getMode(ctx)) }
    var pulsarBrightness by remember { mutableStateOf((PulsarLight.getBrightness(ctx) / 255f).coerceIn(0f, 1f)) }
    var pulsarOn by remember { mutableStateOf(PlayerPreferences.loadPulsarEnabled(ctx)) }
    var bpmSyncOn by remember { mutableStateOf(PulsarLight.isBpmSyncEnabled(ctx)) }
    // Real probe of the LED sysfs nodes — decides whether this screen may claim it drives hardware.
    val ledWritable = remember { PulsarLight.isHardwareWritable() }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        // Bespoke Hatsune Miku Stage Backdrop
        Image(
            painter = painterResource(id = R.drawable.miku_cyber_stage),
            contentDescription = "Miku Pulsar Stage Artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Frosted Cyber Dark Gradient Overlay
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE040D12),
                            Color(0xAA000000),
                            Color(0xF8040D12)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Header Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.miku.player.ui.MikuBackButton(onClick = onBack)

                Text(
                    "MIKU PULSAR RGB CONTROLLER",
                    color = MikuCyan,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                // Hardware Chipset Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FF4081))
                        .border(1.dp, MikuNeonPink, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "SGM31324",
                        color = MikuNeonPink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Master Enable Card
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.5.dp, MikuCyan, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "SGM31324 RGB LIGHTBAR",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                                Text(
                                    if (ledWritable) "Controls the physical RGB LED lightbar built into the M500 chassis."
                                    else "This unit's LED nodes are not writable by the player, so these controls only save a preference — the chassis light does not change.",
                                    color = if (ledWritable) MikuTextSecondary else Color(0xFFFFB300),
                                    fontSize = 10.5.sp
                                )
                            }
                            Switch(
                                checked = pulsarOn,
                                onCheckedChange = {
                                    pulsarOn = it
                                    scope.launch {
                                        PlayerPreferences.savePulsarEnabled(ctx, it)
                                        PulsarLight.setEnabled(ctx, it)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuCyan, checkedTrackColor = Color(0xFF00695C))
                            )
                        }
                    }
                }

                if (pulsarOn) {
                    // Pattern Modes Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xDD0A1E26))
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                "LIGHTING PATTERN MODE",
                                color = MikuCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Select the active animation and color engine for the chassis lightbar.",
                                color = MikuTextSecondary,
                                fontSize = 10.sp
                            )
                            Spacer(Modifier.height(10.dp))

                            PulsarLight.Mode.values().forEach { mode ->
                                val isSel = pulsarMode == mode
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) Color(0x3300E5FF) else Color.Transparent)
                                        .clickable {
                                            pulsarMode = mode
                                            scope.launch { PulsarLight.setMode(ctx, mode, (pulsarBrightness * 255).roundToInt()) }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSel,
                                        onClick = {
                                            pulsarMode = mode
                                            scope.launch { PulsarLight.setMode(ctx, mode, (pulsarBrightness * 255).roundToInt()) }
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = MikuCyan, unselectedColor = Color.Gray)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(mode.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(mode.description, color = MikuTextSecondary, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Brightness & Controls Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xDD0A1E26))
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("HARDWARE PWM BRIGHTNESS", color = MikuCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text("${(pulsarBrightness * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                            Spacer(Modifier.height(6.dp))
                            Slider(
                                value = pulsarBrightness,
                                onValueChange = {
                                    pulsarBrightness = it
                                    scope.launch { PulsarLight.setMode(ctx, pulsarMode, (it * 255).roundToInt()) }
                                },
                                colors = SliderDefaults.colors(thumbColor = MikuCyan, activeTrackColor = MikuCyan, inactiveTrackColor = Color(0xFF1E3C44))
                            )

                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            Spacer(Modifier.height(10.dp))

                            // Audio Real-time BPM Sync Toggle
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Dynamic Audio BPM Sync", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Dynamically modulates the LED pulse tempo to the currently playing track BPM.", color = MikuTextSecondary, fontSize = 10.sp)
                                }
                                Switch(
                                    checked = bpmSyncOn,
                                    onCheckedChange = {
                                        bpmSyncOn = it
                                        scope.launch { PulsarLight.setBpmSyncEnabled(ctx, it) }
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MikuNeonPink, checkedTrackColor = Color(0xFF880E4F))
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
