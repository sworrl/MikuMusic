package com.miku.systemui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MikuOS Custom Themed Power Menu (Global Actions / Long Power Press Modal)
 * Provides tactile, cyberpunk-styled hardware power actions including Lockdown,
 * Fast Reboot, Power Off, Recovery, Bootloader, and SystemUI restart.
 */
class MikuPowerMenuActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {}

        window.setBackgroundDrawableResource(android.R.color.transparent)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.setDimAmount(0.60f)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MikuPowerMenuScreen(
                onDismiss = { finish() }
            )
        }
    }

    override fun finish() {
        super.finish()
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {}
    }
}

@Composable
fun MikuPowerMenuScreen(
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingActionName by remember { mutableStateOf<String?>(null) }
    var pendingActionCommand by remember { mutableStateOf<String?>(null) }
    var pendingActionAccent by remember { mutableStateOf(MikuTealBright) }
    var countdownSeconds by remember { mutableIntStateOf(3) }

    fun hapticTick() {
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vib?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    fun executeAction(command: String) {
        hapticTick()
        scope.launch(Dispatchers.IO) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            when (command) {
                "lockdown" -> {
                    try {
                        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "26"))
                    } catch (_: Throwable) {}
                    val lockIntent = Intent().apply {
                        setClassName("com.miku.launcher", "com.miku.launcher.lockscreen.MikuLockscreenActivity")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    try { ctx.startActivity(lockIntent) } catch (_: Throwable) {}
                    (ctx as? Activity)?.finish()
                }
                "systemui" -> {
                    try {
                        Runtime.getRuntime().exec(arrayOf("pkill", "-f", "com.miku.systemui"))
                    } catch (_: Throwable) {}
                    (ctx as? Activity)?.finish()
                }
                "reboot -p" -> {
                    try {
                        val shutdownIntent = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
                        shutdownIntent.putExtra("android.intent.extra.KEY_CONFIRM", false)
                        shutdownIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(shutdownIntent)
                    } catch (_: Throwable) {}
                }
                else -> {
                    val reason = when (command) {
                        "reboot recovery" -> "recovery"
                        "reboot bootloader" -> "bootloader"
                        else -> null
                    }
                    try {
                        pm?.reboot(reason)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // Battery level telemetry
    val batteryPct = remember {
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        } catch (_: Throwable) { 100 }
    }

    // Infinite breathing glow for the power core arc
    val infiniteTransition = rememberInfiniteTransition(label = "corePulse")
    val coreGlow by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "coreGlow"
    )

    // Backdrop with tap-to-dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC03090C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (pendingActionName != null) {
                    pendingActionName = null
                    pendingActionCommand = null
                } else {
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Futuristic Card Frame
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {} // Intercept taps inside card
                .drawBehind {
                    val rr = CornerRadius(22.dp.toPx(), 22.dp.toPx())
                    drawRoundRect(
                        Color(0x88000000),
                        topLeft = Offset(0f, 6.dp.toPx()),
                        size = size,
                        cornerRadius = rr
                    )
                }
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFA0B222A),
                            Color(0xF506141A),
                            Color(0xFA030B0F)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(
                        listOf(
                            MikuTealBright.copy(alpha = 0.85f * coreGlow),
                            MikuPurple.copy(alpha = 0.60f),
                            MikuPinkBright.copy(alpha = 0.80f * coreGlow)
                        )
                    ),
                    RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Arc Reactor Core & Telemetry Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cyan energy core icon
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            MikuTealBright.copy(alpha = 0.4f * coreGlow),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .border(1.2.dp, MikuTealBright, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "MIKUOS POWER CORE",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "GLOBAL ACTIONS & HARDWARE SECURITY",
                                color = MikuTealBright.copy(alpha = 0.85f),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    // Battery Telemetry Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF081C22))
                            .border(1.dp, MikuTeal.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "⚡ $batteryPct%",
                            color = MikuTealBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Pending Action Countdown Dialog Overlay (if armed)
                if (pendingActionName != null && pendingActionCommand != null) {
                    LaunchedEffect(pendingActionCommand) {
                        countdownSeconds = 3
                        while (countdownSeconds > 0) {
                            delay(1000)
                            countdownSeconds--
                        }
                        executeAction(pendingActionCommand!!)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF031015))
                            .border(1.2.dp, pendingActionAccent, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "EXECUTING $pendingActionName",
                            color = pendingActionAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Action engages in $countdownSeconds seconds...",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    pendingActionName = null
                                    pendingActionCommand = null
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("CANCEL", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { executeAction(pendingActionCommand!!) },
                                modifier = Modifier.weight(1f).height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = pendingActionAccent.copy(alpha = 0.35f)),
                                border = BorderStroke(1.2.dp, pendingActionAccent),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("NOW", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Action Grid: 6 Themed 3D Action Cards (2 Columns x 3 Rows)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Row 1: Lockdown & Reboot
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PowerActionTile(
                                modifier = Modifier.weight(1f),
                                title = "LOCKDOWN",
                                subtitle = "Secure & Force AOD",
                                icon = Icons.Default.Shield,
                                accentColor = MikuTealBright,
                                onClick = {
                                    executeAction("lockdown")
                                }
                            )
                            PowerActionTile(
                                modifier = Modifier.weight(1f),
                                title = "REBOOT",
                                subtitle = "Fast OS Restart",
                                icon = Icons.Default.RestartAlt,
                                accentColor = Color(0xFF00E5FF),
                                onClick = {
                                    pendingActionName = "REBOOT"
                                    pendingActionCommand = "reboot"
                                    pendingActionAccent = Color(0xFF00E5FF)
                                }
                            )
                        }

                        // Row 2: Power Off & Recovery
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PowerActionTile(
                                modifier = Modifier.weight(1f),
                                title = "POWER OFF",
                                subtitle = "Full Shutdown",
                                icon = Icons.Default.PowerSettingsNew,
                                accentColor = MikuPinkBright,
                                onClick = {
                                    pendingActionName = "POWER OFF"
                                    pendingActionCommand = "reboot -p"
                                    pendingActionAccent = MikuPinkBright
                                }
                            )
                            PowerActionTile(
                                modifier = Modifier.weight(1f),
                                title = "RECOVERY",
                                subtitle = "Recovery Partition",
                                icon = Icons.Default.SettingsBackupRestore,
                                accentColor = MikuPurple,
                                onClick = {
                                    pendingActionName = "RECOVERY"
                                    pendingActionCommand = "reboot recovery"
                                    pendingActionAccent = MikuPurple
                                }
                            )
                        }

                        // Row 3: Fastboot & SystemUI
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PowerActionTile(
                                modifier = Modifier.weight(1f),
                                title = "FASTBOOT",
                                subtitle = "Bootloader Mode",
                                icon = Icons.Default.DeveloperMode,
                                accentColor = MikuGold,
                                onClick = {
                                    pendingActionName = "FASTBOOT"
                                    pendingActionCommand = "reboot bootloader"
                                    pendingActionAccent = MikuGold
                                }
                            )
                            PowerActionTile(
                                modifier = Modifier.weight(1f),
                                title = "SYSTEMUI",
                                subtitle = "Soft Restart Shell",
                                icon = Icons.Default.Refresh,
                                accentColor = Color(0xFF00E676),
                                onClick = {
                                    executeAction("systemui")
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Bottom Close / Dismiss Pill
                Button(
                    onClick = {
                        hapticTick()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x18FFFFFF)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "DISMISS",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

/**
 * 3D Tactile Action Card for the Power Core Modal
 */
@Composable
fun PowerActionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .height(76.dp)
            .drawBehind {
                val rr = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                drawRoundRect(
                    Color(0x66000000),
                    topLeft = Offset(0f, 3.dp.toPx()),
                    size = size,
                    cornerRadius = rr
                )
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B2129),
                        Color(0xFF05131A),
                        Color(0xFF02090D)
                    )
                )
            )
            .border(
                1.2.dp,
                Brush.verticalGradient(
                    listOf(
                        accentColor.copy(alpha = 0.75f),
                        accentColor.copy(alpha = 0.20f),
                        Color.Transparent
                    )
                ),
                RoundedCornerShape(14.dp)
            )
            .clickable {
                try {
                    val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vib?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Throwable) {}
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Badge with glowing halo
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.18f))
                    .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = MikuMuted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
