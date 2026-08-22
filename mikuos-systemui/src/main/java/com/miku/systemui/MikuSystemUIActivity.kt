package com.miku.systemui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MikuSystemUIActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MikuSystemUIConfigScreen(onExit = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikuSystemUIConfigScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MIKU", color = MikuTealBright, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        Text("OS SYSTEMUI", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit", tint = MikuTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MikuDarkBg)
            )
        },
        containerColor = MikuDarkBg
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.mikuGlassCard().padding(14.dp)) {
                    Text("LIVE QUICK SETTINGS SHADE", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))

                    ConfigRow(
                        icon = Icons.Default.OpenInBrowser,
                        title = "Open Quick Settings & Notification Shade",
                        subtitle = "Launch full-screen Compose shade with 3x3 tiles, audio toggles & sliders",
                        onClick = {
                            val intent = Intent(ctx, MikuShadeActivity::class.java)
                            ctx.startActivity(intent)
                        }
                    )
                }
            }

            item {
                Column(Modifier.mikuGlassCard().padding(14.dp)) {
                    Text("OVERLAY PERMISSIONS & SERVICES", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))

                    ConfigRow(
                        icon = Icons.Default.Layers,
                        title = "Draw Over Other Apps",
                        subtitle = "Required to display custom Quick Settings shade",
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                                ctx.startActivity(intent)
                            } catch (_: Throwable) {}
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    ConfigRow(
                        icon = Icons.Default.AccessibilityNew,
                        title = "Accessibility Gesture Service",
                        subtitle = "Required for top-edge pull-down gesture detection",
                        onClick = {
                            try {
                                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Throwable) {}
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    ConfigRow(
                        icon = Icons.Default.Notifications,
                        title = "Notification Listener Access",
                        subtitle = "Required to display incoming notifications in shade",
                        onClick = {
                            try {
                                ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            } catch (_: Throwable) {}
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun ConfigRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MikuMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MikuMuted, modifier = Modifier.size(18.dp))
    }
}
