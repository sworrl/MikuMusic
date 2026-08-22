package com.miku.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.AudiowideFont
import com.miku.player.HapticIconButton
import com.miku.player.MikuTealBright

/**
 * System-wide Standard Hatsune Miku Branded Back Button.
 * Replicates the exact size, placement, and springy haptic tactile feel across every screen.
 */
@Composable
fun MikuBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MikuTealBright
) {
    HapticIconButton(
        onClick = onClick,
        flat = true,
        modifier = modifier.size(38.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * System-wide Standard Hatsune Miku Top Bar Header.
 * Combines the branded back button, Audiowide title, and optional actions.
 */
@Composable
fun MikuTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MikuBackButton(onClick = onBack)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}
