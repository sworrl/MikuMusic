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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FnLockSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent {
            FnLockSettingsScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FnLockSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val scope = rememberCoroutineScope()

    var fnMode by remember {
        mutableStateOf(android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock")
    }
    var allowVolume by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_allow_volume_wheel", 0) == 1)
    }
    var lockPower by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_lock_power_button", 1) == 1)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        // Bespoke Hatsune Miku Pose Backdrop
        Image(
            painter = painterResource(id = R.drawable.miku_pose_headphones),
            contentDescription = "Miku Fn Key Artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Frosted Dark Cyan Gradient Overlay
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE040D12),
                            Color(0xBB000000),
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
                    "FN KEY & POCKET GUARD",
                    color = MikuCyan,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                // Status Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x3300E5FF))
                        .border(1.dp, MikuCyan, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "HARDWARE",
                        color = MikuCyan,
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
                // Info Banner
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.5.dp, MikuCyan, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "PHYSICAL HARDWARE SWITCH CONTROLS",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Controls how the physical side toggle switch and hardware buttons behave to prevent pocket misfires and accidental track skips.",
                            color = MikuTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp
                        )
                    }
                }

                // Switch Behavior Mode Selection
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
                            "SIDE TOGGLE ACTUATION MODE",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(10.dp))

                        val modes = listOf(
                            Triple("touch_and_key_lock", "Touchscreen & Key Lock (Recommended)", "Disables touchscreen digitizer and side transport buttons. Keeps volume wheel operational."),
                            Triple("key_lock", "Key Lock Only", "Disables physical track skip and play/pause buttons while keeping touchscreen active."),
                            Triple("touch_lock", "Touchscreen Lock Only", "Disables capacitive touch digitizer while allowing physical playback buttons to operate.")
                        )

                        modes.forEach { (id, label, desc) ->
                            val isSel = fnMode == id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) Color(0x3300E5FF) else Color.Transparent)
                                    .clickable {
                                        fnMode = id
                                        try {
                                            android.provider.Settings.Global.putString(cr, "fn_settings", id)
                                        } catch (t: Throwable) {
                                            // WRITE_SECURE_SETTINGS is a platform grant on this build; a
                                            // failure here is a permission bug to fix, not something a
                                            // (non-existent) su shell can paper over.
                                            android.util.Log.w("FnLockSettings", "fn_settings write refused: $t")
                                        }
                                        val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                        if (isCurrentlyLocked) {
                                            MikuPocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = {
                                        fnMode = id
                                        try {
                                            android.provider.Settings.Global.putString(cr, "fn_settings", id)
                                        } catch (t: Throwable) {
                                            // WRITE_SECURE_SETTINGS is a platform grant on this build; a
                                            // failure here is a permission bug to fix, not something a
                                            // (non-existent) su shell can paper over.
                                            android.util.Log.w("FnLockSettings", "fn_settings write refused: $t")
                                        }
                                        val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                        if (isCurrentlyLocked) {
                                            MikuPocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MikuCyan, unselectedColor = Color.Gray)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(desc, color = MikuTextSecondary, fontSize = 10.sp, lineHeight = 13.5.sp)
                                }
                            }
                        }
                    }
                }

                // Granular Button Lock Safeguards
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
                            "POCKET SAFEGUARDS & OVERRIDES",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(10.dp))

                        // Allow Rotary Volume Wheel
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Allow Volume Wheel in Pocket", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Allows adjusting audio level via the physical volume dial even when Fn Lock is active.", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = allowVolume,
                                onCheckedChange = {
                                    allowVolume = it
                                    val v = if (it) 1 else 0
                                    try {
                                        android.provider.Settings.Global.putInt(cr, "m500_fn_allow_volume_wheel", v)
                                    } catch (t: Throwable) {
                                        android.util.Log.w("FnLockSettings", "m500_fn_allow_volume_wheel write refused: $t")
                                    }
                                    val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                    if (isCurrentlyLocked) {
                                        MikuPocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuCyan, checkedTrackColor = Color(0xFF00695C))
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(10.dp))

                        // Lock Power Button
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Lock Power Key When Switched", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Prevents accidental screen wake-ups in pocket when pressing the power button.", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = lockPower,
                                onCheckedChange = {
                                    lockPower = it
                                    val v = if (it) 1 else 0
                                    try {
                                        android.provider.Settings.Global.putInt(cr, "m500_fn_lock_power_button", v)
                                    } catch (t: Throwable) {
                                        android.util.Log.w("FnLockSettings", "m500_fn_lock_power_button write refused: $t")
                                    }
                                    val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                    if (isCurrentlyLocked) {
                                        MikuPocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuNeonPink, checkedTrackColor = Color(0xFF880E4F))
                            )
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
