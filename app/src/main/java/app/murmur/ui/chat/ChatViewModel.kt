package app.murmur.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.protocol.PeerId
import app.murmur.core.text.Replies
import app.murmur.data.Peer
import app.murmur.data.PeerRepository
import app.murmur.data.SosResult
import app.murmur.data.db.ChannelEntity
import app.murmur.data.db.ConversationEntity
import app.murmur.data.db.MessageEntity
import app.murmur.data.db.MessageKind
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.data.db.ReactionEntity
import app.murmur.data.db.channelOf
import app.murmur.ui.components.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class ChatKind { DIRECT, NEARBY, CHANNEL }

data class ReactionUi(val emoji: String, val count: Int, val mine: Boolean)

data class MessageUi(
    val id: String,
    /** Body without the reply quote line. */
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
    /** Rooms: the first message of a group shows name + avatar. */
    val showHeader: Boolean,
    val quoteAuthor: String? = null,
    val quote: String? = null,
    val reactions: List<ReactionUi> = emptyList(),
    val retracted: Boolean = false,
    val kind: Int = MessageKind.TEXT,
    val mentionsMe: Boolean = false,
    val sentAt: Long = time,
    val deliveredAt: Long = 0,
    val readAt: Long = 0,
    val expiresAt: Long = 0,
    /** The name everyone else knows the sender by (their own nickname, never my private alias). */
    val publicName: String = senderName,
) {
    val isText: Boolean get() = kind == MessageKind.TEXT
    val isSystem: Boolean get() = kind == MessageKind.SYSTEM
    val isSos: Boolean get() = kind == MessageKind.SOS

    /** Text used when quoting or copying (the reply part only). */
    val plain: String get() = body
}

sealed interface ChatItem {
    val key: String

    data class Bubble(val message: MessageUi) : ChatItem {
        override val key: String get() = message.id
    }

    data class Day(val label: String, override val key: String) : ChatItem
}

data class SearchHit(val id: String, val sender: String, val body: String, val time: Long)

data class ChatUiState(
    val conversationId: String = "",
    val kind: ChatKind = ChatKind.DIRECT,
    val peer: Peer? = null,
    val title: String = "",
    val subtitle: String = "",
    /** Newest first (the list is reverse-laid-out). */
    val items: List<ChatItem> = emptyList(),
    val typing: Boolean = false,
    val blocked: Boolean = false,
    val loaded: Boolean = false,
    val muted: Boolean = false,
    val disappearSeconds: Long = 0,
    val channelLocked: Boolean = false,
    val savedDraft: String = "",
    val myName: String = "",
    /** People to suggest after "@" in rooms. */
    val mentionNames: List<String> = emptyList(),
    val searchResults: List<SearchHit>? = null,
) {
    val isNearby: Boolean get() = kind == ChatKind.NEARBY
    val isRoom: Boolean get() = kind != ChatKind.DIRECT
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(private val c: AppContainer, private val conversationId: String) : ViewModel() {
    private val channel: String? = channelOf(conversationId)
    private val kind = when {
        conversationId == NEARBY_CONVERSATION -> ChatKind.NEARBY
        channel != null -> ChatKind.CHANNEL
        else -> ChatKind.DIRECT
    }
    private val peerId: PeerId? = if (kind == ChatKind.DIRECT) PeerId.fromHex(conversationId) else null

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off messages for a snackbar. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val openedAt = c.clock.now()

    /** Emits when this chat's peer waves while it's open (for a haptic). */
    val waves: Flow<Unit> = c.chats.waves.filterNotNull().filter { it.first == peerId && it.second >= openedAt }.map { }

    private val ticker = flow {
        while (true) {
            emit(c.clock.now())
            delay(30_000)
        }
    }

    private val query = MutableStateFlow<String?>(null)
    private val draftUpdates = MutableStateFlow<String?>(null)

    private val searchResults: Flow<List<SearchHit>?> = query.debounce(150).flatMapLatest { q ->
        if (q.isNullOrBlank()) {
            flowOf(if (q == null) null else emptyList())
        } else {
            c.chats.search(conversationId, q.trim()).map { list ->
                list.map { SearchHit(it.id, if (it.outgoing) "You" else it.senderNickname, Replies.parse(it.body).text, it.sortKey) }
            }
        }
    }

    private val content = combine(
        c.chats.messages(conversationId),
        c.chats.reactions(conversationId),
        c.peers.peers,
        c.identity,
        c.settingsState,
    ) { messages, reactions, peers, identity, settings ->
        Content(messages, reactions, peers, identity?.peerId?.toHex().orEmpty(), settings?.profile)
    }

    private data class Content(
        val messages: List<MessageEntity>,
        val reactions: List<ReactionEntity>,
        val peers: List<Peer>,
        val myIdHex: String,
        val me: app.murmur.core.mesh.Profile?,
    )

    private val extras = combine(
        c.chats.conversation(conversationId),
        c.chats.channels,
        c.chats.typing,
        ticker,
        searchResults,
    ) { conv, channels, typing, now, search -> Extras(conv, channels, typing, now, search) }

    private data class Extras(
        val conv: ConversationEntity?,
        val channels: List<ChannelEntity>,
        val typing: Set<PeerId>,
        val now: Long,
        val search: List<SearchHit>?,
    )

    val state: StateFlow<ChatUiState> = combine(content, extras) { ct, ex -> build(ct, ex) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(conversationId = conversationId, kind = kind))

    init {
        viewModelScope.launch {
            draftUpdates.filterNotNull().debounce(500).collect { c.chats.saveDraft(conversationId, it) }
        }
    }

    private fun build(ct: Content, ex: Extras): ChatUiState {
        val byId = ct.peers.associateBy { it.id.toHex() }
        val peer = peerId?.let { byId[it.toHex()] }
        val online = ct.peers.count { it.isOnline && !it.blocked }
        val channelEntity = channel?.let { name -> ex.channels.firstOrNull { it.name == name } }
        val recentSenders = ct.messages.asSequence()
            .filter { !it.outgoing && it.kind == MessageKind.TEXT && ex.now - it.sortKey < ACTIVE_WINDOW_MILLIS }
            .map { it.senderId }.toSet().size
        val timer = ex.conv?.disappearSeconds ?: 0L
        val subtitle = when (kind) {
            ChatKind.NEARBY -> "$online ${if (online == 1) "person" else "people"} in range"
            ChatKind.CHANNEL -> buildString {
                append(if (channelEntity?.keyHex != null) "🔒 password channel" else "open channel")
                append(" · $recentSenders active")
            }
            ChatKind.DIRECT -> Format.peerStatus(peer) + if (timer > 0) " · ⏱ ${app.murmur.core.text.Disappearing.label(timer)}" else ""
        }
        return ChatUiState(
            conversationId = conversationId,
            kind = kind,
            peer = peer,
            title = when (kind) {
                ChatKind.NEARBY -> "#nearby"
                ChatKind.CHANNEL -> "#$channel"
                ChatKind.DIRECT -> peer?.let { Format.displayName(it, ct.peers) } ?: peerId?.let(PeerRepository::fallbackName) ?: "Chat"
            },
            subtitle = subtitle,
            items = buildItems(ct, byId, ex.now),
            typing = peerId != null && peerId in ex.typing,
            blocked = peer?.blocked == true,
            loaded = true,
            muted = ex.conv?.muted == true,
            disappearSeconds = timer,
            channelLocked = channelEntity?.keyHex != null,
            savedDraft = ex.conv?.draft.orEmpty(),
            myName = ct.me?.nickname.orEmpty(),
            mentionNames = if (kind == ChatKind.DIRECT) {
                emptyList()
            } else {
                (ct.peers.filter { it.isOnline && !it.blocked }.map { it.nickname } +
                    ct.messages.filter { !it.outgoing && it.kind == MessageKind.TEXT }.map { it.senderNickname })
                    .distinct().take(20)
            },
            searchResults = ex.search,
        )
    }

    private fun buildItems(ct: Content, peers: Map<String, Peer>, now: Long): List<ChatItem> {
        val messages = ct.messages
        val reactionsByMessage = ct.reactions.groupBy { it.messageId }
        fun groupable(m: MessageEntity) = m.kind == MessageKind.TEXT && !m.retracted
        fun sameGroup(a: MessageEntity?, b: MessageEntity?): Boolean = a != null && b != null && groupable(a) && groupable(b) &&
            a.outgoing == b.outgoing && a.senderId == b.senderId &&
            abs(a.sortKey - b.sortKey) <= GROUP_WINDOW_MILLIS && Format.sameDay(a.sortKey, b.sortKey)

        val out = ArrayList<ChatItem>(messages.size + 8)
        for (i in messages.indices) {
            val m = messages[i]
            val newer = messages.getOrNull(i - 1)
            val older = messages.getOrNull(i + 1)
            val sender = peers[m.senderId]
            val withOlder = sameGroup(m, older)
            val parsed = Replies.parse(m.body)
            val reactions = reactionsByMessage[m.id].orEmpty()
                .groupBy { it.emoji }
                .map { (emoji, list) -> ReactionUi(emoji, list.size, list.any { it.reactorId == ct.myIdHex }) }
                .sortedByDescending { it.count }
            out += ChatItem.Bubble(
                MessageUi(
                    id = m.id,
                    body = parsed.text,
                    outgoing = m.outgoing,
                    senderId = m.senderId,
                    senderName = if (m.outgoing) "You" else sender?.name ?: m.senderNickname,
                    senderEmoji = if (m.outgoing) ct.me?.emoji ?: PeerRepository.DEFAULT_EMOJI else sender?.emoji ?: PeerRepository.DEFAULT_EMOJI,
                    senderColor = if (m.outgoing) ct.me?.colorIndex ?: 0 else sender?.colorIndex ?: 0,
                    time = m.sortKey,
                    status = if (m.outgoing && kind == ChatKind.DIRECT && m.kind == MessageKind.TEXT) DeliveryStatus.entries.getOrNull(m.status) else null,
                    hops = m.hops,
                    groupedWithOlder = withOlder,
                    groupedWithNewer = sameGroup(m, newer),
                    showHeader = kind != ChatKind.DIRECT && !m.outgoing && !withOlder && m.kind == MessageKind.TEXT,
                    quoteAuthor = parsed.quoteAuthor,
                    quote = parsed.quote,
                    reactions = reactions,
                    retracted = m.retracted,
                    kind = m.kind,
                    mentionsMe = m.mentionsMe,
                    sentAt = m.sentAt,
                    deliveredAt = m.deliveredAt,
                    readAt = m.readAt,
                    expiresAt = m.expiresAt,
                    publicName = if (m.outgoing) ct.me?.nickname ?: m.senderNickname else m.senderNickname,
                ),
            )
            if (older == null || !Format.sameDay(m.sortKey, older.sortKey)) {
                out += ChatItem.Day(Format.day(now, m.sortKey), "day-${Format.dayKey(m.sortKey)}")
            }
        }
        return out
    }

    // ------------------------------------------------------------------ actions

    fun send(text: String, replyTo: MessageUi?) {
        val body = if (replyTo != null && !replyTo.retracted) {
            Replies.compose(replyTo.publicName, replyTo.plain, text.trim())
        } else {
            text
        }
        viewModelScope.launch {
            val ok = when (kind) {
                ChatKind.NEARBY -> c.chats.sendNearby(body)
                ChatKind.CHANNEL -> c.chats.sendChannel(channel!!, body)
                ChatKind.DIRECT -> peerId?.let { c.chats.sendDirect(it, body) } ?: false
            }
            if (!ok) _events.tryEmit(if (kind == ChatKind.DIRECT) "Couldn't queue the message." else "The mesh is off, so this can't be sent.")
        }
    }

    fun onDraftChanged(text: String) {
        draftUpdates.value = text
        if (text.isNotBlank()) {
            val id = peerId ?: return
            viewModelScope.launch { c.chats.userTyping(id) }
        }
    }

    fun setSearch(q: String?) {
        query.value = q
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { c.chats.react(conversationId, messageId, emoji) }
    }

    fun retract(messageId: String) {
        viewModelScope.launch { c.chats.retract(conversationId, messageId) }
    }

    fun wave() {
        val id = peerId ?: return
        viewModelScope.launch { if (!c.chats.wave(id)) _events.tryEmit("You just waved. Give it a moment.") }
    }

    fun setTimer(seconds: Long) {
        val id = peerId ?: return
        viewModelScope.launch { c.chats.setDisappearing(id, seconds) }
    }

    fun toggleMute() {
        val muted = state.value.muted
        viewModelScope.launch { c.chats.setMuted(conversationId, !muted) }
    }

    fun leaveChannel(onLeft: () -> Unit) {
        val name = channel ?: return
        viewModelScope.launch {
            c.chats.leaveChannel(name)
            onLeft()
        }
    }

    fun sendSos(text: String) {
        viewModelScope.launch {
            when (c.chats.sendSos(text)) {
                SosResult.SENT -> _events.tryEmit("SOS sent to everyone in range")
                SosResult.TOO_SOON -> _events.tryEmit("You sent an SOS less than 2 minutes ago")
                SosResult.NO_MESH -> _events.tryEmit("The mesh is off, so the SOS can't be sent")
            }
        }
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
        draftUpdates.value?.let { d -> c.appScope.launch { c.chats.saveDraft(conversationId, d) } }
        if (c.chats.currentConversation.value == conversationId) c.chats.setOpenConversation(null)
    }

    private companion object {
        const val GROUP_WINDOW_MILLIS = 3 * 60_000L
        const val ACTIVE_WINDOW_MILLIS = 15 * 60_000L
    }
}
