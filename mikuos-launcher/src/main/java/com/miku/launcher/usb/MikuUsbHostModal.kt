package com.miku.launcher.usb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miku.launcher.AudiowideFont
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink
import kotlinx.coroutines.launch

/**
 * Cyberpunk Themed System-Level USB Host Controller Modal.
 * Offers clean mode switching (Direct ALSA USB DAC, MTP Files, Charge Only)
 * with robust persistence options (24 hours, 7 days, forever on this PC).
 */
@Composable
fun MikuUsbHostModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedDismissOption by remember { mutableStateOf(MikuUsbPreferences.DismissOption.ONCE) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF061418))
                    .border(
                        1.2.dp,
                        Brush.verticalGradient(
                            listOf(MikuCyan.copy(alpha = 0.8f), MikuNeonPink.copy(alpha = 0.6f))
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {} // swallow clicks
                    .padding(18.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MikuCyan.copy(alpha = 0.15f))
                                    .border(1.dp, MikuCyan.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = MikuCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "USB HOST CONNECTED",
                                    color = Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    "Hardware Audio & Link Controller",
                                    color = MikuCyan.copy(alpha = 0.7f),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        "Configure device mode for the connected host:",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Mode Selection Options
                    // 1. USB DAC Master Mode
                    ModeButton(
                        title = "⚡ USB DAC Mode (Direct ALSA)",
                        subtitle = "UAC2 192kHz/32-bit Bit-Perfect soundcard",
                        accentColor = MikuCyan,
                        onClick = {
                            MikuUsbPreferences.savePreference(ctx, MikuUsbPreferences.MODE_DAC, selectedDismissOption)
                            scope.launch {
                                MikuUsbAudioHostManager.setUsbDacMode(ctx, true)
                            }
                            onDismissRequest()
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // 2. MTP File Transfer
                    ModeButton(
                        title = "📁 MTP / File Transfer",
                        subtitle = "High-speed internal storage & SD card access",
                        accentColor = Color(0xFF80D8FF),
                        onClick = {
                            MikuUsbPreferences.savePreference(ctx, MikuUsbPreferences.MODE_MTP, selectedDismissOption)
                            scope.launch {
                                MikuUsbAudioHostManager.setMtpMode(ctx)
                            }
                            onDismissRequest()
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // 3. Charge Only
                    ModeButton(
                        title = "🔋 Charge Only",
                        subtitle = "Standard battery charging, data transfer disabled",
                        accentColor = Color(0xFF69F0AE),
                        onClick = {
                            MikuUsbPreferences.savePreference(ctx, MikuUsbPreferences.MODE_CHARGE, selectedDismissOption)
                            scope.launch {
                                MikuUsbAudioHostManager.setChargeOnlyMode(ctx)
                            }
                            onDismissRequest()
                        }
                    )

                    Spacer(Modifier.height(14.dp))

                    // Persistence / Dismiss Options
                    Text(
                        "PERSISTENCE & PROMPT PREFERENCE:",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF030D10))
                            .border(0.8.dp, Color(0xFF1E3A42), RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MikuUsbPreferences.DismissOption.values().forEach { option ->
                            val selected = selectedDismissOption == option
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) MikuCyan.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { selectedDismissOption = option }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, if (selected) MikuCyan else Color.Gray, CircleShape)
                                        .background(if (selected) MikuCyan else Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    option.label,
                                    color = if (selected) Color.White else Color(0xFF90A4AE),
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A1F26))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column {
            Text(
                title,
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = Color(0xFFB0BEC5),
                fontSize = 10.sp
            )
        }
    }
}
