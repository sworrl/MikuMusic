package com.miku.launcher.arco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.CyberDarkBg
import com.miku.launcher.CyberGlassBorder
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuGold
import com.miku.launcher.MikuNeonPink
import com.miku.launcher.MikuTextPrimary
import com.miku.launcher.MikuTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Standalone full-screen settings surface for the arcobocconotto RGB fleet
 * integration: pairing (discover/manual IP → SAS confirm), connection status,
 * blackout/power, global brightness, the 100+ theme browser, a dynamic zone
 * viewer, the music-visualizer BPM bridge toggle, and paired-device
 * management. Registered standalone in AndroidManifest.xml — does not touch
 * MikuLauncherActivity.
 */
class MikuArcoSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArcoClient.init(applicationContext)
        setContent {
            ArcoSettingsScreen(onBack = { finish() })
        }
    }
}

private enum class ArcoSettingsTab { CONNECTION, DASHBOARD, THEMES, ZONES, ADVANCED }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ArcoSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ArcoSettingsTab.CONNECTION) }
    BackHandler(onBack = onBack)

    val connectionState by ArcoClient.connectionState.collectAsState()
    val isPaired = ArcoClient.isPaired

    LaunchedEffect(connectionState) {
        if (connectionState == ArcoConnectionState.CONNECTED) {
            ArcoClient.refreshEffectsAndZones()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
            .padding(horizontal = 14.dp, vertical = 16.dp)
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MikuCyan)
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("ARCOBOCCONOTTO", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp)
                Text("RGB Fleet Controller Integration", color = MikuTextSecondary, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Tab pills
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArcoTabPill("LINK", tab == ArcoSettingsTab.CONNECTION) { tab = ArcoSettingsTab.CONNECTION }
            if (isPaired) {
                ArcoTabPill("DASHBOARD", tab == ArcoSettingsTab.DASHBOARD) { tab = ArcoSettingsTab.DASHBOARD }
                ArcoTabPill("THEMES", tab == ArcoSettingsTab.THEMES) { tab = ArcoSettingsTab.THEMES }
                ArcoTabPill("ZONES", tab == ArcoSettingsTab.ZONES) { tab = ArcoSettingsTab.ZONES }
            }
            ArcoTabPill("ADVANCED", tab == ArcoSettingsTab.ADVANCED) { tab = ArcoSettingsTab.ADVANCED }
        }

        Spacer(Modifier.height(12.dp))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                ArcoSettingsTab.CONNECTION -> ArcoConnectionPane(onPaired = { tab = ArcoSettingsTab.DASHBOARD })
                ArcoSettingsTab.DASHBOARD -> ArcoDashboardPane()
                ArcoSettingsTab.THEMES -> ArcoThemeBrowserPane()
                ArcoSettingsTab.ZONES -> ArcoZonesPane()
                ArcoSettingsTab.ADVANCED -> ArcoAdvancedPane()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ArcoTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CutCornerShape(6.dp))
            .background(if (selected) MikuCyan.copy(alpha = 0.22f) else Color(0x140A222C))
            .border(1.dp, if (selected) MikuCyan else CyberGlassBorder, CutCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) MikuCyan else MikuTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
    }
}

private fun arcoCardModifier(accent: Color) = Modifier
    .fillMaxWidth()
    .padding(bottom = 12.dp)
    .clip(CutCornerShape(12.dp))
    .background(Brush.verticalGradient(listOf(Color(0xEE0C2936), Color(0xFF04141E), Color(0xFF020B10))))
    .border(BorderStroke(1.dp, Brush.linearGradient(listOf(accent, CyberGlassBorder.copy(alpha = 0.4f), accent.copy(alpha = 0.6f)))), CutCornerShape(12.dp))
    .padding(12.dp)

@Composable
private fun ArcoCardTitle(text: String, accent: Color) {
    Text(text, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 0.8.sp)
    Spacer(Modifier.height(8.dp))
}

// =============================================================================
// CONNECTION / PAIRING PANE
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcoConnectionPane(onPaired: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by ArcoClient.connectionState.collectAsState()
    val discovered by ArcoClient.discovery.discovered.collectAsState()
    val isDiscovering by ArcoClient.discovery.isDiscovering.collectAsState()
    val isPaired = ArcoClient.isPaired

    var manualUrl by remember { mutableStateOf(ArcoClient.serverUrl ?: "") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "MikuOS Device") }
    var pairingSession by remember { mutableStateOf<ArcoPairInitResponse?>(null) }
    var pairingServerUrl by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }

    LaunchedEffect(pairingSession) {
        val session = pairingSession ?: return@LaunchedEffect
        secondsLeft = session.expires_in
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        if (pairingSession == session) pairingSession = null // session expired locally
    }

    DisposableDiscoveryEffect()

    if (isPaired) {
        Column(arcoCardModifier(Color(0xFF00E676))) {
            ArcoCardTitle("PAIRED", Color(0xFF00E676))
            Text("Device: ${ArcoClient.deviceName ?: "—"}", color = MikuTextPrimary, fontSize = 11.sp)
            Text("Server: ${ArcoClient.serverUrl ?: "—"}", color = MikuTextSecondary, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (connectionState == ArcoConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF9100)))
                Spacer(Modifier.width(4.dp))
                Text(connectionState.name, color = MikuTextSecondary, fontSize = 9.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArcoSmallButton("Reconnect", MikuCyan) { ArcoClient.connectWebSocket() }
                ArcoSmallButton("Unpair", Color(0xFFFF1744)) { ArcoClient.unpair() }
            }
        }
    }

    // --- Discovery ---
    Column(arcoCardModifier(MikuCyan)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ArcoCardTitle("DISCOVER ON LAN", MikuCyan)
            IconButton(onClick = {
                if (isDiscovering) ArcoClient.discovery.stopDiscovery() else ArcoClient.discovery.startDiscovery()
            }) {
                if (isDiscovering) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MikuCyan)
                else Icon(Icons.Default.Radar, contentDescription = "Scan for arcobocconotto (_arcobocconotto._tcp)", tint = MikuCyan)
            }
        }
        if (discovered.isEmpty()) {
            Text(
                if (isDiscovering) "Scanning _arcobocconotto._tcp…" else "No servers found yet. Tap the radar icon to scan the LAN.",
                color = MikuTextSecondary, fontSize = 9.5.sp
            )
        } else {
            discovered.forEach { server ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(server.name, color = MikuTextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        Text(server.url, color = MikuTextSecondary, fontSize = 8.5.sp)
                    }
                    ArcoSmallButton("Pair", MikuCyan) { manualUrl = server.url }
                }
            }
        }
    }

    // --- Manual entry / pairing initiation ---
    Column(arcoCardModifier(MikuGold)) {
        ArcoCardTitle("SERVER ADDRESS", MikuGold)
        OutlinedTextField(
            value = manualUrl,
            onValueChange = { manualUrl = it },
            label = { Text("http(s)://host:port", fontSize = 9.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = arcoTextFieldColors(MikuGold)
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Convenience fill from the optional gitignored arco.properties — never a source literal.
            ArcoConfig.buildConfigLanUrl()?.let { lan ->
                ArcoSmallButton("Use Saved LAN", MikuGold) { manualUrl = lan }
            }
            ArcoConfig.buildConfigPublicUrl()?.let { pub ->
                ArcoSmallButton("Use Saved Public", MikuGold) { manualUrl = pub }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device name shown to the daemon", fontSize = 9.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = arcoTextFieldColors(MikuGold)
        )
        Spacer(Modifier.height(8.dp))
        ArcoModalActionButtonFull(
            label = if (isWorking) "REQUESTING…" else "REQUEST PAIRING CODE",
            accentColor = MikuGold,
            enabled = manualUrl.isNotBlank() && !isWorking
        ) {
            isWorking = true
            errorText = null
            scope.launch {
                ArcoClient.initiatePairing(manualUrl.trim(), deviceName.ifBlank { "MikuOS Device" })
                    .onSuccess { pairingSession = it; pairingServerUrl = manualUrl.trim() }
                    .onFailure { errorText = it.message ?: "Pairing request failed" }
                isWorking = false
            }
        }
    }

    // --- SAS confirmation ---
    val session = pairingSession
    if (session != null) {
        Column(arcoCardModifier(MikuNeonPink)) {
            ArcoCardTitle("CONFIRM CODE (${secondsLeft}s)", MikuNeonPink)
            Text("Check that the desktop / tray notification on ${session.server_name} shows the SAME code:", color = MikuTextSecondary, fontSize = 9.5.sp)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x22FF4081)).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(session.display_code, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArcoModalActionButtonFull(label = if (isWorking) "CONFIRMING…" else "CONFIRM & PAIR", accentColor = Color(0xFF00E676), enabled = !isWorking, modifier = Modifier.weight(1f)) {
                    isWorking = true
                    errorText = null
                    scope.launch {
                        ArcoClient.verifyPairing(pairingServerUrl, session.session_id, session.code)
                            .onSuccess { pairingSession = null; onPaired() }
                            .onFailure { errorText = it.message ?: "Verification failed" }
                        isWorking = false
                    }
                }
                ArcoSmallButton("Cancel", MikuTextSecondary) { pairingSession = null }
            }
        }
    }

    errorText?.let {
        Text("⚠ $it", color = Color(0xFFFF1744), fontSize = 9.5.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun DisposableDiscoveryEffect() {
    androidx.compose.runtime.DisposableEffect(Unit) {
        ArcoClient.discovery.startDiscovery()
        onDispose { ArcoClient.discovery.stopDiscovery() }
    }
}

// =============================================================================
// DASHBOARD PANE
// =============================================================================

@Composable
private fun ArcoDashboardPane() {
    val scope = rememberCoroutineScope()
    val connectionState by ArcoClient.connectionState.collectAsState()
    val activeEffect by ArcoClient.activeEffect.collectAsState()
    val bridgeEnabled by ArcoClient.musicVisualizerBridgeEnabled.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var lastNonOff by remember { mutableStateOf("cyberpunk_city") }
    LaunchedEffect(activeEffect) { if (activeEffect.isNotBlank() && activeEffect != "off") lastNonOff = activeEffect }
    var brightness by remember { mutableFloatStateOf(1f) }
    var brightnessUnsupported by remember { mutableStateOf(false) }

    Column(arcoCardModifier(MikuCyan)) {
        ArcoCardTitle("LIVE STATUS", MikuCyan)
        Text("Active effect: $activeEffect", color = MikuTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Link: ${connectionState.name}", color = MikuTextSecondary, fontSize = 9.5.sp)
    }

    Column(arcoCardModifier(Color(0xFF00E676))) {
        ArcoCardTitle("POWER", Color(0xFF00E676))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = if (activeEffect != "off") Color(0xFF00E676) else Color(0xFF8BA6A9))
                Spacer(Modifier.width(6.dp))
                Text(if (activeEffect != "off") "Fleet is ON" else "Fleet is blacked out", color = MikuTextPrimary, fontSize = 11.sp)
            }
            Switch(
                checked = activeEffect != "off",
                onCheckedChange = { on -> scope.launch { if (on) ArcoClient.startEffect(lastNonOff) else ArcoClient.stop() } },
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E676), checkedTrackColor = Color(0x3300E676))
            )
        }
        Spacer(Modifier.height(6.dp))
        ArcoSmallButton("Blackout Now", Color(0xFFFF1744)) { scope.launch { ArcoClient.stop() } }
    }

    Column(arcoCardModifier(MikuGold)) {
        ArcoCardTitle("GLOBAL BRIGHTNESS", MikuGold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = MikuGold)
            Text("${(brightness * 100).toInt()}%", color = MikuGold, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        }
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            onValueChangeFinished = { scope.launch { ArcoClient.setBrightness(brightness).onFailure { brightnessUnsupported = true } } },
            colors = SliderDefaults.colors(thumbColor = MikuGold, activeTrackColor = MikuGold)
        )
        if (brightnessUnsupported) {
            Text("Server build doesn't expose a brightness endpoint yet (client is ready — see ArcoClient.setBrightness).", color = Color(0xFFFF9100), fontSize = 8.sp)
        }
    }

    Column(arcoCardModifier(MikuNeonPink)) {
        ArcoCardTitle("MUSIC VISUALIZER BRIDGE", MikuNeonPink)
        Text(
            "Feeds MikuOS's live BPM engine (com.miku.action.BPM_UPDATE / BPM_PULSE, Settings.Global miku_live_bpm) into arco's visualizer themes (music_spectrum, music_bass_pulse, …) via POST /api/audio.",
            color = MikuTextSecondary, fontSize = 8.5.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Bridge enabled", color = MikuTextPrimary, fontSize = 11.sp)
            Switch(
                checked = bridgeEnabled,
                onCheckedChange = { on ->
                    ArcoClient.setMusicVisualizerBridgeEnabled(on)
                    if (on) ArcoMusicVisualizerBridge.start(ctx) else ArcoMusicVisualizerBridge.stop()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = MikuNeonPink, checkedTrackColor = Color(0x33FF4081))
            )
        }
        Text(
            "Note: bands are synthesized from tempo/beat-phase only, not real FFT — MikuOS doesn't broadcast per-band spectral data yet. See ArcoMusicVisualizerBridge.kt's TODO(monolith) for the real wiring point.",
            color = MikuTextSecondary, fontSize = 7.5.sp
        )
    }
}

// =============================================================================
// THEME BROWSER PANE
// =============================================================================

@Composable
private fun ArcoThemeBrowserPane() {
    val scope = rememberCoroutineScope()
    val themes by ArcoClient.themes.collectAsState()
    val activeEffect by ArcoClient.activeEffect.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val categories = remember(themes) { themes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
    val filtered = remember(themes, query, selectedCategory) {
        themes.filter { t ->
            (selectedCategory == null || t.category == selectedCategory) &&
                (query.isBlank() || t.name.contains(query, true) || t.description.contains(query, true) || t.category.contains(query, true))
        }
    }

    Column(arcoCardModifier(MikuCyan)) {
        ArcoCardTitle("THEME BROWSER (${themes.size})", MikuCyan)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MikuCyan) },
            placeholder = { Text("Search themes…", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = arcoTextFieldColors(MikuCyan)
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArcoTabPill("ALL", selectedCategory == null) { selectedCategory = null }
            categories.forEach { cat ->
                ArcoTabPill(cat.uppercase(), selectedCategory == cat) { selectedCategory = if (selectedCategory == cat) null else cat }
            }
        }
    }

    if (filtered.isEmpty()) {
        Text("No themes loaded yet — connect to the fleet controller first.", color = MikuTextSecondary, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxWidth().height(((filtered.size / 2 + 1) * 96).dp.coerceAtMost(2000.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { theme ->
                ArcoThemeCard(theme = theme, isActive = theme.id == activeEffect) {
                    scope.launch { ArcoClient.startEffect(theme.id) }
                }
            }
        }
    }
}

@Composable
private fun ArcoThemeCard(theme: ArcoTheme, isActive: Boolean, onClick: () -> Unit) {
    val colors = theme.palette_hex.mapNotNull { hex -> runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull() }
        .ifEmpty { listOf(MikuCyan, MikuNeonPink) }
    Column(
        Modifier
            .clip(CutCornerShape(10.dp))
            .background(Color(0xFF04141E))
            .border(if (isActive) 2.dp else 0.8.dp, if (isActive) Color.White else CyberGlassBorder, CutCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(36.dp).background(Brush.linearGradient(colors)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(theme.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (theme.category.isNotBlank()) Text(theme.category, color = MikuTextSecondary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// =============================================================================
// ZONES PANE — deliberately dynamic. Never render a fixed hardware diagram
// here; the rig's zones/LEDs keep growing over time. See the TODO(server)
// note on ArcoZone in ArcoModels.kt and ArcoClient.fetchZones().
// =============================================================================

@Composable
private fun ArcoZonesPane() {
    val scope = rememberCoroutineScope()
    val zones by ArcoClient.zones.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var flashColor by remember { mutableStateOf("FF0055") }

    LaunchedEffect(Unit) { ArcoClient.fetchZones() }

    Column(arcoCardModifier(MikuGold)) {
        ArcoCardTitle("ZONES (${zones.size}, LIVE)", MikuGold)
        Text(
            "Fetched live from the server at connect time — this list grows automatically as zones/LEDs are added to the rig; nothing here is hardcoded.",
            color = MikuTextSecondary, fontSize = 8.5.sp
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            zones.forEach { zone ->
                val isSel = zone.id in selected
                Box(
                    Modifier
                        .clip(CutCornerShape(6.dp))
                        .background(if (isSel) MikuGold.copy(alpha = 0.25f) else Color(0x140A222C))
                        .border(1.dp, if (isSel) MikuGold else CyberGlassBorder, CutCornerShape(6.dp))
                        .clickable { selected = if (isSel) selected - zone.id else selected + zone.id }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(zone.name, color = if (isSel) MikuGold else MikuTextPrimary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        zone.ledCount?.let { Text("$it LEDs", color = MikuTextSecondary, fontSize = 7.sp) }
                    }
                }
            }
        }
        if (zones.isEmpty()) {
            Text("No zones reported yet. Connect first, then this refreshes automatically.", color = MikuTextSecondary, fontSize = 9.sp)
        }
    }

    Column(arcoCardModifier(MikuNeonPink)) {
        ArcoCardTitle("TEST FLASH", MikuNeonPink)
        Text("Sends a notify-style alert flash to the selected zones (or all zones if none selected).", color = MikuTextSecondary, fontSize = 8.5.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = flashColor,
            onValueChange = { flashColor = it.filter { c -> c.isLetterOrDigit() }.take(6).uppercase() },
            label = { Text("Hex color (RRGGBB)", fontSize = 9.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = arcoTextFieldColors(MikuNeonPink)
        )
        Spacer(Modifier.height(8.dp))
        ArcoModalActionButtonFull(label = "FLASH", accentColor = MikuNeonPink, enabled = flashColor.length == 6) {
            scope.launch {
                ArcoClient.notify(color = flashColor, style = "triple_flash", zones = selected.toList().ifEmpty { null })
            }
        }
    }
}

// =============================================================================
// ADVANCED PANE — direct HMAC key path + paired-device management
// =============================================================================

@Composable
private fun ArcoAdvancedPane() {
    val scope = rememberCoroutineScope()
    val devices by ArcoClient.devices.collectAsState()
    var serverUrl by remember { mutableStateOf(ArcoClient.serverUrl ?: "") }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    val isDirectKeyConfigured = ArcoClient.isDirectKeyConfigured

    Column(arcoCardModifier(MikuGold)) {
        ArcoCardTitle("DIRECT KEY (HMAC) — ADVANCED", MikuGold)
        Text(
            "Bypasses interactive SAS pairing for REST calls using a pre-shared HMAC-SHA512 key (X-Api-Key-Id / X-Signature). Does not open the live WebSocket — that requires a paired Bearer token. Never stored as source code; saved only to EncryptedSharedPreferences (or read from the optional gitignored arco.properties at build time).",
            color = MikuTextSecondary, fontSize = 8.5.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(if (isDirectKeyConfigured) "Status: configured" else "Status: not configured", color = if (isDirectKeyConfigured) Color(0xFF00E676) else MikuTextSecondary, fontSize = 9.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Server URL", fontSize = 9.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = arcoTextFieldColors(MikuGold))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = keyId, onValueChange = { keyId = it }, label = { Text("X-Api-Key-Id", fontSize = 9.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = arcoTextFieldColors(MikuGold))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("API secret", fontSize = 9.sp) },
            singleLine = true,
            visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = arcoTextFieldColors(MikuGold)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArcoSmallButton(if (showSecret) "Hide" else "Show", MikuTextSecondary) { showSecret = !showSecret }
            ArcoModalActionButtonFull(label = "SAVE DIRECT KEY", accentColor = MikuGold, enabled = serverUrl.isNotBlank() && keyId.isNotBlank() && secret.isNotBlank(), modifier = Modifier.weight(1f)) {
                ArcoClient.setDirectKey(serverUrl.trim(), keyId.trim(), secret.trim())
            }
        }
    }

    Column(arcoCardModifier(MikuCyan)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ArcoCardTitle("PAIRED DEVICES", MikuCyan)
            ArcoSmallButton("Refresh", MikuCyan) { scope.launch { ArcoClient.listDevices() } }
        }
        if (devices.isEmpty()) {
            Text("No devices loaded — tap Refresh while connected.", color = MikuTextSecondary, fontSize = 9.5.sp)
        }
        devices.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(d.name, color = MikuTextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    Text("${d.platform} · last seen ${d.last_seen}", color = MikuTextSecondary, fontSize = 8.sp)
                }
                ArcoSmallButton("Revoke", Color(0xFFFF1744)) { scope.launch { ArcoClient.revokeDevice(d.id); ArcoClient.listDevices() } }
            }
        }
    }
}

// =============================================================================
// Shared small widgets
// =============================================================================

@Composable
private fun ArcoSmallButton(label: String, accentColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CutCornerShape(6.dp))
            .background(accentColor.copy(alpha = 0.18f))
            .border(1.dp, accentColor, CutCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
    }
}

@Composable
private fun ArcoModalActionButtonFull(label: String, accentColor: Color, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier
            .fillMaxWidth()
            .clip(CutCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.28f * alpha), Color(0xFF030D14))))
            .border(1.dp, accentColor.copy(alpha = alpha), CutCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accentColor.copy(alpha = alpha), fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun arcoTextFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MikuTextPrimary,
    unfocusedTextColor = MikuTextPrimary,
    focusedBorderColor = accent,
    unfocusedBorderColor = CyberGlassBorder,
    focusedLabelColor = accent,
    unfocusedLabelColor = MikuTextSecondary,
    cursorColor = accent
)
