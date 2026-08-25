package com.miku.launcher.arco

import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.CyberDarkBg
import com.miku.launcher.CyberGlassBorder
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuGold
import com.miku.launcher.MikuNeonPink
import com.miku.launcher.MikuTextSecondary
import com.miku.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch

/**
 * Compact quick-control modal for the arcobocconotto RGB fleet — connection
 * state, active effect, blackout/power, global brightness, and a handful of
 * quick themes. Meant to be opened from [MikuArcoBadge]'s onClick. Full
 * pairing/discovery/theme-browser/zone UI lives in [MikuArcoSettingsActivity]
 * ("More" button below opens it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikuArcoModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ArcoClient.init(ctx)
        if (ArcoClient.isPaired || ArcoClient.isDirectKeyConfigured) {
            ArcoClient.refreshEffectsAndZones()
        }
    }

    val connectionState by ArcoClient.connectionState.collectAsState()
    val activeEffect by ArcoClient.activeEffect.collectAsState()
    val themes by ArcoClient.themes.collectAsState()
    val lastError by ArcoClient.lastError.collectAsState()

    var lastNonOffEffect by remember { mutableStateOf("cyberpunk_city") }
    LaunchedEffect(activeEffect) { if (activeEffect.isNotBlank() && activeEffect != "off") lastNonOffEffect = activeEffect }

    var brightness by remember { mutableFloatStateOf(1f) }
    var brightnessUnsupported by remember { mutableStateOf(false) }
    var isBusyAction by remember { mutableStateOf(false) }

    val isPaired = ArcoClient.isPaired || ArcoClient.isDirectKeyConfigured
    val isConnected = connectionState == ArcoConnectionState.CONNECTED
    val statusColor = when (connectionState) {
        ArcoConnectionState.CONNECTED -> com.miku.launcher.ui.MikuIdentity.Leek
        ArcoConnectionState.CONNECTING -> MikuGold
        ArcoConnectionState.ERROR -> com.miku.launcher.ui.MikuIdentity.Coral
        ArcoConnectionState.DISCONNECTED -> Color(0xFFFF9100)
        ArcoConnectionState.UNPAIRED -> Color(0xFF8BA6A9)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ArcoModalPulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ArcoModalPulseGlow"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismissRequest)
            // System-gesture-style dismiss: swipe up starting at the bottom edge of the sheet.
            .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
            .background(Color(0xCC040D12)),
        contentAlignment = Alignment.TopEnd
    ) {
        Box(
            Modifier
                .padding(top = 44.dp, end = 8.dp)
                .width(300.dp)
                .clickable(enabled = false) {}
                .clip(CutCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.68f)))
                )
        ) {
            Column(
                Modifier
                    .padding(1.dp)
                    .clip(CutCornerShape(13.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFA09222E), Color(0xFF04121A), Color(0xFF02090D))))
                    .border(
                        BorderStroke(1.dp, Brush.verticalGradient(listOf(statusColor.copy(alpha = pulseGlow), CyberGlassBorder.copy(alpha = 0.35f), statusColor.copy(alpha = 0.5f)))),
                        CutCornerShape(13.dp)
                    )
                    .padding(12.dp)
            ) {
                // Header
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💡", fontSize = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("ARCOBOCCONOTTO", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 0.6.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(statusColor))
                                Spacer(Modifier.width(4.dp))
                                Text(connectionStateLabel(connectionState), color = statusColor, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                        }
                    }
                    Row {
                        IconButton(onClick = { ctx.startActivity(Intent(ctx, MikuArcoSettingsActivity::class.java)) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Settings, contentDescription = "Open arcobocconotto Settings", tint = MikuCyan, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onDismissRequest, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MikuNeonPink, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (!isPaired) {
                    Text(
                        "Not paired with the RGB fleet controller yet.",
                        color = MikuTextSecondary, fontSize = 10.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    ArcoModalActionButton(
                        label = "PAIR NOW",
                        accentColor = MikuCyan,
                        onClick = { ctx.startActivity(Intent(ctx, MikuArcoSettingsActivity::class.java)) }
                    )
                } else {
                    // Active effect row
                    Text("Active Effect", color = MikuTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Text(
                        if (activeEffect.isBlank()) "—" else activeEffect,
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(10.dp))

                    // Power / blackout row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = if (activeEffect != "off") com.miku.launcher.ui.MikuIdentity.Leek else Color(0xFF8BA6A9), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Power", color = MikuTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Switch(
                            checked = activeEffect != "off",
                            enabled = isConnected && !isBusyAction,
                            onCheckedChange = { turnOn ->
                                isBusyAction = true
                                scope.launch {
                                    if (turnOn) ArcoClient.startEffect(lastNonOffEffect) else ArcoClient.stop()
                                    isBusyAction = false
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = com.miku.launcher.ui.MikuIdentity.Leek,
                                checkedTrackColor = Color(0x3300E676),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color(0x22FFFFFF)
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Brightness slider (best-effort — no server endpoint yet, see ArcoClient.setBrightness TODO)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Brightness", color = MikuTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${(brightness * 100).toInt()}%", color = MikuGold, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                    }
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        onValueChangeFinished = {
                            scope.launch {
                                ArcoClient.setBrightness(brightness).onFailure { brightnessUnsupported = true }
                            }
                        },
                        enabled = isConnected,
                        colors = SliderDefaults.colors(thumbColor = MikuGold, activeTrackColor = MikuGold, inactiveTrackColor = Color(0x33FFD54F)),
                        modifier = Modifier.height(24.dp)
                    )
                    if (brightnessUnsupported) {
                        Text(
                            "Server build doesn't support brightness control yet.",
                            color = Color(0xFFFF9100), fontSize = 7.sp
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Quick themes
                    Text("Quick Themes", color = MikuTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(4.dp))
                    if (themes.isEmpty() && isConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MikuCyan)
                            Spacer(Modifier.width(6.dp))
                            Text("Loading themes…", color = MikuTextSecondary, fontSize = 9.sp)
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(themes.take(12)) { theme ->
                                ArcoQuickThemeSwatch(
                                    theme = theme,
                                    isActive = theme.id == activeEffect,
                                    onClick = { scope.launch { ArcoClient.startEffect(theme.id) } }
                                )
                            }
                        }
                    }

                    lastError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("⚠ $it", color = com.miku.launcher.ui.MikuIdentity.Coral, fontSize = 7.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArcoQuickThemeSwatch(theme: ArcoTheme, isActive: Boolean, onClick: () -> Unit) {
    val colors = theme.palette_hex.mapNotNull { hex ->
        runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull()
    }.ifEmpty { listOf(MikuCyan, MikuNeonPink) }

    Box(
        Modifier
            .size(width = 54.dp, height = 40.dp)
            .clip(CutCornerShape(6.dp))
            .background(Brush.linearGradient(colors))
            .border(if (isActive) 2.dp else 0.8.dp, if (isActive) Color.White else Color.Black.copy(alpha = 0.4f), CutCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 3.dp, vertical = 1.dp)) {
            Text(theme.name, color = Color.White, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ArcoModalActionButton(label: String, accentColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.28f), Color(0xFF030D14))))
            .border(1.dp, accentColor, CutCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp)
    }
}

private fun connectionStateLabel(state: ArcoConnectionState): String = when (state) {
    ArcoConnectionState.CONNECTED -> "CONNECTED"
    ArcoConnectionState.CONNECTING -> "CONNECTING…"
    ArcoConnectionState.ERROR -> "ERROR"
    ArcoConnectionState.DISCONNECTED -> "DISCONNECTED"
    ArcoConnectionState.UNPAIRED -> "NOT PAIRED"
}
