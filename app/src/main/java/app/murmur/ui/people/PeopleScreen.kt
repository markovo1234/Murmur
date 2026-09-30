package app.murmur.ui.people

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.pressBounce
import app.murmur.ui.components.PreviewData
import app.murmur.ui.containerViewModel
import app.murmur.ui.peer.PeerSheet
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PeopleUiState(val peers: List<Peer> = emptyList(), val now: Long = 0L)

class PeopleViewModel(private val c: AppContainer) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(30_000)
        }
    }

    val state: StateFlow<PeopleUiState> = combine(c.peers.peers, ticker) { peers, now ->
        PeopleUiState(peers.filter { !it.blocked }, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleUiState())

    fun toggleFavorite(peer: Peer) {
        viewModelScope.launch { c.peers.setFavorite(peer.id, !peer.favorite) }
    }
}

@Composable
fun PeopleRoute(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm = containerViewModel { PeopleViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var sheetPeer by rememberSaveable { mutableStateOf<String?>(null) }
    PeopleScreen(state, onBack, onOpen = { sheetPeer = it.id.toHex() }, onToggleFavorite = vm::toggleFavorite)
    sheetPeer?.let { hex ->
        PeerId.fromHex(hex)?.let { id ->
            PeerSheet(id, onDismiss = { sheetPeer = null }, onMessage = {
                sheetPeer = null
                onOpenChat(hex)
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(state: PeopleUiState, onBack: () -> Unit, onOpen: (Peer) -> Unit, onToggleFavorite: (Peer) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val visible = state.peers.filter {
        q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.nickname.contains(q, ignoreCase = true) || it.id.shortTag.startsWith(q.removePrefix("#"), ignoreCase = true)
    }
    val favorites = visible.filter { it.favorite }.sortedBy { it.name.lowercase() }
    val around = visible.filter { !it.favorite && it.isOnline }.sortedWith(compareBy<Peer> { it.status.ordinal }.thenBy { it.name.lowercase() })
    val before = visible.filter { !it.favorite && !it.isOnline }.sortedByDescending { it.lastSeen }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("People") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(40) },
                    placeholder = { Text("Search by name or #tag") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                )
            }
            if (state.peers.isEmpty()) {
                item(key = "empty") { Empty("Nobody yet. People appear here once your phones have been in range.") }
            } else if (visible.isEmpty()) {
                item(key = "none") { Empty("Nobody matches “$q”") }
            }
            section("Favorites", favorites, state.now, onOpen, onToggleFavorite)
            section("Around you", around, state.now, onOpen, onToggleFavorite)
            section("Met before", before, state.now, onOpen, onToggleFavorite)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    peers: List<Peer>,
    now: Long,
    onOpen: (Peer) -> Unit,
    onToggleFavorite: (Peer) -> Unit,
) {
    if (peers.isEmpty()) return
    item(key = "h-$title") {
        Text(
            "$title · ${peers.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp).semantics { heading() },
        )
    }
    items(peers, key = { "$title-${it.id.toHex()}" }) { peer ->
        PersonRow(peer, now, onClick = { onOpen(peer) }, onToggleFavorite = { onToggleFavorite(peer) }, modifier = Modifier.animateItem())
    }
}

@Composable
private fun PersonRow(peer: Peer, now: Long, onClick: () -> Unit, onToggleFavorite: () -> Unit, modifier: Modifier = Modifier) {
    val status = when (peer.status) {
        PeerStatus.OFFLINE -> "last seen ${Format.lastSeen(now, peer.lastSeen)}"
        else -> Format.peerStatus(peer)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressBounce()
            .clickable(onClickLabel = "Open profile", onClick = onClick)
            .padding(start = Dimens.ScreenPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiAvatar(peer.emoji, peer.colorIndex, 44.dp, online = peer.isOnline, verified = peer.verified)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (peer.alias != null) "${peer.nickname} · $status" else status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.semantics {
            contentDescription = if (peer.favorite) "Remove ${peer.name} from favorites" else "Add ${peer.name} to favorites"
        }) {
            Icon(
                if (peer.favorite) Icons.Filled.Star else MurmurIcons.StarOutline,
                contentDescription = null,
                tint = if (peer.favorite) MurmurTheme.colors.hop else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

private val previewState = PeopleUiState(
    peers = listOf(PreviewData.luna.copy(favorite = true), PreviewData.kai, PreviewData.mira.copy(alias = "Mira (climbing)"), PreviewData.theo, PreviewData.ines),
    now = PreviewData.NOW,
)

@Preview(name = "People · light", showBackground = true)
@Composable
private fun PeopleLightPreview() = MurmurTheme(ThemeMode.LIGHT) { PeopleScreen(previewState, {}, {}, {}) }

@Preview(name = "People · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PeopleDarkPreview() = MurmurTheme(ThemeMode.DARK) { PeopleScreen(previewState, {}, {}, {}) }
