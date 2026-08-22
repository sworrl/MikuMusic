package com.miku.systemui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val MikuTeal = Color(0xFF39C5BB)
val MikuTealBright = Color(0xFF00F5D4)
val MikuPink = Color(0xFFFF2A85)
val MikuPinkBright = Color(0xFFFF4081)
val MikuPurple = Color(0xFFB388FF)
val MikuGold = Color(0xFFFFD54F)
val MikuDarkBg = Color(0xFF040D12)
val MikuSurface1 = Color(0xFF071922)
val MikuSurface2 = Color(0xFF0E242C)
val MikuCardBg = Color(0xFF06141B)
val MikuMuted = Color(0xFF8BA6A9)
val MikuWhite = Color(0xFFF0FDFB)
val CyberGlassBorder = Color(0x5500F5D4)

fun Modifier.mikuTile(isActive: Boolean) = this
    .clip(RoundedCornerShape(14.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (isActive) 0.22f else 0.10f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.60f)
            )
        )
    )
    .padding(0.8.dp)
    .clip(RoundedCornerShape(13.dp))
    .background(
        if (isActive)
            Brush.verticalGradient(listOf(Color(0xFF0E3842), Color(0xFF082228), Color(0xFF030E12)))
        else
            Brush.verticalGradient(listOf(Color(0xFF0B2129), Color(0xFF05131A), Color(0xFF02090D)))
    )
    .border(
        1.1.dp,
        if (isActive)
            Brush.linearGradient(listOf(MikuTealBright, MikuPurple, MikuTealBright))
        else
            Brush.verticalGradient(listOf(CyberGlassBorder.copy(alpha = 0.5f), Color.Transparent, CyberGlassBorder.copy(alpha = 0.25f))),
        RoundedCornerShape(13.dp)
    )

fun Modifier.mikuGlassCard() = this
    .clip(RoundedCornerShape(16.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.55f)
            )
        )
    )
    .padding(0.9.dp)
    .clip(RoundedCornerShape(15.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color(0xEE0D2630),
                Color(0xFF07171E),
                Color(0xFA030B0F)
            )
        )
    )
    .border(
        1.1.dp,
        Brush.linearGradient(
            listOf(
                MikuTealBright.copy(alpha = 0.85f),
                MikuPurple.copy(alpha = 0.45f),
                MikuPinkBright.copy(alpha = 0.65f)
            )
        ),
        RoundedCornerShape(15.dp)
    )
