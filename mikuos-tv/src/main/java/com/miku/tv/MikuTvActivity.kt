package com.miku.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The TV side of the cast.
 *
 * Layout is for a ten-foot view: large type, generous padding inside the overscan margin, and
 * every control reachable with a D-pad because a TV remote has no pointer.
 *
 * The format line is the point of this screen as much as the transport is. It shows what the
 * M500 sent AND what this TV's AudioTrack actually granted, side by side, because the two differ
 * on plenty of sets: many resample everything to 48kHz no matter what an app asks for, and some
 * clamp optical output below what the panel claims. Printing the source rate alone would be a
 * confident lie about the signal reaching the THX system.
 */
class MikuTvActivity : ComponentActivity() {

    private lateinit var client: MikuCastClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cast session is a watch session: nothing here should time out the screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        client = MikuCastClient(applicationContext)
        client.start()

        setContent { CastScreen(client) }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.stop()
    }
}

private val Bg = Color(0xFF04100F)
private val Cyan = Color(0xFF39C5BB)
private val Pink = Color(0xFFFF5FA2)
private val Muted = Color(0xFF8FA3A8)
private val Warn = Color(0xFFFFB020)

@Composable
private fun CastScreen(client: MikuCastClient) {
    val s by client.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            // TV overscan: a 5% inset keeps everything on screen on sets that still crop.
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Column(Modifier.fillMaxSize()) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (s.connected) Cyan else Muted)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (s.connected) "MIKU CAST  ·  ${s.host}" else "MIKU CAST",
                    color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                s.title.ifBlank { if (s.connected) "Waiting for audio" else s.status },
                color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 2
            )
            if (s.artist.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(s.artist, color = Pink, fontSize = 26.sp, maxLines = 1)
            }
            if (s.album.isNotBlank()) {
                Text(s.album, color = Muted, fontSize = 18.sp, maxLines = 1)
            }

            Spacer(Modifier.height(32.dp))

            // ---- the honest format block ----
            val src = s.source
            if (src.sampleRate > 0) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x14FFFFFF))
                        .padding(18.dp)
                ) {
                    Text("FROM THE M500", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${src.sampleRate} Hz  ·  ${src.bytesPerSample * 8}-bit  ·  ${src.channelCount} ch",
                        color = Color.White, fontSize = 22.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("THIS TV ACTUALLY ACCEPTED", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (s.grantedRate > 0) "${s.grantedRate} Hz  ·  ${s.grantedEncodingLabel}"
                        else "nothing yet",
                        color = if (s.passthroughExact) Cyan else Warn, fontSize = 22.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (s.passthroughExact)
                            "Bit-perfect to the TV. What leaves the optical port is the set's business."
                        else
                            "The TV changed the format, so this is no longer the file's own samples.",
                        color = if (s.passthroughExact) Muted else Warn, fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(s.status, color = Muted, fontSize = 15.sp)
            if (s.skips > 0) {
                Text(
                    "${s.skips} resync${if (s.skips == 1L) "" else "s"} after network stalls",
                    color = Warn, fontSize = 14.sp
                )
            }
            if (s.lastError.isNotBlank()) {
                Text(s.lastError, color = Warn, fontSize = 13.sp, maxLines = 2)
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Previous") { client.send("prev") }
                TvButton("Play / Pause") { client.send("playpause") }
                TvButton("Next") { client.send("next") }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "projectM on this screen is not wired up yet. See docs/12_tv_companion_design.md.",
                color = Muted, fontSize = 12.sp
            )
        }
    }
}

/** Large hit area and a visible focus ring, because this is driven by a D-pad and not a finger. */
@Composable
private fun TvButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0x2239C5BB), contentColor = Color.White),
        modifier = Modifier.height(56.dp)
    ) {
        Text(label, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
