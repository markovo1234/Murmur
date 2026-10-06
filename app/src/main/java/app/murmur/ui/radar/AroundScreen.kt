package app.murmur.ui.radar

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.data.ThemeMode
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.UnreadBadge
import app.murmur.ui.components.pressBounce
import app.murmur.ui.components.shimmer
import app.murmur.ui.containerViewModel
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import app.murmur.ui.theme.MurmurType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class AroundUiState(
    val myEmoji: String = "🙂",
    val myColor: Int = 0,
    /** Online, unblocked peers, strongest first. */
    val peers: List<Peer> = emptyList(),
    /** Unblocked people who left in the last day (shown faded under the online ones). */
    val recentlyGone: List<Peer> = emptyList(),
    /** Everyone known (for #tag disambiguation). */
    val allPeers: List<Peer> = emptyList(),
    val nearbyPreview: String = "",
    val nearbyUnread: Int = 0,
    val now: Long = 0L,
)

class AroundViewModel(c: AppContainer) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(15_000)
        }
    }

    val state: StateFlow<AroundUiState> = combine(c.settingsState, c.peers.peers, c.chats.conversations, ticker) { s, peers, conversations, now ->
        val nearby = conversations.firstOrNull { it.id == NEARBY_CONVERSATION }
        AroundUiState(
            myEmoji = s?.profile?.emoji ?: "🙂",
            myColor = s?.profile?.colorIndex ?: 0,
            peers = peers.filter { it.isOnline && !it.blocked },
            recentlyGone = peers.asSequence()
                .filter { !it.isOnline && !it.blocked && it.lastSeen > 0 && now - it.lastSeen < RECENT_MILLIS }
                .sortedByDescending { it.lastSeen }
                .take(MAX_RECENT)
                .toList(),
            allPeers = peers,
            nearbyPreview = nearby?.preview.orEmpty(),
            nearbyUnread = nearby?.unread ?: 0,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AroundUiState())

    private companion object {
        const val RECENT_MILLIS = 24 * 60 * 60 * 1000L
        const val MAX_RECENT = 5
    }
}

/** Everything the Around tab can do. */
class AroundActions(
    val onPeerClick: (PeerId) -> Unit = {},
    val onMessage: (PeerId) -> Unit = {},
    val onWave: (Peer) -> Unit = {},
    val onOpenNearby: () -> Unit = {},
    val onAllPeople: () -> Unit = {},
    val onSos: () -> Unit = {},
)

@Composable
fun AroundRoute(contentPadding: PaddingValues, actions: AroundActions) {
    val vm = containerViewModel { AroundViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    AroundScreen(state, contentPadding, actions)
}

/**
 * Home: the radar with names on it (hold your own avatar for SOS), then a panel with #nearby and everyone
 * around you, each with the way a message would reach them.
 */
@Composable
fun AroundScreen(state: AroundUiState, contentPadding: PaddingValues, actions: AroundActions) {
    var holding by remember { mutableStateOf(false) }
    var confirmSos by remember { mutableStateOf(false) }
    val panel = MaterialTheme.colorScheme.surface
    val edge = MaterialTheme.colorScheme.outlineVariant

    BoxWithConstraints(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        val radarSize = min(maxWidth - 40.dp, 352.dp)
        val panelMin = maxHeight - radarSize - 44.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                RadarField(
                    myEmoji = state.myEmoji,
                    myColor = state.myColor,
                    peers = state.peers,
                    onPeerClick = actions.onPeerClick,
                    modifier = Modifier.size(radarSize),
                    showNames = true,
                    selfSize = 64.dp,
                    onSelfHold = actions.onSos,
                    onSelfAccessibilityClick = { confirmSos = true },
                    onHoldingChange = { holding = it },
                )
            }
            Text(
                if (holding) "keep holding… SOS goes to everyone in range" else "hold ${state.myEmoji} for 2 s to send SOS",
                style = MurmurType.Mono,
                fontSize = 10.5.sp,
                color = if (holding) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // Clears the name label of someone sitting on the bottom edge of the radar.
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Spacer(Modifier.height(14.dp))

            // The panel: a sheet-like surface with a grab-handle look.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = panelMin.coerceAtLeast(0.dp))
                    .clip(RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius))
                    .background(panel)
                    .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(start = 12.dp, end = 12.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
            ) {
                Box(
                    Modifier
                        .padding(top = 8.dp, bottom = 4.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
                NearbyRoomCard(state, onClick = actions.onOpenNearby)
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Around you",
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (state.allPeers.any { !it.blocked }) {
                        Text(
                            "all people ›",
                            style = MurmurType.Mono,
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClickLabel = "Show all people", role = Role.Button, onClick = actions.onAllPeople)
                                .heightIn(min = Dimens.MinTouch)
                                .wrapContentHeight()
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
                if (state.peers.isEmpty() && state.recentlyGone.isEmpty()) {
                    EmptyAround()
                }
                for (peer in state.peers + state.recentlyGone) {
                    androidx.compose.runtime.key(peer.id.raw) {
                        PersonRow(peer, Format.displayName(peer, state.allPeers), state.now, actions)
                    }
                }
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            title = { Text("Send an SOS?") },
            text = { Text("Everyone in range gets a loud alert, even on silent. It's also pinned in #nearby.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSos = false
                    actions.onSos()
                }) { Text("Send SOS", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NearbyRoomCard(state: AroundUiState, onClick: () -> Unit) {
    val inRange = state.peers.size
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp)
            .pressBounce(0.985f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClickLabel = "Open #nearby", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "#nearby, $inRange in range" + if (state.nearbyUnread > 0) ", ${state.nearbyUnread} unread" else ""
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Box(contentAlignment = Alignment.Center) { Icon(MurmurIcons.Hub, contentDescription = null, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val ink = MaterialTheme.colorScheme.onPrimaryContainer
            Row(verticalAlignment = Alignment.Bottom) {
                Text("#nearby", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.width(8.dp))
                Text("$inRange in range", style = MurmurType.Mono, fontSize = 10.5.sp, color = ink, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(
                state.nearbyPreview.ifEmpty { "Everyone in range. Messages vanish after 24 h." },
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.nearbyUnread > 0) {
            Spacer(Modifier.width(8.dp))
            UnreadBadge(state.nearbyUnread)
        }
    }
}

@Composable
private fun EmptyAround() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
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
private fun PersonRow(peer: Peer, name: String, now: Long, actions: AroundActions) {
    val online = peer.isOnline
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (online) 1f else 0.55f)
            .heightIn(min = 60.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .pressBounce()
                .clickable(onClickLabel = "Open profile") { actions.onPeerClick(peer.id) }
                .semantics(mergeDescendants = true) {}
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmojiAvatar(peer.emoji, peer.colorIndex, 44.dp, online = online)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (peer.verified) {
                        Spacer(Modifier.width(5.dp))
                        Icon(MurmurIcons.Verified, contentDescription = "Verified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    }
                    if (peer.favorite) {
                        Text(" ★", color = MurmurTheme.colors.hop, style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text(
                    Format.route(peer, now),
                    style = MurmurType.Mono,
                    color = if (peer.status == PeerStatus.VIA_MESH) MurmurTheme.colors.hop else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (online) {
            Spacer(Modifier.width(4.dp))
            RoundAction(
                onClick = { actions.onWave(peer) },
                label = "Wave at ${peer.name}",
                background = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Text("👋", fontSize = 18.sp) }
            RoundAction(
                onClick = { actions.onMessage(peer.id) },
                label = "Message ${peer.name}",
                background = MaterialTheme.colorScheme.primary,
            ) { Icon(MurmurIcons.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** A 40 dp round button inside a 48 dp touch target. */
@Composable
private fun RoundAction(onClick: () -> Unit, label: String, background: Color, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(Dimens.MinTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) { content() }
    }
}

private val previewState = AroundUiState(
    myEmoji = "🐧",
    myColor = 1,
    peers = PreviewData.peers.filter { it.isOnline }.map { if (it == PreviewData.luna) it.copy(favorite = true) else it },
    recentlyGone = listOf(PreviewData.ines),
    allPeers = PreviewData.peers,
    nearbyPreview = "Theo: Anyone else at the station? @Sam",
    nearbyUnread = 3,
    now = PreviewData.NOW,
)

@Preview(name = "Around · light", showBackground = true, heightDp = 900)
@Composable
private fun AroundLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface(color = MaterialTheme.colorScheme.background) { AroundScreen(previewState, PaddingValues(0.dp), AroundActions()) }
}

@Preview(name = "Around · dark", showBackground = true, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AroundDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface(color = MaterialTheme.colorScheme.background) { AroundScreen(previewState, PaddingValues(0.dp), AroundActions()) }
}

@Preview(name = "Around empty · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AroundEmptyPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface(color = MaterialTheme.colorScheme.background) { AroundScreen(AroundUiState(myEmoji = "🐧"), PaddingValues(0.dp), AroundActions()) }
}
