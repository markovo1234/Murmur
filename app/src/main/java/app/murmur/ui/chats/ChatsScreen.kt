package app.murmur.ui.chats

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.data.PeerRepository
import app.murmur.data.ThemeMode
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.PulsingDot
import app.murmur.ui.components.UnreadBadge
import app.murmur.ui.components.sharedAvatar
import app.murmur.ui.containerViewModel
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationRow(
    val id: String,
    val peer: Peer?,
    val title: String,
    val preview: String,
    val time: Long,
    val unread: Int,
)

data class ChatsUiState(
    val nearbyOnline: Int = 0,
    val nearbyPreview: String = "",
    val nearbyUnread: Int = 0,
    val nearbyTime: Long = 0L,
    val rows: List<ConversationRow> = emptyList(),
    val now: Long = 0L,
)

class ChatsViewModel(private val c: AppContainer) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(30_000)
        }
    }

    val state: StateFlow<ChatsUiState> = combine(c.chats.conversations, c.peers.peers, ticker) { conversations, peers, now ->
        val nearby = conversations.firstOrNull { it.id == NEARBY_CONVERSATION }
        val byId = peers.associateBy { it.id.toHex() }
        ChatsUiState(
            nearbyOnline = peers.count { it.isOnline && !it.blocked },
            nearbyPreview = nearby?.preview.orEmpty(),
            nearbyUnread = nearby?.unread ?: 0,
            nearbyTime = nearby?.lastActivity ?: 0L,
            rows = conversations.filter { it.id != NEARBY_CONVERSATION }.map { conv ->
                val peer = byId[conv.id]
                ConversationRow(
                    id = conv.id,
                    peer = peer,
                    title = peer?.let { Format.displayName(it, peers) } ?: PeerId.fromHex(conv.id)?.let(PeerRepository::fallbackName) ?: "Unknown",
                    preview = conv.preview,
                    time = conv.lastActivity,
                    unread = conv.unread,
                )
            },
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    fun delete(id: String) {
        viewModelScope.launch { c.chats.deleteConversation(id) }
    }
}

@Composable
fun ChatsRoute(contentPadding: PaddingValues, onOpen: (String) -> Unit) {
    val vm = containerViewModel { ChatsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    ChatsScreen(state, contentPadding, onOpen, vm::delete)
}

@Composable
fun ChatsScreen(state: ChatsUiState, contentPadding: PaddingValues, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "nearby") {
            NearbyCard(state, onClick = { onOpen(NEARBY_CONVERSATION) })
        }
        if (state.rows.isEmpty()) {
            item(key = "hint") {
                Text(
                    "No direct messages yet. Tap someone on the Radar to start one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(state.rows, key = { it.id }) { row ->
            ConversationItem(
                row = row,
                now = state.now,
                onClick = { onOpen(row.id) },
                onLongClick = { confirmDelete = row.id },
                modifier = Modifier.animateItem(),
            )
        }
    }
    val pending = confirmDelete
    if (pending != null) {
        val title = state.rows.firstOrNull { it.id == pending }?.title ?: "this chat"
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("Your messages with $title will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pending)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NearbyCard(state: ChatsUiState, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp)
            .semantics { contentDescription = "#nearby, ${state.nearbyOnline} online, ${state.nearbyUnread} unread" },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Box(contentAlignment = Alignment.Center) { Icon(MurmurIcons.Hub, contentDescription = null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#nearby", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    PulsingDot(if (state.nearbyOnline > 0) MurmurTheme.colors.online else MaterialTheme.colorScheme.outline, size = 6.dp)
                    Text("${state.nearbyOnline} online", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    state.nearbyPreview.ifEmpty { "Everyone in range. Messages vanish after 24 h." },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (state.nearbyTime > 0) {
                    Text(Format.relative(state.now, state.nearbyTime), style = MaterialTheme.typography.labelSmall)
                }
                if (state.nearbyUnread > 0) UnreadBadge(state.nearbyUnread, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(row: ConversationRow, now: Long, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(
                onClickLabel = "Open chat",
                onLongClickLabel = "Delete chat",
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = Dimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiAvatar(
            emoji = row.peer?.emoji ?: PeerRepository.DEFAULT_EMOJI,
            colorIndex = row.peer?.colorIndex ?: 0,
            size = 48.dp,
            modifier = Modifier.sharedAvatar(row.id),
            online = row.peer?.isOnline == true,
            verified = row.peer?.verified == true,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Format.relative(now, row.time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            if (row.unread > 0) UnreadBadge(row.unread)
        }
    }
}

private val previewState = ChatsUiState(
    nearbyOnline = 4,
    nearbyPreview = "Kai: Found a great bench by the river",
    nearbyUnread = 3,
    nearbyTime = PreviewData.NOW - 120_000,
    rows = listOf(
        ConversationRow(PreviewData.luna.id.toHex(), PreviewData.luna, "Luna", "Want to meet by the fountain?", PreviewData.NOW - 60_000, 2),
        ConversationRow(PreviewData.theo.id.toHex(), PreviewData.theo, "Theo", "You: see you on the other floor", PreviewData.NOW - 3_600_000, 0),
        ConversationRow(PreviewData.ines.id.toHex(), PreviewData.ines, "Inès", "Speak soon, going out of range", PreviewData.NOW - 90_000_000, 0),
    ),
    now = PreviewData.NOW,
)

@Preview(name = "Chats · light", showBackground = true)
@Composable
private fun ChatsLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { ChatsScreen(previewState, PaddingValues(0.dp), {}, {}) }
}

@Preview(name = "Chats · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatsDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { ChatsScreen(previewState, PaddingValues(0.dp), {}, {}) }
}
