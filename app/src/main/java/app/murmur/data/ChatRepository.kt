package app.murmur.data

import app.murmur.core.Clock
import app.murmur.core.Murmur
import app.murmur.core.SecureRandomSource
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.mesh.MeshEvent
import app.murmur.core.mesh.MeshNode
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.PeerId
import app.murmur.data.db.ConversationEntity
import app.murmur.data.db.MessageEntity
import app.murmur.data.db.MurmurDatabase
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.data.db.tx
import app.murmur.diagnostics.DiagnosticsLog
import app.murmur.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Receives from the mesh / demo mode, stores in Room, and sends on behalf of the UI. */
class ChatRepository(
    private val db: MurmurDatabase,
    private val settings: StateFlow<Settings>,
    private val node: () -> MeshNode?,
    private val myId: () -> PeerId?,
    private val peers: () -> List<Peer>,
    private val notifier: Notifier,
    private val clock: Clock,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
) {
    /** Wired after construction (demo mode depends on this repository too). */
    var demo: DemoGateway? = null

    private val random = SecureRandomSource()

    val conversations: Flow<List<ConversationEntity>> = db.conversations().observeAll()
    val totalUnread: Flow<Int> = db.conversations().observeTotalUnread()

    fun messages(conversationId: String): Flow<List<MessageEntity>> =
        db.messages().observeConversation(conversationId, MESSAGE_WINDOW)

    private val openConversation = MutableStateFlow<String?>(null)
    val currentConversation: StateFlow<String?> = openConversation.asStateFlow()

    /** Called by the chat screen while it is visible (null when it leaves). */
    fun setOpenConversation(id: String?) {
        openConversation.value = id
        if (id != null) notifier.cancelConversation(id)
    }

    private val typingUntil = MutableStateFlow<Map<PeerId, Long>>(emptyMap())
    val typing: StateFlow<Set<PeerId>> = typingUntil.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // ------------------------------------------------------------------ outgoing

    /** Returns false if there's no way to send (mesh stopped and demo mode off). */
    suspend fun sendNearby(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty() || Murmur.utf8Size(body) > Murmur.MAX_TEXT_BYTES) return false
        val me = settings.value.profile ?: return false
        val n = node()
        val id = when {
            n != null -> n.sendPublic(body).toHex()
            demo?.isEnabled == true -> PacketId.random(random).toHex()
            else -> return false
        }
        val now = clock.now()
        db.tx {
            db.messages().insertOrIgnore(
                MessageEntity(
                    id = id,
                    conversationId = NEARBY_CONVERSATION,
                    senderId = myId()?.toHex() ?: "",
                    senderNickname = me.nickname,
                    body = body,
                    sentAt = now,
                    sortKey = now,
                    outgoing = true,
                    status = -1,
                    hops = 0,
                    seen = true,
                ),
            )
            touchConversation(NEARBY_CONVERSATION, null, "You: $body", now, unreadDelta = 0)
        }
        demo?.onOutgoingNearby(body)
        return true
    }

    suspend fun sendDirect(peer: PeerId, text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty() || Murmur.utf8Size(body) > Murmur.MAX_TEXT_BYTES) return false
        val me = settings.value.profile ?: return false
        val messageId = MessageId.random(random)
        val now = clock.now()
        db.tx {
            db.messages().insertOrIgnore(
                MessageEntity(
                    id = messageId.toHex(),
                    conversationId = peer.toHex(),
                    senderId = myId()?.toHex() ?: "",
                    senderNickname = me.nickname,
                    body = body,
                    sentAt = now,
                    sortKey = now,
                    outgoing = true,
                    status = DeliveryStatus.PENDING.ordinal,
                    hops = 0,
                    seen = true,
                ),
            )
            touchConversation(peer.toHex(), peer.toHex(), "You: $body", now, unreadDelta = 0)
        }
        dispatchDirect(peer, messageId, body, now)
        return true
    }

    /** "Not delivered · tap to retry". */
    suspend fun retry(messageIdHex: String) {
        val m = db.messages().get(messageIdHex) ?: return
        if (!m.outgoing || m.status != DeliveryStatus.FAILED.ordinal) return
        val peer = PeerId.fromHex(m.conversationId) ?: return
        val messageId = MessageId.fromHex(m.id) ?: return
        db.messages().setSendStatus(m.id, DeliveryStatus.PENDING.ordinal, DeliveryStatus.DELIVERED.ordinal)
        dispatchDirect(peer, messageId, m.body, clock.now())
    }

    private suspend fun dispatchDirect(peer: PeerId, messageId: MessageId, body: String, createdAt: Long) {
        val d = demo
        if (d != null && d.isDemoPeer(peer)) {
            d.onOutgoingDirect(peer, messageId.toHex(), body)
            return
        }
        // No running mesh: stays Pending and is re-queued when the mesh starts.
        node()?.sendDirectMessage(peer, messageId, body, createdAt)
    }

    /** From the composer; the mesh throttles to one TYPING per 3 s. */
    suspend fun userTyping(peer: PeerId) {
        if (demo?.isDemoPeer(peer) == true) return
        node()?.sendTyping(peer)
    }

    /** Clears the unread badge and sends READ for messages now on screen (if read receipts are on). */
    suspend fun markConversationRead(conversationId: String) {
        db.conversations().markRead(conversationId)
        if (conversationId == NEARBY_CONVERSATION) return
        val unseen = db.messages().unseenIncoming(conversationId)
        if (unseen.isEmpty()) return
        db.messages().markSeen(unseen.map { it.id })
        if (!settings.value.readReceipts) return
        val peer = PeerId.fromHex(conversationId) ?: return
        val d = demo
        for (m in unseen) {
            if (d != null && d.isDemoPeer(peer)) continue
            val id = MessageId.fromHex(m.id) ?: continue
            node()?.sendReadReceipt(peer, id)
        }
    }

    suspend fun deleteMessage(id: String) = db.messages().delete(id)

    suspend fun clearConversation(conversationId: String) = db.tx {
        db.messages().deleteConversation(conversationId)
        db.conversations().get(conversationId)?.let {
            db.conversations().upsert(it.copy(preview = "", unread = 0))
        }
    }

    suspend fun deleteConversation(conversationId: String) = db.tx {
        db.messages().deleteConversation(conversationId)
        db.conversations().delete(conversationId)
    }

    // ------------------------------------------------------------------ incoming

    suspend fun onMeshEvent(event: MeshEvent) {
        try {
            when (event) {
                is MeshEvent.PublicMessage -> receivePublic(
                    event.packetId.toHex(), event.senderId, event.nickname, event.text, event.timestamp, event.hops,
                )
                is MeshEvent.DirectMessage -> receiveDirect(
                    event.messageId.toHex(), event.senderId, nicknameOf(event.senderId), event.body, event.timestamp, event.hops,
                )
                is MeshEvent.Delivery -> updateDelivery(event.messageId.toHex(), event.peerId, event.status)
                is MeshEvent.Typing -> receiveTyping(event.peerId)
                is MeshEvent.LinkIdentified -> Unit
            }
        } catch (e: Exception) {
            log.log("CHAT", "failed to store ${event.javaClass.simpleName}: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    suspend fun receivePublic(packetIdHex: String, sender: PeerId, nickname: String, text: String, sentAt: Long, hops: Int) {
        if (isBlocked(sender)) return
        val now = clock.now()
        val open = openConversation.value == NEARBY_CONVERSATION
        val inserted = db.tx {
            val row = db.messages().insertOrIgnore(
                MessageEntity(
                    id = packetIdHex,
                    conversationId = NEARBY_CONVERSATION,
                    senderId = sender.toHex(),
                    senderNickname = nickname,
                    body = text,
                    sentAt = sentAt,
                    sortKey = now,
                    outgoing = false,
                    status = -1,
                    hops = hops,
                    seen = true,
                ),
            )
            if (row != -1L) touchConversation(NEARBY_CONVERSATION, null, "$nickname: $text", now, unreadDelta = if (open) 0 else 1)
            row != -1L
        }
        if (inserted && !open && settings.value.nearbyNotifications) {
            notifier.showNearby(nickname, text)
        }
    }

    suspend fun receiveDirect(messageIdHex: String, sender: PeerId, nickname: String, body: String, sentAt: Long, hops: Int) {
        if (isBlocked(sender)) return
        typingUntil.update { it - sender }
        val conversationId = sender.toHex()
        val now = clock.now()
        val open = openConversation.value == conversationId
        val inserted = db.tx {
            val row = db.messages().insertOrIgnore(
                MessageEntity(
                    id = messageIdHex,
                    conversationId = conversationId,
                    senderId = conversationId,
                    senderNickname = nickname,
                    body = body,
                    sentAt = sentAt,
                    sortKey = now,
                    outgoing = false,
                    status = -1,
                    hops = hops,
                    seen = false,
                ),
            )
            if (row != -1L) touchConversation(conversationId, conversationId, body, now, unreadDelta = if (open) 0 else 1)
            row != -1L
        }
        if (!inserted) return
        if (open) {
            markConversationRead(conversationId)
        } else {
            val peer = peers().firstOrNull { it.id == sender }
            notifier.showDirect(sender, nickname, peer?.emoji ?: PeerRepository.DEFAULT_EMOJI, body)
        }
    }

    suspend fun updateDelivery(messageIdHex: String, peer: PeerId, status: DeliveryStatus) {
        if (status == DeliveryStatus.DELIVERED || status == DeliveryStatus.READ) {
            db.messages().upgradeStatus(messageIdHex, peer.toHex(), status.ordinal)
        } else {
            db.messages().setSendStatus(messageIdHex, status.ordinal, DeliveryStatus.DELIVERED.ordinal)
        }
    }

    fun receiveTyping(peer: PeerId) {
        if (isBlocked(peer)) return
        val until = clock.now() + TYPING_VISIBLE_MILLIS
        typingUntil.update { it + (peer to until) }
        scope.launch {
            delay(TYPING_VISIBLE_MILLIS + 50)
            val now = clock.now()
            typingUntil.update { m -> m.filterValues { it > now } }
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Hands every unfinished DM to a freshly started mesh; fails those older than 24 h. */
    suspend fun requeuePending(node: MeshNode) {
        val open = listOf(DeliveryStatus.PENDING, DeliveryStatus.SENDING, DeliveryStatus.SENT).map { it.ordinal }
        val now = clock.now()
        var queued = 0
        for (m in db.messages().outgoingWithStatus(open)) {
            val peer = PeerId.fromHex(m.conversationId) ?: continue
            val id = MessageId.fromHex(m.id) ?: continue
            if (demo?.isDemoPeer(peer) == true) continue
            if (now - m.sortKey >= PENDING_EXPIRY_MILLIS) {
                db.messages().setSendStatus(m.id, DeliveryStatus.FAILED.ordinal, DeliveryStatus.DELIVERED.ordinal)
            } else {
                node.sendDirectMessage(peer, id, m.body, m.sortKey)
                queued++
            }
        }
        if (queued > 0) log.log("CHAT", "re-queued $queued unfinished DMs")
    }

    /** #nearby messages auto-delete after 24 h. */
    suspend fun purgeOldNearby() {
        val removed = db.messages().purgeNearbyBefore(clock.now() - NEARBY_RETENTION_MILLIS)
        if (removed > 0) {
            log.log("CHAT", "purged $removed old #nearby messages")
            val latest = db.messages().latest(NEARBY_CONVERSATION)
            db.conversations().get(NEARBY_CONVERSATION)?.let {
                db.conversations().upsert(it.copy(preview = latest?.let { m -> "${m.senderNickname}: ${m.body}" } ?: ""))
            }
        }
    }

    private suspend fun touchConversation(id: String, peerId: String?, preview: String, time: Long, unreadDelta: Int) {
        val existing = db.conversations().get(id)
        db.conversations().upsert(
            ConversationEntity(
                id = id,
                peerId = peerId,
                preview = preview.take(PREVIEW_CHARS),
                lastActivity = maxOf(time, existing?.lastActivity ?: 0L),
                unread = (existing?.unread ?: 0) + unreadDelta,
            ),
        )
    }

    private fun isBlocked(peer: PeerId): Boolean = peers().firstOrNull { it.id == peer }?.blocked == true

    private fun nicknameOf(peer: PeerId): String =
        peers().firstOrNull { it.id == peer }?.nickname ?: PeerRepository.fallbackName(peer)

    companion object {
        const val MESSAGE_WINDOW = 1_000
        const val PREVIEW_CHARS = 140
        const val TYPING_VISIBLE_MILLIS = 5_000L
        const val PENDING_EXPIRY_MILLIS = 24 * 60 * 60 * 1000L
        const val NEARBY_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/** What the chat repository needs from demo mode (kept narrow so demo never touches the mesh). */
interface DemoGateway {
    val isEnabled: Boolean
    fun isDemoPeer(peer: PeerId): Boolean
    fun onOutgoingDirect(peer: PeerId, messageIdHex: String, body: String)
    fun onOutgoingNearby(body: String)
}
