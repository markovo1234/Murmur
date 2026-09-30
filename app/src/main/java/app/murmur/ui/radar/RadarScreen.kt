package app.murmur.ui.radar

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.data.ThemeMode
import app.murmur.ui.components.AnimatedCount
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.HopBadge
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.SignalBars
import app.murmur.ui.components.pressBounce
import app.murmur.ui.components.shimmer
import app.murmur.ui.containerViewModel
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class RadarUiState(
    val myEmoji: String = "🙂",
    val myColor: Int = 0,
    /** Online, unblocked peers, strongest first. */
    val peers: List<Peer> = emptyList(),
    /** Everyone known (for #tag disambiguation). */
    val allPeers: List<Peer> = emptyList(),
    val now: Long = 0L,
)

class RadarViewModel(c: AppContainer) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(15_000)
        }
    }

    val state: StateFlow<RadarUiState> = combine(c.settingsState, c.peers.peers, ticker) { s, peers, now ->
        RadarUiState(
            myEmoji = s?.profile?.emoji ?: "🙂",
            myColor = s?.profile?.colorIndex ?: 0,
            peers = peers.filter { it.isOnline && !it.blocked },
            allPeers = peers,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadarUiState())
}

@Composable
fun RadarRoute(contentPadding: PaddingValues, onPeerClick: (PeerId) -> Unit, onMessage: (PeerId) -> Unit, onAllPeople: () -> Unit) {
    val vm = containerViewModel { RadarViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    RadarScreen(state, contentPadding, onPeerClick, onMessage, onAllPeople)
}

@Composable
fun RadarScreen(
    state: RadarUiState,
    contentPadding: PaddingValues,
    onPeerClick: (PeerId) -> Unit,
    onMessage: (PeerId) -> Unit,
    onAllPeople: () -> Unit = {},
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "radar") {
            RadarField(
                myEmoji = state.myEmoji,
                myColor = state.myColor,
                peers = state.peers,
                onPeerClick = onPeerClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
            )
        }
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nearby now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.peers.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    AnimatedCount(
                        state.peers.size,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.allPeers.any { !it.blocked }) {
                    TextButton(onClick = onAllPeople) {
                        Icon(MurmurIcons.People, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("All people")
                    }
                }
            }
        }
        if (state.peers.isEmpty()) {
            item(key = "empty") { EmptyRadar() }
        } else {
            items(state.peers, key = { it.id.raw }) { peer ->
                NearbyRow(
                    peer = peer,
                    name = Format.displayName(peer, state.allPeers),
                    now = state.now,
                    onClick = { onPeerClick(peer.id) },
                    onMessage = { onMessage(peer.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun EmptyRadar() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Looking for people nearby…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.shimmer(),
        )
        Text(
            "Ask a friend to open Murmur",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NearbyRow(peer: Peer, name: String, now: Long, onClick: () -> Unit, onMessage: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressBounce()
            .clickable(onClickLabel = "Open profile", onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiAvatar(peer.emoji, peer.colorIndex, 44.dp, online = peer.isOnline, verified = peer.verified)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (peer.favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Star, contentDescription = "favorite", tint = MurmurTheme.colors.hop, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "${Format.peerStatus(peer)} · ${Format.lastSeen(now, peer.lastSeen)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (peer.status == PeerStatus.NEARBY) SignalBars(peer.rssi) else HopBadge(peer.hops)
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = onMessage) {
            Icon(MurmurIcons.Chat, contentDescription = "Message ${peer.name}")
        }
    }
}

private val previewState = RadarUiState(
    myEmoji = "🐧",
    myColor = 1,
    peers = PreviewData.peers.filter { it.isOnline }.map { if (it == PreviewData.luna) it.copy(favorite = true) else it },
    allPeers = PreviewData.peers,
    now = PreviewData.NOW,
)

@Preview(name = "Radar · light", showBackground = true, heightDp = 900)
@Composable
private fun RadarLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { RadarScreen(previewState, PaddingValues(0.dp), {}, {}) }
}

@Preview(name = "Radar · dark", showBackground = true, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RadarDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { RadarScreen(previewState, PaddingValues(0.dp), {}, {}) }
}

@Preview(name = "Radar empty · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RadarEmptyPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { RadarScreen(RadarUiState(myEmoji = "🐧"), PaddingValues(0.dp), {}, {}) }
}
