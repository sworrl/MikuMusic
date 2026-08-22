package com.m500.hardware

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class PulsarDialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PulsarSettingsScreen(onClose = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulsarSettingsScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var pulsarEnabled by remember { mutableStateOf(PulsarLight.isEnabled(ctx)) }
    var pulsarMode by remember { mutableStateOf(PulsarLight.getMode(ctx)) }
    var pulsarBrightness by remember { mutableStateOf(PulsarLight.getBrightness(ctx).toFloat()) }
    var bpmSyncOn by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "PULSAR RGB LIGHT",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = HwMikuTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HwBgDark)
            )
        },
        containerColor = HwBgDark
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Master Toggle Card
            item {
                HwSettingsSection("Master Indicator Control")
                HwSettingsToggleRow(
                    title = "Enable Pulsar Indicator",
                    subtitle = "Front RGB indicator lighting for audio format status, BPM tempo pulse, and charging",
                    checked = pulsarEnabled
                ) { enabled ->
                    pulsarEnabled = enabled
                    scope.launch { PulsarLight.setEnabled(ctx, enabled) }
                }
            }

            if (pulsarEnabled) {
                // Pattern Selection
                item {
                    HwSettingsSection("Lighting Patterns & Dynamic Effects")

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(HwSurface1)
                            .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        PulsarLight.Mode.values().filter { it != PulsarLight.Mode.OFF }.forEach { mode ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pulsarMode = mode
                                        scope.launch { PulsarLight.setMode(ctx, mode) }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = pulsarMode == mode,
                                    onClick = {
                                        pulsarMode = mode
                                        scope.launch { PulsarLight.setMode(ctx, mode) }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = HwMikuTeal, unselectedColor = HwMuted)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(mode.label, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                    Text(mode.description, color = HwMuted, fontSize = 11.5.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text("BRIGHTNESS (${(pulsarBrightness / 255f * 100).toInt()}%)", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = pulsarBrightness,
                            onValueChange = {
                                pulsarBrightness = it
                                scope.launch { PulsarLight.setBrightness(ctx, it.toInt()) }
                            },
                            valueRange = 10f..255f,
                            colors = SliderDefaults.colors(thumbColor = HwMikuPink, activeTrackColor = HwMikuTeal, inactiveTrackColor = HwSurface2)
                        )
                    }
                }

                // Format Color Coding Legend
                item {
                    HwSettingsSection("Audiophile Format Hierarchy")

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(HwSurface1)
                            .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Text("BIT-PERFECT LED HARDWARE STATUS", color = HwMikuPink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            "#FFFFFF" to "Direct Stream Digital (DSD64 - DSD256 / SACD ISO)",
                            "#FFD700" to "Ultra Hi-Res PCM (352.8kHz - 768kHz / 32-bit)",
                            "#00E5FF" to "Hi-Res Lossless (88.2kHz - 192kHz / 24-bit)",
                            "#00E676" to "CD Lossless (44.1kHz - 48kHz / 16-bit FLAC/WAV)",
                            "#9C27B0" to "Miku Custom Mode (Purple ⇋ Teal Dynamic Cycle)"
                        ).forEach { (colorHex, text) ->
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(AndroidColor.parseColor(colorHex))))
                                Spacer(Modifier.width(10.dp))
                                Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
