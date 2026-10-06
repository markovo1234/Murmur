package app.murmur.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.murmur.core.mesh.PeerStatus
import app.murmur.data.Peer
import app.murmur.data.PeerRepository
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.PreviewData
import app.murmur.ui.theme.MurmurTheme

/** How the line between two route nodes is drawn. */
private enum class Link { DIRECT, RELAYED, WAITING }

/**
 * The strip under the chat header that makes the mesh visible: for a DM, you → (relays) → them, plus one
 * sentence on how messages travel; for rooms, just who can read them.
 */
@Composable
fun RouteStrip(state: ChatUiState, modifier: Modifier = Modifier) {
    val peer = state.peer
    val text = routeText(state)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Route: $text" },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.kind == ChatKind.DIRECT) {
                Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                    Node(state.myEmoji, state.myColor)
                    when (peer?.status) {
                        PeerStatus.NEARBY -> {
                            Line(Link.DIRECT)
                            Node(peer.emoji, peer.colorIndex)
                        }
                        PeerStatus.VIA_MESH -> {
                            // One amber dot per phone in between (hops counts links, so relays = hops - 1).
                            val relays = (peer.hops - 1).coerceIn(1, MAX_RELAY_DOTS)
                            repeat(relays) {
                                Line(Link.RELAYED)
                                Box(Modifier.size(10.dp).clip(CircleShape).background(MurmurTheme.colors.hop))
                            }
                            Line(Link.RELAYED)
                            Node(peer.emoji, peer.colorIndex)
                        }
                        PeerStatus.OFFLINE, null -> {
                            Line(Link.WAITING)
                            Node(peer?.emoji ?: PeerRepository.DEFAULT_EMOJI, peer?.colorIndex ?: 0, Modifier.alpha(0.5f))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun routeText(state: ChatUiState): String {
    val peer = state.peer
    return when (state.kind) {
        ChatKind.NEARBY -> "Public room, not encrypted. Anyone in range can read it."
        ChatKind.CHANNEL -> if (state.channelLocked) "Encrypted with the channel password." else "Open channel, not encrypted. Anyone in range can read it."
        ChatKind.DIRECT -> when (peer?.status) {
            PeerStatus.NEARBY -> "Direct Bluetooth link · end-to-end encrypted"
            PeerStatus.VIA_MESH -> {
                val relays = (peer.hops - 1).coerceAtLeast(1)
                "Relayed by $relays other phone${if (relays == 1) "" else "s"}. Relays can't read it."
            }
            PeerStatus.OFFLINE, null ->
                "${peer?.name ?: state.title} is out of range. Messages wait on your phone and send when they're back."
        }
    }
}

@Composable
private fun Node(emoji: String, color: Int, modifier: Modifier = Modifier) {
    EmojiAvatar(emoji, color, 26.dp, modifier = modifier)
}

@Composable
private fun RowScope.Line(link: Link) {
    val color = when (link) {
        Link.DIRECT -> MaterialTheme.colorScheme.primary
        Link.RELAYED -> MurmurTheme.colors.hop.copy(alpha = 0.7f)
        Link.WAITING -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .weight(1f)
            .height(2.dp)
            .padding(horizontal = 6.dp)
            .drawBehind { drawLink(color, link) },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLink(color: Color, link: Link) {
    val y = size.height / 2
    val stroke = 2.dp.toPx()
    val effect = when (link) {
        Link.DIRECT -> null
        Link.RELAYED -> PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        Link.WAITING -> PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
    }
    drawLine(color, Offset(0f, y), Offset(size.width, y), stroke, cap = if (link == Link.WAITING) StrokeCap.Round else StrokeCap.Butt, pathEffect = effect)
}

private const val MAX_RELAY_DOTS = 4

private fun previewChat(peer: Peer) = ChatUiState(conversationId = peer.id.toHex(), kind = ChatKind.DIRECT, peer = peer, title = peer.name, myEmoji = "🐧", myColor = 1)

@Preview(name = "Route strip · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RouteStripDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteStrip(previewChat(PreviewData.luna))
            RouteStrip(previewChat(PreviewData.theo))
            RouteStrip(previewChat(PreviewData.ines))
            RouteStrip(ChatUiState(kind = ChatKind.NEARBY, title = "#nearby"))
        }
    }
}

@Preview(name = "Route strip · light", showBackground = true)
@Composable
private fun RouteStripLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteStrip(previewChat(PreviewData.theo))
            RouteStrip(ChatUiState(kind = ChatKind.CHANNEL, title = "#hiking", channelLocked = true))
        }
    }
}
