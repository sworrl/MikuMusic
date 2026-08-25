package com.miku.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * MikuOS notification shade — Pixel 11 layout & behaviour, Miku skin.
 *
 *   ┌ header: hearts clock + date ─────────── battery · expand chevron ┐
 *   │ QQS: 4 pill tiles (Wi-Fi · BT · Ingress · Wireless ADB)          │  ← pull further /
 *   │ brightness                                                         │    chevron = full
 *   │ media card (active MediaSession)                                   │    2-column QS grid
 *   │ notifications (swipe to dismiss, tap = open, chevron = expand)     │
 *   │ CLEAR ALL                                                          │
 *   └ footer: listener state ·········· settings · power · close ───────┘
 *
 * 8dp grid, ≥40dp touch targets, ≥11sp text, 360×640dp target.
 */

private val ShadeCorner = 28.dp
private val TileCorner = 24.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MikuNotificationShadeView(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPower: () -> Unit = {},
    startExpanded: Boolean = false
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ---------------------------------------------------------------- live state
    var tick by remember { mutableIntStateOf(0) }
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    var currentDate by remember { mutableStateOf(SimpleDateFormat("MM / dd / yyyy", Locale.getDefault()).format(Date())) }
    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<MikuMediaHub.Now?>(null) }
    var tiles by remember { mutableStateOf<List<QsTile>>(emptyList()) }
    val notifs by MikuNotificationStore.items.collectAsState()
    val listenerOk by MikuNotificationStore.connected.collectAsState()
    // Album accent (Miku Music → Settings.Global miku_np_accent), ≈30% into teal, animated 400ms.
    LaunchedEffect(Unit) { MikuAccent.observe(ctx); MikuPowerProfile.observe(ctx) }
    val powerProfile by MikuPowerProfile.profile.collectAsState()
    val quiet = powerProfile == "audio_only" || powerProfile == "idle"
    val accentRaw by MikuAccent.accent.collectAsState()
    val osAccent by animateColorAsState(Color(MikuAccent.tealTinted(accentRaw, 0.30f)), tween(400), label = "osAccent")
    val osAccentBright by animateColorAsState(Color(MikuAccent.tealBrightTinted(accentRaw, 0.30f)), tween(400), label = "osAccentBright")

    fun refreshTiles() {
        tiles = QuickSettingsModel.getTiles(ctx, scope) { tick++ } + extraTiles(ctx) { tick++ }
    }

    LaunchedEffect(tick) { refreshTiles() }
    LaunchedEffect(Unit) {
        MikuNotificationStore.ensureEnabled(ctx)
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            currentDate = SimpleDateFormat("MM / dd / yyyy", Locale.getDefault()).format(Date())
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: batteryPct
            isCharging = runCatching {
                val st = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }.getOrDefault(false)
            media = withContext(Dispatchers.IO) { runCatching { MikuMediaHub.now(ctx) }.getOrNull() }
            delay(MikuPowerProfile.pollMs(1000L))
        }
    }

    // Brightness (manual mode while the user drags)
    val cr = ctx.contentResolver
    var brightness by remember {
        mutableIntStateOf(runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128))
    }

    // ---------------------------------------------------------------- expansion
    // 0 = quick-quick settings (compact row), 1 = full QS grid; <0 = being pulled up to dismiss.
    val expand = remember { Animatable(if (startExpanded) 1f else 0f) }
    val expanded = expand.value > 0.5f
    val expandRangePx = with(density) { 220.dp.toPx() }
    var dragStartValue by remember { mutableStateOf(0f) }
    val panelIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (MikuPowerProfile.lowPower) panelIn.animateTo(1f, tween(120))
        else panelIn.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }

    fun settle() {
        scope.launch {
            val v = expand.value
            when {
                dragStartValue < 0.5f && v < -0.14f -> onDismiss()
                MikuPowerProfile.lowPower -> expand.animateTo(if (v >= 0.5f) 1f else 0f, tween(120))
                v >= 0.5f -> expand.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow))
                else -> expand.animateTo(0f, spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { dragStartValue = expand.value },
            onDragEnd = { settle() },
            onDragCancel = { settle() },
            onVerticalDrag = { change, dy ->
                change.consume()
                val next = (expand.value + dy / expandRangePx).coerceIn(-0.4f, 1.12f)
                scope.launch { expand.snapTo(next) }
            }
        )
    }

    BackHandler(enabled = true) {
        if (expanded) scope.launch { expand.animateTo(0f, spring(dampingRatio = 0.74f)) } else onDismiss()
    }

    val compactH = 56.dp
    val gridRows = (tiles.size + 1) / 2
    val expandedH = (gridRows * 64 + (gridRows - 1).coerceAtLeast(0) * 8).dp
    val qsH = compactH + (expandedH - compactH) * expand.value.coerceIn(0f, 1f)
    val pullUp = (-expand.value).coerceAtLeast(0f)   // 0..0.4 while dragging up to dismiss

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f * panelIn.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxPanelH = maxHeight - 28.dp   // keep clear of the home-pill zone
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelH)
                    .graphicsLayer {
                        translationY = -(1f - panelIn.value) * size.height * 0.35f - pullUp * size.height * 0.5f
                        alpha = (0.6f + 0.4f * panelIn.value) * (1f - pullUp * 0.8f)
                    }
                    .clip(RoundedCornerShape(bottomStart = ShadeCorner, bottomEnd = ShadeCorner))
                    .background(Brush.verticalGradient(listOf(Color(0xFA0A1E26), Color(0xFC061319), Color(0xFE030B0F))))
                    .drawBehind {
                        // soft teal + pink plasma glows behind the glass
                        drawCircle(Brush.radialGradient(listOf(MikuTeal.copy(alpha = 0.18f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.05f), radius = size.width * 0.6f), radius = size.width * 0.6f, center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.05f))
                        drawCircle(Brush.radialGradient(listOf(MikuPink.copy(alpha = 0.10f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.4f), radius = size.width * 0.5f), radius = size.width * 0.5f, center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.4f))
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                // ---------------------------------------------------- header (draggable)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .padding(start = 16.dp, end = 8.dp, top = 24.dp)
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (quiet) QuietClock(currentTime, currentDate) else CyberPlasmaGlowClock(time = currentTime, date = currentDate)
                    Spacer(Modifier.weight(1f))
                    // battery chip
                    Row(
                        Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MikuSurface2.copy(alpha = 0.9f))
                            .border(1.dp, MikuTeal.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            null, tint = if (isCharging) Color(0xFF69F0AE) else MikuTealBright, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("$batteryPct%", color = MikuWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                    // expand / collapse chevron (40dp target)
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                scope.launch { expand.animateTo(if (expanded) 0f else 1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow)) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MikuTextSecondary, modifier = Modifier.size(24.dp))
                    }
                }
                // header underline — carries the album accent
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)
                        .background(Brush.horizontalGradient(listOf(osAccentBright, osAccent.copy(alpha = 0.35f), Color.Transparent)))
                )

                Spacer(Modifier.height(8.dp))

                // ---------------------------------------------------- quick settings
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .padding(horizontal = 16.dp)
                        .height(qsH)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    val e = expand.value.coerceIn(0f, 1f)
                    // compact row
                    if (e < 0.999f) {
                        val compactIds = listOf("wifi", "bluetooth", "ingest", "wireless_adb")
                        val compact = compactIds.mapNotNull { id -> tiles.firstOrNull { it.id == id } }
                        Row(
                            Modifier.fillMaxWidth().height(compactH).graphicsLayer { alpha = 1f - e },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            compact.forEach { t -> CompactTile(t, Modifier.weight(1f), osAccent, osAccentBright) }
                        }
                    }
                    // expanded grid
                    if (e > 0.001f) {
                        Column(
                            Modifier.fillMaxWidth().graphicsLayer { alpha = e; translationY = (1f - e) * 24f },
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tiles.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { t -> GridTile(t, Modifier.weight(1f), osAccent, osAccentBright) }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ---------------------------------------------------- brightness
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MikuSurface1.copy(alpha = 0.9f))
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BrightnessMedium, null, tint = MikuTealBright, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = brightness.toFloat(),
                        onValueChange = { v ->
                            brightness = v.toInt()
                            runCatching {
                                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness)
                            }
                        },
                        valueRange = 8f..255f,
                        colors = SliderDefaults.colors(thumbColor = osAccentBright, activeTrackColor = osAccent, inactiveTrackColor = Color(0xFF12313A)),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ---------------------------------------------------- media + notifications
                val visibleNotifs = remember(notifs, media) {
                    notifs.filter { !(it.hasMediaSession && media != null && it.pkg == media?.pkg) }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    media?.let { m ->
                        item(key = "media") { MediaCard(m, onOpen = { openApp(ctx, m.pkg); onDismiss() }) }
                    }
                    items(visibleNotifs, key = { it.key }) { n ->
                        NotifRow(
                            n,
                            onOpen = { if (MikuNotificationStore.send(ctx, n.contentIntent)) { if (n.isClearable) MikuNotificationStore.dismiss(n.key); onDismiss() } },
                            onDismiss = { MikuNotificationStore.dismiss(n.key) }
                        )
                    }
                    if (visibleNotifs.any { it.isClearable }) {
                        item(key = "clear") {
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(MikuSurface2)
                                        .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                        .clickable {
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                            MikuNotificationStore.dismissAll()
                                        }
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("CLEAR ALL", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
                            }
                        }
                    }
                    if (visibleNotifs.isEmpty() && media == null) {
                        item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().height(96.dp).clickable(enabled = !listenerOk) { MikuNotificationStore.ensureEnabled(ctx) },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                            ) {
                                Text("♥", color = MikuTeal.copy(alpha = 0.55f), fontSize = 28.sp)
                                Text(
                                    if (listenerOk) "No notifications" else "Notification access is off — tap to enable",
                                    color = MikuTextSecondary, fontSize = 12.sp
                                )
                            }
                        }
                    }
                    item(key = "bottomPad") { Spacer(Modifier.height(4.dp)) }
                }

                // ---------------------------------------------------- footer
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "MikuOS ♥", color = MikuTeal.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    FooterIcon(Icons.Default.Settings, "Settings", MikuTealBright) { onDismiss(); onOpenSettings() }
                    FooterIcon(Icons.Default.PowerSettingsNew, "Power", MikuPinkBright) { onDismiss(); onOpenPower() }
                    FooterIcon(Icons.Default.Close, "Close", MikuTextSecondary) { onDismiss() }
                }
                // drag handle (swipe up to close)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .height(20.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(Modifier.padding(top = 4.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MikuTeal.copy(alpha = 0.55f)))
                }
            }
        }
    }
}

private fun openApp(ctx: Context, pkg: String) {
    runCatching {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { ctx.startActivity(it) }
    }
}

/** Device-specific extras that are not in QuickSettingsModel: ingest engine, pause-on-unplug, track HUD. */
private fun extraTiles(ctx: Context, onRefresh: () -> Unit): List<QsTile> {
    val cr = ctx.contentResolver
    val ingest = Settings.Global.getInt(cr, "miku_ingest_enabled", 0) == 1
    val pause = Settings.Global.getInt(cr, "miku_pause_on_unplug", 1) == 1
    val hud = Settings.Global.getInt(cr, "miku_track_hud_enabled", 1) == 1
    fun putGlobal(k: String, v: Int) {
        runCatching { Settings.Global.putInt(cr, k, v) }.onFailure { RootShell.execFast("settings put global $k $v") }
    }
    return listOf(
        QsTile("ingest", "Ingress Engine", if (ingest) "Rsync ingest on" else "Local SD only", Icons.Default.CloudSync, ingest,
            onClick = {
                putGlobal("miku_ingest_enabled", if (ingest) 0 else 1)
                ctx.sendBroadcast(Intent("com.miku.launcher.action.INGEST_ENABLED").setPackage("com.miku.launcher").putExtra("enabled", !ingest))
                onRefresh()
            },
            onLongClick = { runCatching { ctx.startActivity(Intent().setClassName("com.miku.launcher", "com.miku.launcher.MikuLauncherActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }),
        QsTile("pause_unplug", "Unplug Pause", if (pause) "Stock behaviour" else "Keep playing", Icons.Default.HeadsetOff, pause,
            onClick = {
                putGlobal("miku_pause_on_unplug", if (pause) 0 else 1)
                ctx.sendBroadcast(Intent("com.miku.player.SET_PAUSE_ON_UNPLUG").setPackage("com.miku.player").putExtra("enabled", !pause))
                onRefresh()
            }),
        QsTile("track_hud", "Track HUD", if (hud) "Pops over apps" else "Off", Icons.Default.MusicNote, hud,
            onClick = { putGlobal("miku_track_hud_enabled", if (hud) 0 else 1); onRefresh() })
    )
}

// ------------------------------------------------------------------------------------ tiles

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactTile(t: QsTile, modifier: Modifier, accent: Color = MikuTeal, accentBright: Color = MikuTealBright) {
    val view = LocalView.current
    val bg by animateFloatAsState(if (t.isActive) 1f else 0f, tween(160), label = "tileBg")
    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(lerpColor(MikuSurface2, accent, bg))
            .border(1.dp, if (t.isActive) accentBright.copy(alpha = 0.9f) else accent.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
            .combinedClickable(
                onClick = { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); t.onClick() },
                onLongClick = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); t.onLongClick?.invoke() }
            ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(t.icon, t.label, tint = if (t.isActive) MikuDarkBg else MikuTealBright, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        val short = when (t.id) { "wifi" -> "WI-FI"; "bluetooth" -> "BLUETOOTH"; "ingest" -> "INGRESS"; "wireless_adb" -> "ADB"; else -> t.label.uppercase().take(10) }
        Text(
            short, color = if (t.isActive) MikuDarkBg else MikuTextSecondary,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = 0.sp,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(t: QsTile, modifier: Modifier, accent: Color = MikuTeal, accentBright: Color = MikuTealBright) {
    val view = LocalView.current
    val bg by animateFloatAsState(if (t.isActive) 1f else 0f, tween(160), label = "gridTileBg")
    Row(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(TileCorner))
            .background(lerpColor(MikuSurface2, accent, bg))
            .border(1.dp, if (t.isActive) accentBright.copy(alpha = 0.9f) else accent.copy(alpha = 0.25f), RoundedCornerShape(TileCorner))
            .combinedClickable(
                onClick = { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); t.onClick() },
                onLongClick = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); t.onLongClick?.invoke() }
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (t.isActive) MikuDarkBg.copy(alpha = 0.25f) else MikuTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Icon(t.icon, t.label, tint = if (t.isActive) MikuDarkBg else MikuTealBright, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, color = if (t.isActive) MikuDarkBg else MikuWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, color = if (t.isActive) MikuDarkBg.copy(alpha = 0.75f) else MikuTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = a.alpha + (b.alpha - a.alpha) * t
)

@Composable
private fun RowScope.FooterIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ------------------------------------------------------------------------------------ media card

@Composable
private fun MediaCard(m: MikuMediaHub.Now, onOpen: () -> Unit) {
    val view = LocalView.current
    val accent = remember(m.art) { Color(MikuMediaHub.dominantColor(m.art)) }
    val progress = if (m.durationMs > 0) (m.positionMs.toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TileCorner))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.35f), MikuSurface1.copy(alpha = 0.95f))))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(TileCorner))
            .clickable { onOpen() }
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MikuSurface2), contentAlignment = Alignment.Center) {
                val art = m.art
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    m.appIcon?.let { d ->
                        val bmp = remember(m.pkg) { runCatching { d.toBitmap(32, 32).asImageBitmap() }.getOrNull() }
                        if (bmp != null) Image(bmp, null, modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(m.appLabel.uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
                }
                Text(m.title.ifBlank { "Now playing" }, color = MikuWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist.ifBlank { m.album }, color = MikuTextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); m.controller.transportControls.skipToPrevious() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = MikuWhite, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(accent).clickable {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    if (m.isPlaying) m.controller.transportControls.pause() else m.controller.transportControls.play()
                }, contentAlignment = Alignment.Center
            ) { Icon(if (m.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = MikuDarkBg, modifier = Modifier.size(24.dp)) }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); m.controller.transportControls.skipToNext() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SkipNext, "Next", tint = MikuWhite, modifier = Modifier.size(22.dp))
            }
        }
        // progress hairline
        Box(Modifier.fillMaxWidth().height(3.dp).background(MikuSurface2)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Brush.horizontalGradient(listOf(accent, MikuPinkBright))))
        }
    }
}

// ------------------------------------------------------------------------------------ notification row

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotifRow(n: MikuNotif, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var expanded by remember(n.key) { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                if (n.isClearable) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onDismiss(); true } else false
            } else true
        },
        positionalThreshold = { it * 0.4f }
    )
    val appIconBmp = remember(n.pkg) { runCatching { n.appIcon?.toBitmap(48, 48)?.asImageBitmap() }.getOrNull() }
    val accent = if (n.accent != 0) Color(n.accent) else MikuTeal
    val timeLabel = remember(n.postTime) {
        DateUtils.getRelativeTimeSpanString(n.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = n.isClearable,
        enableDismissFromEndToStart = n.isClearable,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MikuPink.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Text("♥ bye", color = MikuPinkBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MikuSurface1.copy(alpha = 0.96f))
                .border(1.dp, MikuTeal.copy(alpha = if (n.isOngoing) 0.15f else 0.3f), RoundedCornerShape(20.dp))
                .clickable { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onOpen() }
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIconBmp != null) Image(appIconBmp, null, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
                else Box(Modifier.size(16.dp).clip(CircleShape).background(accent.copy(alpha = 0.6f)))
                Spacer(Modifier.width(6.dp))
                Text(n.appLabel, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  ·  $timeLabel", color = MikuMuted, fontSize = 11.sp, maxLines = 1)
                if (n.isOngoing) Text("  ·  ongoing", color = MikuMuted, fontSize = 11.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center
                ) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MikuTextSecondary, modifier = Modifier.size(20.dp)) }
            }
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(n.title, color = MikuWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
                    val body = if (expanded) (n.bigText ?: n.text) else n.text
                    if (body.isNotBlank()) Text(body, color = MikuTextSecondary, fontSize = 12.sp, maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis)
                    n.subText?.takeIf { expanded && it.isNotBlank() }?.let { Text(it, color = MikuMuted, fontSize = 11.sp, maxLines = 1) }
                }
                n.largeIcon?.let { bmp ->
                    Spacer(Modifier.width(8.dp))
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                }
            }
            n.progressMax?.let { max ->
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().padding(end = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MikuSurface2)) {
                    Box(Modifier.fillMaxWidth(((n.progress ?: 0).toFloat() / max).coerceIn(0f, 1f)).fillMaxHeight().background(accent))
                }
            }
            AnimatedVisibility(visible = expanded && n.actions.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.padding(top = 6.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    n.actions.take(3).forEach { (label, pi) ->
                        Box(
                            Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MikuSurface2)
                                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                .clickable { MikuNotificationStore.send(ctx, pi) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(label.uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 0.5.sp) }
                    }
                }
            }
        }
    }
}


/** Static hearts clock for audio_only / idle power profiles — no infinite plasma transitions. */
@Composable
private fun QuietClock(time: String, date: String) {
    val parts = time.split(":")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(parts.getOrNull(0) ?: time, color = MikuTextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp)
            KawaiiHeartColon(color = MikuPinkBright, scale = 1f)
            Text(parts.getOrNull(1) ?: "", color = MikuTextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp)
        }
        Text(date, color = MikuTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
    }
}
