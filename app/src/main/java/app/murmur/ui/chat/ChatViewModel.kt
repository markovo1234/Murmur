package app.murmur.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.data.PeerRepository
import app.murmur.data.db.MessageEntity
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.ui.components.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

data class MessageUi(
    val id: String,
    val body: String,
    val outgoing: Boolean,
    val senderId: String,
    val senderName: String,
    val senderEmoji: String,
    val senderColor: Int,
    val time: Long,
    /** Outgoing DMs only. */
    val status: DeliveryStatus?,
    val hops: Int,
    val groupedWithOlder: Boolean,
    val groupedWithNewer: Boolean,
    /** #nearby: the first message of a group shows name + avatar. */
    val showHeader: Boolean,
)

sealed interface ChatItem {
    val key: String

    data class Bubble(val message: MessageUi) : ChatItem {
        override val key: String get() = message.id
    }

    data class Day(val label: String, override val key: String) : ChatItem
}

data class ChatUiState(
    val conversationId: String = "",
    val isNearby: Boolean = false,
    val peer: Peer? = null,
    val title: String = "",
    val subtitle: String = "",
    /** Newest first (the list is reverse-laid-out). */
    val items: List<ChatItem> = emptyList(),
    val typing: Boolean = false,
    val blocked: Boolean = false,
    val loaded: Boolean = false,
)

class ChatViewModel(private val c: AppContainer, private val conversationId: String) : ViewModel() {
    private val isNearby = conversationId == NEARBY_CONVERSATION
    private val peerId: PeerId? = if (isNearby) null else PeerId.fromHex(conversationId)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off messages for a snackbar. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(30_000)
        }
    }

    val state: StateFlow<ChatUiState> = combine(
        c.chats.messages(conversationId),
        c.peers.peers,
        c.chats.typing,
        ticker,
        c.settingsState.map { it?.profile },
    ) { messages, peers, typing, now, me ->
        val byId = peers.associateBy { it.id.toHex() }
        val peer = peerId?.let { byId[it.toHex()] }
        val online = peers.count { it.isOnline && !it.blocked }
        ChatUiState(
            conversationId = conversationId,
            isNearby = isNearby,
            peer = peer,
            title = when {
                isNearby -> "#nearby"
                peer != null -> Format.displayName(peer, peers)
                else -> peerId?.let(PeerRepository::fallbackName) ?: "Chat"
            },
            subtitle = if (isNearby) "$online ${if (online == 1) "person" else "people"} in range" else Format.peerStatus(peer),
            items = buildItems(messages, byId, me?.emoji, me?.colorIndex, now),
            typing = peerId != null && peerId in typing,
            blocked = peer?.blocked == true,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(conversationId = conversationId, isNearby = isNearby))

    private fun buildItems(
        messages: List<MessageEntity>,
        peers: Map<String, Peer>,
        myEmoji: String?,
        myColor: Int?,
        now: Long,
    ): List<ChatItem> {
        fun sameGroup(a: MessageEntity?, b: MessageEntity?): Boolean = a != null && b != null &&
            a.outgoing == b.outgoing && a.senderId == b.senderId &&
            abs(a.sortKey - b.sortKey) <= GROUP_WINDOW_MILLIS && Format.sameDay(a.sortKey, b.sortKey)

        val out = ArrayList<ChatItem>(messages.size + 8)
        for (i in messages.indices) {
            val m = messages[i]
            val newer = messages.getOrNull(i - 1)
            val older = messages.getOrNull(i + 1)
            val sender = peers[m.senderId]
            val withOlder = sameGroup(m, older)
            out += ChatItem.Bubble(
                MessageUi(
                    id = m.id,
                    body = m.body,
                    outgoing = m.outgoing,
                    senderId = m.senderId,
                    senderName = if (m.outgoing) "You" else sender?.nickname ?: m.senderNickname,
                    senderEmoji = if (m.outgoing) myEmoji ?: PeerRepository.DEFAULT_EMOJI else sender?.emoji ?: PeerRepository.DEFAULT_EMOJI,
                    senderColor = if (m.outgoing) myColor ?: 0 else sender?.colorIndex ?: 0,
                    time = m.sortKey,
                    status = if (m.outgoing && !isNearby) DeliveryStatus.entries.getOrNull(m.status) else null,
                    hops = m.hops,
                    groupedWithOlder = withOlder,
                    groupedWithNewer = sameGroup(m, newer),
                    showHeader = isNearby && !m.outgoing && !withOlder,
                ),
            )
            if (older == null || !Format.sameDay(m.sortKey, older.sortKey)) {
                out += ChatItem.Day(Format.day(now, m.sortKey), "day-${Format.dayKey(m.sortKey)}")
            }
        }
        return out
    }

    fun send(text: String) {
        viewModelScope.launch {
            val ok = if (isNearby) c.chats.sendNearby(text) else peerId?.let { c.chats.sendDirect(it, text) } ?: false
            if (!ok) _events.tryEmit(if (isNearby) "The mesh is off, so #nearby can't be sent." else "Couldn't queue the message.")
        }
    }

    fun onTyping() {
        val id = peerId ?: return
        viewModelScope.launch { c.chats.userTyping(id) }
    }

    fun setVisible(visible: Boolean) {
        c.chats.setOpenConversation(if (visible) conversationId else null)
        if (visible) markRead()
    }

    fun markRead() {
        viewModelScope.launch { c.chats.markConversationRead(conversationId) }
    }

    fun retry(id: String) {
        viewModelScope.launch { c.chats.retry(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { c.chats.deleteMessage(id) }
    }

    fun clear() {
        viewModelScope.launch { c.chats.clearConversation(conversationId) }
    }

    fun toggleBlock() {
        val id = peerId ?: return
        val blocked = state.value.blocked
        viewModelScope.launch { c.peers.setBlocked(id, !blocked) }
    }

    fun blockSender(senderIdHex: String) {
        val id = PeerId.fromHex(senderIdHex) ?: return
        viewModelScope.launch {
            c.peers.setBlocked(id, true)
            _events.tryEmit("Blocked. Their messages are hidden, but still relayed.")
        }
    }

    override fun onCleared() {
        if (c.chats.currentConversation.value == conversationId) c.chats.setOpenConversation(null)
    }

    private companion object {
        const val GROUP_WINDOW_MILLIS = 3 * 60_000L
    }
}
