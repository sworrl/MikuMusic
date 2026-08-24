package com.miku.launcher.menu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miku.launcher.*
import com.miku.launcher.ui.swipeUpFromBottomToDismiss

/**
 * Hatsune Miku Lifted AOSP Launcher3 Long-Press Desktop Options Menu.
 * Replaces stock AOSP OptionsPopupView with a bespoke, themed Kotlin Compose implementation
 * featuring Wallpaper & Style, Widgets, and MikuOS Home Settings.
 */
@Composable
fun MikuHomescreenLongpressMenu(
    onWallpaperAndStyle: () -> Unit,
    onWidgets: () -> Unit,
    onHomeSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                // System-gesture-style dismiss: swipe up starting at the bottom edge of the scrim.
                .swipeUpFromBottomToDismiss(onDismiss = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(260.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xF2071F2B),
                                Color(0xF5030F15)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.2.dp,
                            Brush.verticalGradient(
                                listOf(
                                    MikuCyan.copy(alpha = 0.9f),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    MikuNeonPink.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(vertical = 10.dp, horizontal = 12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header Pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "DESKTOP OPTIONS",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 0.8.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MikuNeonPink)
                        )
                    }

                    HorizontalDivider(
                        color = CyberGlassBorder.copy(alpha = 0.4f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    // 1. Wallpaper & Style (AOSP 1:1)
                    AospMenuItem(
                        title = "Wallpaper & style",
                        subtitle = "Themes, live art & colors",
                        icon = Icons.Default.Palette,
                        iconTint = MikuCyan,
                        onClick = {
                            onDismiss()
                            onWallpaperAndStyle()
                        }
                    )

                    // 2. Widgets (AOSP 1:1)
                    AospMenuItem(
                        title = "Widgets & Panels",
                        subtitle = "Audio visualizers & meters",
                        icon = Icons.Default.Widgets,
                        iconTint = Color(0xFFFFD600),
                        onClick = {
                            onDismiss()
                            onWidgets()
                        }
                    )

                    // 3. Home Settings (AOSP 1:1)
                    AospMenuItem(
                        title = "Home settings",
                        subtitle = "Diva dock, gestures & grid",
                        icon = Icons.Default.Tune,
                        iconTint = MikuNeonPink,
                        onClick = {
                            onDismiss()
                            onHomeSettings()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AospMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.18f))
                .border(1.dp, iconTint.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            Text(
                text = subtitle,
                color = MikuTextSecondary,
                fontSize = 9.sp
            )
        }
    }
}
