package com.miku.player.entitlement

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.miku.player.CyberDarkBg
import com.miku.player.CyberGlassBorder
import com.miku.player.CyberGlassCard
import com.miku.player.CyberInfoRow
import com.miku.player.CyberSwitchRow
import com.miku.player.MikuCyan
import com.miku.player.MikuNeonPink
import com.miku.player.MikuTextSecondary
import com.miku.player.ui.MikuTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private fun fmt(ms: Long): String = if (ms <= 0L) "never" else stamp.format(Date(ms))

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {}
}

/* ========================================================================= */
/* Settings → "License" card (modal)                                         */
/* ========================================================================= */

@Composable
fun MikuLicenseModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Reading `revision` subscribes this composable to every state change in the manager.
    val rev = EntitlementManager.revision
    val st = remember(rev) { EntitlementManager.status(ctx) }
    var overrideCode by remember { mutableStateOf("") }
    var overrideMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFFD740), MikuCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "License & Entitlement", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    // ---- Decision banner (never synthesized: mirrors EntitlementDecision) ----
                    item {
                        val d = st.decision
                        val tint = if (d.blocked) MikuNeonPink else if (st.configured && st.enabled) Color(0xFF00E676) else MikuTextSecondary
                        GlassCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (d.blocked) Icons.Default.Lock else Icons.Default.VerifiedUser, null, tint = tint, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(if (d.blocked) "DISALLOWED" else "ALLOWED", color = tint, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                    Text(d.reason, color = MikuTextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // ---- Toggle ----
                    item {
                        GlassCard {
                            CyberSwitchRow(
                                title = "Remote entitlement",
                                subtitle = if (st.configured)
                                    "Ask ${st.endpointHost} once a day whether this device may run Miku Music. Off by default; fail-open on any error."
                                else
                                    "Not configured in this build (miku.entitlement.url / .hmac are empty) — the switch is inert.",
                                checked = st.enabled,
                                onCheckedChange = { EntitlementManager.setEnabled(ctx, it) }
                            )
                        }
                    }

                    // ---- Status (all real values) ----
                    item {
                        GlassCard(spacing = 8.dp) {
                            CyberInfoRow("Configured", if (st.configured) "yes · ${st.endpointHost}" else "no")
                            CyberInfoRow("Signing", if (st.configured) "HMAC-SHA256 (baked key)" else "none")
                            CyberInfoRow("Last check", fmt(st.lastCheckAt))
                            CyberInfoRow("Last result", st.lastOutcomeLabel.ifBlank { "—" })
                            if (st.lastOutcomeDetail.isNotBlank()) {
                                Text(st.lastOutcomeDetail, color = MikuTextSecondary, fontSize = 11.sp)
                            }
                            val lv = st.lastVerdict
                            CyberInfoRow("Last signed verdict", if (lv == null) "none" else "${lv.verdict.name} · ${fmt(lv.receivedAt)}")
                            CyberInfoRow("Verdict signed?", if (lv == null) "—" else if (lv.signed) "yes (${lv.alg})" else "no")
                            if (lv != null) CyberInfoRow("Verdict expires", fmt(lv.expiresAt))
                            CyberInfoRow("Disallow chain", "${st.consecutiveDisallows}/${EntitlementConfig.GRACE_MIN_DISALLOWS} · ${st.disallowSpanMs / 3_600_000}h/${EntitlementConfig.GRACE_MIN_SPAN_MS / 3_600_000}h")
                            CyberInfoRow("Next auto check", if (st.enabled && st.configured) fmt(st.nextCheckAt) else "—")
                            CyberInfoRow("Owner override", if (st.ownerOverride) "active" else "off")
                            CyberInfoRow("History", "${st.history.size} signed verdicts")
                        }
                    }

                    // ---- Device identity ----
                    item {
                        GlassCard(spacing = 6.dp) {
                            Text("DEVICE ID", color = MikuCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(st.deviceId.ifBlank { "—" }, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("SHA-256 of ${st.idSource.ifBlank { "hardware id" }} + brand-gate fields. Paste into the dashboard to allow/deny this unit.", color = MikuTextSecondary, fontSize = 11.sp)
                            OutlinedButton(onClick = { copyToClipboard(ctx, "Device ID", st.deviceId) }) { Text("Copy device ID") }
                        }
                    }

                    // ---- Actions ----
                    item {
                        GlassCard(spacing = 8.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = st.configured && st.enabled && !st.checking,
                                    onClick = {
                                        scope.launch {
                                            val o = EntitlementManager.checkNow(ctx)
                                            Toast.makeText(ctx, "${o.label}: ${o.detail}", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MikuCyan, contentColor = Color.Black)
                                ) { Text(if (st.checking) "Checking…" else "Check now") }
                                OutlinedButton(onClick = { EntitlementManager.clearHistory(ctx) }) { Text("Clear history") }
                            }
                            Text(
                                "Clearing history only ever makes the app MORE permissive (fail-open). Nothing here can delete music, likes or stats.",
                                color = MikuTextSecondary, fontSize = 11.sp
                            )
                        }
                    }

                    // ---- Owner override ----
                    item {
                        GlassCard(spacing = 8.dp) {
                            Text("OWNER OVERRIDE", color = Color(0xFFFFD740), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(
                                "Code derived from the signing secret + this device ID (see Worker README → `owner-code`). Accepting it pins this device to ALLOWED regardless of server verdicts.",
                                color = MikuTextSecondary, fontSize = 11.sp
                            )
                            if (st.ownerOverride) {
                                OutlinedButton(onClick = { EntitlementManager.clearOwnerOverride(ctx) }) { Text("Remove override") }
                            } else {
                                OwnerCodeEntry(
                                    value = overrideCode, onValueChange = { overrideCode = it.uppercase() },
                                    enabled = st.configured,
                                    onSubmit = {
                                        val ok = EntitlementManager.tryOwnerOverride(ctx, overrideCode)
                                        overrideMsg = if (ok) "Override accepted — device pinned to ALLOWED." else "Code not accepted."
                                        if (ok) overrideCode = ""
                                    }
                                )
                                if (overrideMsg.isNotBlank()) Text(overrideMsg, color = if (st.ownerOverride) Color(0xFF00E676) else MikuNeonPink, fontSize = 12.sp)
                            }
                        }
                    }

                    // ---- Recent verdicts ----
                    if (st.history.isNotEmpty()) item {
                        GlassCard(spacing = 4.dp) {
                            Text("SIGNED VERDICT HISTORY", color = MikuCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            st.history.asReversed().take(10).forEach { r ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(fmt(r.receivedAt), color = MikuTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text(r.verdict.name, color = if (r.verdict == Verdict.ALLOW) Color(0xFF00E676) else MikuNeonPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                if (r.note.isNotBlank()) Text(r.note, color = MikuTextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun GlassCard(spacing: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CyberGlassCard)
            .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = if (spacing > 0.dp) Arrangement.spacedBy(spacing) else Arrangement.Top,
        content = content
    )
}

@Composable
private fun OwnerCodeEntry(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSubmit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled,
            singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text("XXXX-XXXX-XXXX", color = MikuTextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = MikuCyan, unfocusedBorderColor = CyberGlassBorder, cursorColor = MikuCyan
            )
        )
        Button(
            enabled = enabled && value.replace("-", "").trim().length >= 12,
            onClick = onSubmit,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD740), contentColor = Color.Black)
        ) { Text("Apply") }
    }
}

/* ========================================================================= */
/* Blocked screen — the only thing a DISALLOWED decision ever does           */
/* ========================================================================= */

/**
 * Shown by MainActivity instead of the player when [EntitlementDecision] says blocked.
 * Never destructive: library, likes, stats and preferences are untouched; a background
 * PlaybackService that was already running is left alone. Offers: the server note, contact
 * info, the device ID (copyable), a re-check button, the owner-override code path, and a way
 * to simply switch the feature off (it is opt-in, the owner can always turn it off).
 */
@Composable
fun EntitlementBlockedScreen(onUnblocked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rev = EntitlementManager.revision
    val st = remember(rev) { EntitlementManager.status(ctx) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    // The moment the decision flips back to allowed (re-check → ALLOW, override, toggle off)
    // hand control back so MainActivity can recreate itself into the normal player.
    LaunchedEffect(st.decision.blocked) { if (!st.decision.blocked) onUnblocked() }
    if (!st.decision.blocked) return

    Box(Modifier.fillMaxSize().background(CyberDarkBg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Lock, null, tint = MikuNeonPink, modifier = Modifier.size(56.dp))
            Text("Miku Music is not licensed on this device", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                st.decision.note.ifBlank { "This unit has been marked as disallowed by the licensing server." },
                color = MikuTextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Text("Reason: ${st.decision.reason}", color = MikuTextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
            Text("Contact: ${EntitlementConfig.contact}", color = MikuCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            GlassCard(spacing = 6.dp) {
                Text("DEVICE ID", color = MikuCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(st.deviceId, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = { copyToClipboard(ctx, "Device ID", st.deviceId) }) { Text("Copy device ID") }
                CyberInfoRow("Last check", fmt(st.lastCheckAt))
                CyberInfoRow("Verdict signed?", if (st.lastVerdict?.signed == true) "yes (${st.lastVerdict.alg})" else "no")
            }

            GlassCard(spacing = 8.dp) {
                Text("OWNER OVERRIDE", color = Color(0xFFFFD740), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                OwnerCodeEntry(
                    value = code, onValueChange = { code = it.uppercase() }, enabled = true,
                    onSubmit = {
                        val ok = EntitlementManager.tryOwnerOverride(ctx, code)
                        msg = if (ok) "Override accepted." else "Code not accepted."
                    }
                )
                if (msg.isNotBlank()) Text(msg, color = MikuNeonPink, fontSize = 12.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !st.checking,
                    onClick = {
                        scope.launch {
                            val o = EntitlementManager.checkNow(ctx)
                            Toast.makeText(ctx, "${o.label}: ${o.detail}", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MikuCyan, contentColor = Color.Black)
                ) { Text(if (st.checking) "Checking…" else "Re-check now") }
                OutlinedButton(onClick = { EntitlementManager.setEnabled(ctx, false) }) { Text("Turn feature off") }
            }
            Text(
                "Your music, likes and listening history are untouched. Remote entitlement is an opt-in feature; turning it off restores the player immediately.",
                color = MikuTextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center
            )
        }
    }
}
