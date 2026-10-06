package app.murmur.ui.chats

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import app.murmur.core.mesh.PeerStatus
import app.murmur.ui.components.HopBadge
import app.murmur.ui.components.SectionLabel
import app.murmur.ui.theme.MurmurType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.murmur.data.NearbyChannel
import app.murmur.data.ChatRepository
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.protocol.Channels
import app.murmur.core.protocol.PeerId
import app.murmur.data.JoinResult
import app.murmur.data.Peer
import app.murmur.data.PeerRepository
import app.murmur.data.ThemeMode
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.data.db.channelConversation
import app.murmur.data.db.channelOf
import app.murmur.ui.chat.ChatKind
import app.murmur.ui.chat.RoomIcon
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.PulsingDot
import app.murmur.ui.components.pressBounce
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
    val kind: ChatKind,
    val peer: Peer?,
    val title: String,
    val preview: String,
    val time: Long,
    val unread: Int,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val draft: String = "",
)

data class ChatsUiState(
    val nearbyOnline: Int = 0,
    /** Online, unblocked people, strongest first (the avatar stack on the #nearby card). */
    val nearbyPeers: List<Peer> = emptyList(),
    val nearbyPreview: String = "",
    val nearbyUnread: Int = 0,
    val nearbyTime: Long = 0L,
    val nearbyMuted: Boolean = false,
    val channels: List<ConversationRow> = emptyList(),
    val rows: List<ConversationRow> = emptyList(),
    val now: Long = 0L,
    /** Channels in use nearby that I'm not in (with that lock state). */
    val nearbyChannels: List<NearbyChannel> = emptyList(),
) {
    val anyUnread: Boolean get() = nearbyUnread > 0 || channels.any { it.unread > 0 } || rows.any { it.unread > 0 }
}

class ChatsViewModel(private val c: AppContainer) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(30_000)
        }
    }

    val state: StateFlow<ChatsUiState> = combine(
        c.chats.conversations,
        c.chats.channels,
        c.peers.peers,
        ticker,
        c.chats.nearbyChannels,
    ) { conversations, channels, peers, now, nearbyChannels ->
        val nearby = conversations.firstOrNull { it.id == NEARBY_CONVERSATION }
        val byId = peers.associateBy { it.id.toHex() }
        val convById = conversations.associateBy { it.id }
        val channelRows = channels.map { ch ->
            val id = channelConversation(ch.name)
            val conv = convById[id]
            ConversationRow(
                id = id,
                kind = ChatKind.CHANNEL,
                peer = null,
                title = "#${ch.name}",
                preview = conv?.preview.orEmpty(),
                time = conv?.lastActivity ?: ch.joinedAt,
                unread = conv?.unread ?: 0,
                pinned = conv?.pinned == true,
                muted = conv?.muted == true,
                locked = ch.keyHex != null,
                draft = conv?.draft.orEmpty(),
            )
        }.sortedWith(compareByDescending<ConversationRow> { it.pinned }.thenByDescending { it.time })
        ChatsUiState(
            nearbyOnline = peers.count { it.isOnline && !it.blocked },
            nearbyPeers = peers.filter { it.isOnline && !it.blocked },
            nearbyPreview = nearby?.preview.orEmpty(),
            nearbyUnread = nearby?.unread ?: 0,
            nearbyTime = nearby?.lastActivity ?: 0L,
            nearbyMuted = nearby?.muted == true,
            channels = channelRows,
            rows = conversations.filter { it.id != NEARBY_CONVERSATION && channelOf(it.id) == null }.map { conv ->
                val peer = byId[conv.id]
                ConversationRow(
                    id = conv.id,
                    kind = ChatKind.DIRECT,
                    peer = peer,
                    title = peer?.let { Format.displayName(it, peers) } ?: PeerId.fromHex(conv.id)?.let(PeerRepository::fallbackName) ?: "Unknown",
                    preview = conv.preview,
                    time = conv.lastActivity,
                    unread = conv.unread,
                    pinned = conv.pinned,
                    muted = conv.muted,
                    draft = conv.draft,
                )
            },
            now = now,
            nearbyChannels = nearbyChannels.filter { nc ->
                now - nc.lastSeen < ChatRepository.NEARBY_CHANNEL_MILLIS &&
                    channels.none { it.name == nc.name && (it.keyHex != null) == nc.locked }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    fun delete(id: String) {
        viewModelScope.launch {
            val channel = channelOf(id)
            if (channel != null) c.chats.leaveChannel(channel) else c.chats.deleteConversation(id)
        }
    }

    fun clear(id: String) {
        viewModelScope.launch { c.chats.clearConversation(id) }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { c.chats.setPinned(id, pinned) }
    }

    fun setMuted(id: String, muted: Boolean) {
        viewModelScope.launch { c.chats.setMuted(id, muted) }
    }

    fun markRead(id: String) {
        viewModelScope.launch { c.chats.markConversationRead(id) }
    }

    fun markAllRead() {
        viewModelScope.launch { c.chats.markAllRead() }
    }

    suspend fun join(name: String, password: String?): JoinResult = c.chats.joinChannel(name, password)
}

/** What a long-press on a chat row can do. */
class ChatsActions(
    val onOpen: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onClear: (String) -> Unit = {},
    val onPin: (String, Boolean) -> Unit = { _, _ -> },
    val onMute: (String, Boolean) -> Unit = { _, _ -> },
    val onMarkRead: (String) -> Unit = {},
    val onMarkAllRead: () -> Unit = {},
    val onJoin: suspend (String, String?) -> JoinResult = { _, _ -> JoinResult.Invalid },
)

@Composable
fun ChatsRoute(contentPadding: PaddingValues, onOpen: (String) -> Unit) {
    val vm = containerViewModel { ChatsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    ChatsScreen(
        state,
        contentPadding,
        ChatsActions(
            onOpen = onOpen,
            onDelete = vm::delete,
            onClear = vm::clear,
            onPin = vm::setPinned,
            onMute = vm::setMuted,
            onMarkRead = vm::markRead,
            onMarkAllRead = vm::markAllRead,
            onJoin = vm::join,
        ),
    )
}

@Composable
fun ChatsScreen(state: ChatsUiState, contentPadding: PaddingValues, actions: ChatsActions) {
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var joinOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "toolbar") {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chats", style = MurmurType.ScreenTitle, modifier = Modifier.weight(1f).semantics { heading() })
                if (state.anyUnread) {
                    Row(
                        Modifier
                            .heightIn(min = Dimens.MinTouch)
                            .wrapContentHeight()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(onClickLabel = "Mark all chats read", onClick = actions.onMarkAllRead)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Read all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "nearby") {
            NearbyCard(state, onClick = { actions.onOpen(NEARBY_CONVERSATION) }, onLongClick = { menuFor = NEARBY_CONVERSATION })
        }
        item(key = "channels-header") { SectionLabel("Channels", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp)) }
        item(key = "channels") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in state.channels) {
                    ChannelChip(row, onClick = { actions.onOpen(row.id) }, onLongClick = { menuFor = row.id })
                }
                JoinChip(onClick = { joinOpen = true })
            }
        }
        item(key = "dm-header") { SectionLabel("Direct · end-to-end encrypted", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp)) }
        if (state.rows.isEmpty()) {
            item(key = "hint") {
                Text(
                    "No direct messages yet. Tap someone on Around to start one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(state.rows, key = { it.id }) { row ->
            ConversationItem(row, state.now, onClick = { actions.onOpen(row.id) }, onLongClick = { menuFor = row.id }, modifier = Modifier.animateItem())
        }
    }

    menuFor?.let { id ->
        val row = (state.channels + state.rows).firstOrNull { it.id == id }
        val isNearby = id == NEARBY_CONVERSATION
        if (row != null || isNearby) ChatOptionsSheet(
            title = row?.title ?: "#nearby",
            kind = row?.kind ?: ChatKind.NEARBY,
            pinned = row?.pinned == true,
            muted = if (isNearby) state.nearbyMuted else row?.muted == true,
            unread = if (isNearby) state.nearbyUnread > 0 else (row?.unread ?: 0) > 0,
            onDismiss = { menuFor = null },
            onPin = { actions.onPin(id, !(row?.pinned ?: false)) },
            onMute = { actions.onMute(id, !(if (isNearby) state.nearbyMuted else row?.muted == true)) },
            onMarkRead = { actions.onMarkRead(id) },
            onDelete = { confirmDelete = id },
        )
    }

    confirmDelete?.let { pending ->
        val row = (state.channels + state.rows).firstOrNull { it.id == pending }
        val (title, text, confirm) = when {
            pending == NEARBY_CONVERSATION -> Triple("Clear #nearby?", "All #nearby messages will be deleted from this phone.", "Clear")
            row?.kind == ChatKind.CHANNEL -> Triple("Leave ${row.title}?", "You'll stop receiving its messages and its history is deleted from this phone.", "Leave")
            else -> Triple("Delete chat?", "Your messages with ${row?.title ?: "this person"} will be deleted from this phone.", "Delete")
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    if (pending == NEARBY_CONVERSATION) actions.onClear(pending) else actions.onDelete(pending)
                    confirmDelete = null
                }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (joinOpen) {
        JoinChannelDialog(
            nearby = state.nearbyChannels,
            onJoin = actions.onJoin,
            onJoined = { name ->
                joinOpen = false
                actions.onOpen(channelConversation(name))
            },
            onDismiss = { joinOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NearbyCard(state: ChatsUiState, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .pressBounce(0.985f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
            .combinedClickable(
                onClickLabel = "Open #nearby",
                onLongClickLabel = "Chat options",
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "#nearby, ${state.nearbyOnline} in range, ${state.nearbyUnread} unread${if (state.nearbyMuted) ", muted" else ""}"
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoomIcon(ChatKind.NEARBY, locked = false, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#nearby", style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    if (state.nearbyMuted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(MurmurIcons.Muted, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
                    }
                }
                Text("everyone in range · vanishes after 24 h", style = MurmurType.Mono, fontSize = 10.5.sp, color = ink)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.nearbyTime > 0) {
                    Text(Format.relative(state.now, state.nearbyTime), style = MurmurType.Mono, fontSize = 10.5.sp, color = ink)
                }
                if (state.nearbyUnread > 0) UnreadBadge(state.nearbyUnread)
            }
        }
        Text(
            state.nearbyPreview.ifEmpty { "Say hi to everyone in range 👋" },
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stack = state.nearbyPeers.take(MAX_STACK)
            if (stack.isEmpty()) {
                PulsingDot(MaterialTheme.colorScheme.outline, size = 6.dp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    for (p in stack) StackAvatar(p, ringColor = MaterialTheme.colorScheme.primaryContainer)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (state.nearbyOnline == 0) "nobody in range yet" else "${state.nearbyOnline} in range",
                style = MaterialTheme.typography.labelMedium,
                color = ink,
            )
        }
    }
}

/** 24 dp avatar for the overlapping stack, ringed in the card color so neighbours separate. */
@Composable
private fun StackAvatar(peer: Peer, ringColor: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(ringColor).padding(2.dp), contentAlignment = Alignment.Center) {
        EmojiAvatar(peer.emoji, peer.colorIndex, 20.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelChip(row: ConversationRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val a11y = buildString {
        append(row.title)
        if (row.locked) append(", password protected")
        if (row.pinned) append(", pinned")
        if (row.muted) append(", muted")
        if (row.unread > 0) append(", ${row.unread} unread")
    }
    Row(
        Modifier
            .heightIn(min = Dimens.MinTouch)
            .wrapContentHeight()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(
                onClickLabel = "Open channel",
                onLongClickLabel = "Chat options",
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = MaterialTheme.colorScheme.onSecondaryContainer
        if (row.locked) Icon(Icons.Filled.Lock, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Text(row.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink)
        if (row.muted) Icon(MurmurIcons.Muted, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        if (row.unread > 0) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) {
                Text(
                    if (row.unread > 99) "99+" else "${row.unread}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Dashed "+ Join" chip at the end of the channel row. */
@Composable
private fun JoinChip(onClick: () -> Unit) {
    val dash = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .heightIn(min = Dimens.MinTouch)
            .wrapContentHeight()
            .clip(CircleShape)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(onClickLabel = "Join or create a channel", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Join", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(row: ConversationRow, now: Long, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val a11y = buildString {
        append(row.title)
        if (row.pinned) append(", pinned")
        if (row.muted) append(", muted")
        if (row.unread > 0) append(", ${row.unread} unread")
        if (row.draft.isNotBlank()) append(", draft: ${row.draft}") else if (row.preview.isNotEmpty()) append(", ${row.preview}")
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .pressBounce(0.985f)
            .combinedClickable(
                onClickLabel = "Open chat",
                onLongClickLabel = "Chat options",
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.kind == ChatKind.DIRECT) {
            EmojiAvatar(
                emoji = row.peer?.emoji ?: PeerRepository.DEFAULT_EMOJI,
                colorIndex = row.peer?.colorIndex ?: 0,
                size = 48.dp,
                modifier = Modifier.sharedAvatar(row.id),
                online = row.peer?.isOnline == true,
                verified = row.peer?.verified == true,
            )
        } else {
            RoomIcon(row.kind, row.locked, 48.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.peer?.favorite == true) Text(" ★", color = MurmurTheme.colors.hop, style = MaterialTheme.typography.titleSmall)
                if (row.peer?.status == PeerStatus.VIA_MESH) {
                    Spacer(Modifier.width(6.dp))
                    HopBadge(row.peer.hops)
                }
                if (row.locked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.draft.isNotBlank()) {
                Text(
                    "Draft: ${row.draft}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    row.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Format.relative(now, row.time),
                style = MurmurType.Mono,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                if (row.muted) Icon(MurmurIcons.Muted, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
                if (row.pinned) Icon(MurmurIcons.Pin, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
                if (row.unread > 0) UnreadBadge(row.unread)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatOptionsSheet(
    title: String,
    kind: ChatKind,
    pinned: Boolean,
    muted: Boolean,
    unread: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            fun run(action: () -> Unit): () -> Unit = {
                action()
                onDismiss()
            }
            if (kind != ChatKind.NEARBY) OptionItem(if (pinned) "Unpin" else "Pin to top", MurmurIcons.Pin, onClick = run(onPin))
            OptionItem(if (muted) "Unmute notifications" else "Mute notifications", MurmurIcons.Muted, onClick = run(onMute))
            if (unread) OptionItem("Mark as read", Icons.Filled.Check, onClick = run(onMarkRead))
            val (label, icon) = when (kind) {
                ChatKind.NEARBY -> "Clear #nearby" to Icons.Filled.Delete
                ChatKind.CHANNEL -> "Leave channel" to Icons.Filled.Delete
                ChatKind.DIRECT -> "Delete chat" to Icons.Filled.Delete
            }
            OptionItem(label, icon, danger = true, onClick = run(onDelete))
        }
    }
}

@Composable
private fun OptionItem(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun JoinChannelDialog(
    nearby: List<NearbyChannel>,
    onJoin: suspend (String, String?) -> JoinResult,
    onJoined: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var protect by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalized = Channels.normalize(name)
    val shownName = normalized?.let { "#$it" }
    val canJoin = normalized != null && (!protect || password.length >= MIN_PASSWORD) && !busy
    // Someone nearby uses this name with a password (and nobody without): switch the password on.
    val lockedNearby = normalized != null && nearby.any { it.name == normalized && it.locked }
    val openNearby = normalized != null && nearby.any { it.name == normalized && !it.locked }
    LaunchedEffect(normalized) { if (lockedNearby && !openNearby) protect = true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join or create a channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Channels are topic rooms that reach people nearby and hop through the mesh. Anyone who types the same name " +
                        "(and the same password, if it has one) joins the same channel. Easiest: ask someone inside to invite you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (nearby.isNotEmpty()) {
                    Text("Active nearby", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (nc in nearby) {
                            FilterChip(
                                selected = normalized == nc.name && protect == nc.locked,
                                onClick = {
                                    name = nc.name
                                    protect = nc.locked
                                },
                                label = { Text(if (nc.locked) "🔒 #${nc.name}" else "#${nc.name}") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Channels.MAX_LENGTH + 2) },
                    label = { Text("Channel name") },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = name.isNotBlank() && normalized == null,
                    supportingText = {
                        Text(
                            when {
                                name.isBlank() -> "Letters, numbers, - and _"
                                normalized == null -> "That name can't be used"
                                shownName != "#${name.trim().removePrefix("#")}" -> "Will join $shownName"
                                else -> "Letters, numbers, - and _"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Password", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Encrypts messages so only people who know it can read them",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = protect, onCheckedChange = { protect = it })
                }
                if (lockedNearby && !protect) {
                    Text(
                        "🔒 People nearby use a password for $shownName. Without it you won't see their messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (openNearby && protect && !lockedNearby) {
                    Text(
                        "People nearby use $shownName without a password. With one, you'll be in a separate channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (protect) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(64) },
                        label = { Text("Channel password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            Text(
                                if (lockedNearby) "Ask whoever made it for the password" else "At least $MIN_PASSWORD characters. Share it in person or invite people.",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canJoin, onClick = {
                busy = true
                scope.launch {
                    val result = onJoin(name, if (protect) password else null)
                    busy = false
                    if (result is JoinResult.Joined) onJoined(result.name)
                }
            }) { Text(if (busy) "Joining…" else "Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_PASSWORD = 4
private const val MAX_STACK = 5

private val previewState = ChatsUiState(
    nearbyOnline = 4,
    nearbyPeers = PreviewData.peers.filter { it.isOnline },
    nearbyPreview = "Kai: Found a great bench by the river",
    nearbyUnread = 3,
    nearbyTime = PreviewData.NOW - 120_000,
    channels = listOf(
        ConversationRow("ch:hiking", ChatKind.CHANNEL, null, "#hiking", "Luna: Trail at 9?", PreviewData.NOW - 300_000, 1, pinned = true, locked = true),
        ConversationRow("ch:festival", ChatKind.CHANNEL, null, "#festival", "Kai: stage 2 is packed", PreviewData.NOW - 900_000, 0, muted = true),
    ),
    rows = listOf(
        ConversationRow(PreviewData.luna.id.toHex(), ChatKind.DIRECT, PreviewData.luna, "Luna", "Want to meet by the fountain?", PreviewData.NOW - 60_000, 2, pinned = true),
        ConversationRow(PreviewData.theo.id.toHex(), ChatKind.DIRECT, PreviewData.theo, "Theo", "You: see you on the other floor", PreviewData.NOW - 3_600_000, 0, draft = "running late"),
        ConversationRow(PreviewData.ines.id.toHex(), ChatKind.DIRECT, PreviewData.ines, "Inès", "Speak soon, going out of range", PreviewData.NOW - 90_000_000, 0, muted = true),
    ),
    now = PreviewData.NOW,
)

@Preview(name = "Chats · light", showBackground = true)
@Composable
private fun ChatsLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { ChatsScreen(previewState, PaddingValues(0.dp), ChatsActions()) }
}

@Preview(name = "Chats · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatsDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { ChatsScreen(previewState, PaddingValues(0.dp), ChatsActions()) }
}

@Preview(name = "Chats · empty · light", showBackground = true)
@Composable
private fun ChatsEmptyPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { ChatsScreen(ChatsUiState(nearbyOnline = 0, now = PreviewData.NOW), PaddingValues(0.dp), ChatsActions()) }
}
