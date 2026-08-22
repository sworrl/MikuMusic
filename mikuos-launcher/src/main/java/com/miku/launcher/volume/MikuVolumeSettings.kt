package com.miku.launcher.volume

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont

/**
 * Standalone settings row for the volume-UI choice, kept out of the launcher monolith so it can be
 * dropped into any Settings screen with a single call. Toggles between MikuOS's color-changing
 * volume modal (default) and HiBy's stock fullscreen volume dialog. Writing the toggle calls
 * [MikuVolumeManager.setUseHibyDialog], which both persists the choice and enforces it at the OS
 * level (Settings.Global hiby_volume_dialog_enable / _indicator).
 *
 * Usage from a settings Composable:  MikuVolumeUiSettingRow()
 */
@Composable
fun MikuVolumeUiSettingRow(
    modifier: Modifier = Modifier,
    ctx: Context = LocalContext.current
) {
    var useHiby by remember { mutableStateOf(MikuVolumeManager.useHibyDialog(ctx)) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToRoundedGlass()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "HiBy fullscreen volume",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (useHiby)
                    "On — HiBy's stock overlay handles volume."
                else
                    "Off — MikuOS color volume modal (default).",
                color = Color(0xFF7FB9C6),
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.width(10.dp))
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

/** Shared liquid-glass container styling for the row, matching MikuOS settings chrome. */
private fun Modifier.clipToRoundedGlass(): Modifier = this
    .background(
        Brush.horizontalGradient(listOf(Color(0x2200E5FF), Color(0x11000000))),
        RoundedCornerShape(12.dp)
    )
    .border(1.dp, Color(0x3300E5FF), RoundedCornerShape(12.dp))
