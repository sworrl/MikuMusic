package com.miku.launcher.track

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink

/**
 * System-Level Real-Time Track Counter & Audio Library Badge for MikuOS Homescreen.
 * Displays live formatted track count (e.g. 1k, 11k, 24k) with cyber holographic glow.
 */
@Composable
fun MikuLibraryTrackBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val libraryState by MikuLibraryEngine.state.collectAsState()

    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF04181C))
            .border(1.dp, MikuCyan.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MikuCyan)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "♫ ${libraryState.abbreviatedTracks}",
                color = MikuCyan,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                letterSpacing = 0.3.sp
            )
        }
    }
}
