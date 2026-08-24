package com.miku.launcher.volume

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.MikuCyan

/**
 * Settings Card for Global Volume HUD Layout & Dialog Engine.
 * Allows choosing between:
 * 1. Right-Edge Cyber Bar (Default MikuOS System HUD)
 * 2. Center Cyber Dial / Modal (Miku Music Bespoke HUD)
 * Plus optional toggle for HiBy legacy stock full-screen overlay.
 */
@Composable
fun MikuVolumeUiSettingRow(
    modifier: Modifier = Modifier,
    ctx: Context = LocalContext.current
) {
    var useHiby by remember { mutableStateOf(MikuVolumeManager.useHibyDialog(ctx)) }
    var selectedStyle by remember { mutableStateOf(MikuVolumeManager.getVolumeHudStyle(ctx)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToRoundedGlass()
            .padding(14.dp)
    ) {
        Text(
            text = "VOLUME HUD MODAL STYLE",
            color = MikuCyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Select which holographic volume overlay appears when adjusting volume in any app or homescreen:",
            color = Color(0xFF9EC4C7),
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        // Option 1: Right-Edge Cyber Bar (Default)
        VolumeStyleOptionCard(
            title = "Right-Edge Cyber Bar (Default)",
            description = "Sleek vertical neon bar anchored to the right side of the display with touch dragging",
            isSelected = selectedStyle == VolumeHudStyle.RIGHT_CYBER_BAR,
            onClick = {
                selectedStyle = VolumeHudStyle.RIGHT_CYBER_BAR
                MikuVolumeManager.setVolumeHudStyle(ctx, VolumeHudStyle.RIGHT_CYBER_BAR)
                MikuVolumeManager.triggerHud(ctx)
            }
        )

        Spacer(Modifier.height(8.dp))

        // Option 2: Center Cyber Arc / Modal (Miku Music Style)
        VolumeStyleOptionCard(
            title = "Center Cyber Dial / Modal",
            description = "Centered floating cyberpunk capsule with danger zone meter (original Miku Music style)",
            isSelected = selectedStyle == VolumeHudStyle.CENTER_CYBER_MODAL,
            onClick = {
                selectedStyle = VolumeHudStyle.CENTER_CYBER_MODAL
                MikuVolumeManager.setVolumeHudStyle(ctx, VolumeHudStyle.CENTER_CYBER_MODAL)
                MikuVolumeManager.triggerHud(ctx)
            }
        )

        Spacer(Modifier.height(14.dp))

        // Legacy HiBy Fullscreen Dialog Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Legacy HiBy Fullscreen Dialog",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (useHiby) "Active (HiBy factory fullscreen wheel overlay)" else "Disabled (MikuOS Custom HUD active)",
                    color = Color(0xFF7FB9C6),
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = useHiby,
                onCheckedChange = {
                    useHiby = it
                    MikuVolumeManager.setUseHibyDialog(ctx, it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF00E5FF),
                    checkedTrackColor = Color(0x5500E5FF),
                    uncheckedThumbColor = Color(0xFF7FB9C6),
                    uncheckedTrackColor = Color(0x33FFFFFF)
                )
            )
        }
    }
}

@Composable
private fun VolumeStyleOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Color(0x3300E5FF) else Color(0x14FFFFFF),
                RoundedCornerShape(10.dp)
            )
            .border(
                if (isSelected) 1.2.dp else 0.8.dp,
                if (isSelected) MikuCyan else Color(0x28FFFFFF),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MikuCyan else Color(0xFF7FB9C6),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isSelected) Color.White else Color(0xFFD0E0E3),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = Color(0xFF8BA6AC),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

private fun Modifier.clipToRoundedGlass(): Modifier = this
    .background(
        Brush.horizontalGradient(listOf(Color(0x2200E5FF), Color(0x11000000))),
        RoundedCornerShape(14.dp)
    )
    .border(1.2.dp, Color(0x3300E5FF), RoundedCornerShape(14.dp))
