package app.murmur.data

import app.murmur.core.text.Replies
import app.murmur.core.text.Mentions
import app.murmur.core.text.Disappearing
import app.murmur.core.Clock
import app.murmur.core.Murmur
import app.murmur.core.SecureRandomSource
import app.murmur.core.crypto.ChannelCrypto
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.mesh.MeshEvent
import app.murmur.core.mesh.MeshNode
import app.murmur.core.protocol.Bytes
import app.murmur.core.protocol.ChannelInvites
import app.murmur.core.protocol.Channels
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.RoomKind
import app.murmur.data.db.ChannelEntity
import app.murmur.data.db.ConversationEntity
import app.murmur.data.db.MessageEntity
import app.murmur.data.db.MessageKind
import app.murmur.data.db.MurmurDatabase
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.data.db.ReactionEntity
import app.murmur.data.db.channelConversation
import app.murmur.data.db.channelOf
import app.murmur.data.db.tx
import app.murmur.diagnostics.DiagnosticsLog
import app.murmur.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

/** An emergency alert shown as a banner until dismissed (or 30 minutes pass). */
data class SosAlert(val id: String, val peerId: PeerId, val nickname: String, val text: String, val time: Long, val hops: Int, val mine: Boolean)

enum class SosResult { SENT, TOO_SOON, NO_MESH }

/** A channel someone nearby is using (seen in the last [ChatRepository.NEARBY_CHANNEL_MILLIS]). */
data class NearbyChannel(val name: String, val locked: Boolean, val lastSeen: Long)

enum class InviteResult { SENT, NOT_A_MEMBER, UNREACHABLE }

sealed interface JoinResult {
    data class Joined(val name: String) : JoinResult
    data object Invalid : JoinResult
}

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
    val channels: Flow<List<ChannelEntity>> = db.channels().observeAll()

    /** Keys of joined password channels, for the mesh. */
    val channelKeys: Flow<Map<String, ByteArray>> = channels.map { list ->
        list.mapNotNull { ch -> ch.keyHex?.let(Bytes::fromHex)?.let { ch.name to it } }.toMap()
    }

    fun messages(conversationId: String): Flow<List<MessageEntity>> =
        db.messages().observeConversation(conversationId, MESSAGE_WINDOW)

    fun reactions(conversationId: String): Flow<List<ReactionEntity>> = db.reactions().observe(conversationId)

    fun conversation(conversationId: String): Flow<ConversationEntity?> = db.conversations().observe(conversationId)

    fun search(conversationId: String, query: String): Flow<List<MessageEntity>> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.messages().search(conversationId, escaped)
    }

    private val openConversation = MutableStateFlow<String?>(null)
    val currentConversation: StateFlow<String?> = openConversation.asStateFlow()

    /** Called by the chat screen while it is visible (null when it leaves). */
    fun setOpenConversation(id: String?) {
        openConversation.value = id
        if (id != null) notifier.cancelConversation(id)
    }

    private val typingUntil = MutableStateFlow<Map<PeerId, Long>>(emptyMap())
    val typing: StateFlow<Set<PeerId>> = typingUntil.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _sos = MutableStateFlow<List<SosAlert>>(emptyList())

    /** Active emergency alerts (newest first). */
    val sosAlerts: StateFlow<List<SosAlert>> = _sos.asStateFlow()
    private var lastSosAt = 0L
    private val _nearbyChannels = MutableStateFlow<Map<Pair<String, Boolean>, NearbyChannel>>(emptyMap())

    /** Channels in use around me, newest first (names are visible to every relay; contents aren't). */
    val nearbyChannels: Flow<List<NearbyChannel>> = _nearbyChannels.map { m -> m.values.sortedByDescending { it.lastSeen } }
    private val lastChannelHintAt = HashMap<String, Long>()
    private val lastWaveAt = HashMap<PeerId, Long>()

    private val me: Settings get() = settings.value
    private val hideContent: Boolean get() = me.hideNotificationContent || me.appLock.enabled

    // ================================================================== outgoing

    /** Returns false if there's no way to send (mesh stopped and demo mode off). */
    suspend fun sendNearby(text: String): Boolean {
        val body = text.trim()
        if (!isSendable(body)) return false
        val profile = me.profile ?: return false
        val n = node()
        val id = when {
            n != null -> n.sendPublic(body, me.publicReach).toHex()
            demo?.isEnabled == true -> PacketId.random(random).toHex()
            else -> return false
        }
        storeOutgoing(NEARBY_CONVERSATION, id, profile.nickname, body, status = -1)
        demo?.onOutgoingNearby(body)
        return true
    }

    suspend fun sendChannel(name: String, text: String): Boolean {
        val body = text.trim()
        if (!isSendable(body)) return false
        val profile = me.profile ?: return false
        db.channels().get(name) ?: return false
        val id = sendRoom(name, RoomKind.TEXT, body, null) ?: return false
        storeOutgoing(channelConversation(name), id, profile.nickname, body, status = -1)
        demo?.onOutgoingChannel(name, body)
        return true
    }

    suspend fun sendDirect(peer: PeerId, text: String): Boolean {
        val body = text.trim()
        if (!isSendable(body)) return false
        val profile = me.profile ?: return false
        val messageId = MessageId.random(random)
        val now = clock.now()
        storeOutgoing(peer.toHex(), messageId.toHex(), profile.nickname, body, status = DeliveryStatus.PENDING.ordinal, peerId = peer.toHex())
        dispatchDirect(peer, messageId, body, now)
        return true
    }

    private fun isSendable(body: String) = body.isNotEmpty() && Murmur.utf8Size(body) <= Murmur.MAX_TEXT_BYTES

    private suspend fun storeOutgoing(conversationId: String, id: String, nickname: String, body: String, status: Int, peerId: String? = null) {
        val now = clock.now()
        db.tx {
            val conv = db.conversations().get(conversationId)
            db.messages().insertOrIgnore(
                MessageEntity(
                    id = id,
                    conversationId = conversationId,
                    senderId = myId()?.toHex() ?: "",
                    senderNickname = nickname,
                    body = body,
                    sentAt = now,
                    sortKey = now,
                    outgoing = true,
                    status = status,
                    hops = 0,
                    seen = true,
                    expiresAt = expiryFor(conv, now),
                ),
            )
            touchConversation(conversationId, peerId, "You: ${Replies.parse(body).text}", now, unreadDelta = 0)
            db.conversations().setDraft(conversationId, "")
        }
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

    /** Toggles my [emoji] reaction on a message (choosing the same emoji again removes it). */
    suspend fun react(conversationId: String, messageId: String, emoji: String) {
        val mine = myId()?.toHex() ?: return
        val msg = db.messages().get(messageId) ?: return
        if (msg.conversationId != conversationId || msg.retracted || msg.kind == MessageKind.SYSTEM) return
        val existing = db.reactions().get(messageId, mine)
        val chosen = if (existing?.emoji == emoji) "" else emoji
        if (chosen.isEmpty()) {
            db.reactions().delete(messageId, mine)
        } else {
            db.reactions().upsert(ReactionEntity(messageId, conversationId, mine, chosen, clock.now()))
        }
        when {
            conversationId == NEARBY_CONVERSATION -> PacketId.fromHex(messageId)?.let { sendRoom("", RoomKind.REACTION, chosen, it) }
            channelOf(conversationId) != null -> PacketId.fromHex(messageId)?.let { sendRoom(channelOf(conversationId)!!, RoomKind.REACTION, chosen, it) }
            else -> {
                val peer = PeerId.fromHex(conversationId) ?: return
                val target = MessageId.fromHex(messageId) ?: return
                if (demo?.isDemoPeer(peer) == true) demo?.onReaction(peer, messageId, chosen) else node()?.sendDirectControl(peer, DmKind.REACTION, target, chosen)
            }
        }
    }

    /** Delete for everyone (own messages only). Also removes it here. */
    suspend fun retract(conversationId: String, messageId: String) {
        val msg = db.messages().get(messageId) ?: return
        if (!msg.outgoing || msg.conversationId != conversationId || msg.retracted) return
        db.messages().retract(messageId, conversationId, msg.senderId)
        db.reactions().deleteForMessage(messageId)
        refreshPreview(conversationId)
        when {
            conversationId == NEARBY_CONVERSATION -> PacketId.fromHex(messageId)?.let { sendRoom("", RoomKind.RETRACT, "", it) }
            channelOf(conversationId) != null -> PacketId.fromHex(messageId)?.let { sendRoom(channelOf(conversationId)!!, RoomKind.RETRACT, "", it) }
            else -> {
                val peer = PeerId.fromHex(conversationId) ?: return
                val target = MessageId.fromHex(messageId) ?: return
                if (demo?.isDemoPeer(peer) != true) node()?.sendDirectControl(peer, DmKind.RETRACT, target)
            }
        }
    }

    /** 👋 A nudge. At most one per 10 s per person. Returns false if throttled. */
    suspend fun wave(peer: PeerId): Boolean {
        val now = clock.now()
        if (now - (lastWaveAt[peer] ?: 0L) < WAVE_INTERVAL_MILLIS) return false
        lastWaveAt[peer] = now
        if (demo?.isDemoPeer(peer) == true) demo?.onWave(peer) else node()?.sendDirectControl(peer, DmKind.WAVE, MessageId.random(random))
        insertSystem(peer.toHex(), peer.toHex(), "You waved 👋", unread = false)
        return true
    }

    /** Disappearing messages for a DM (shared with the other person). 0 turns it off. */
    suspend fun setDisappearing(peer: PeerId, seconds: Long) {
        val s = seconds.coerceIn(0, Disappearing.MAX_SECONDS)
        val conv = peer.toHex()
        ensureConversation(conv, conv)
        db.conversations().setDisappear(conv, s)
        if (demo?.isDemoPeer(peer) != true) node()?.sendDirectControl(peer, DmKind.TIMER, MessageId.random(random), s.toString())
        insertSystem(
            conv,
            conv,
            if (s == 0L) "You turned off disappearing messages" else "You set messages to disappear after ${Disappearing.label(s)}",
            unread = false,
        )
    }

    /** Emergency alert to everyone in range. Rate-limited to one per 2 minutes. */
    suspend fun sendSos(text: String): SosResult {
        val now = clock.now()
        if (now - lastSosAt < SOS_INTERVAL_MILLIS) return SosResult.TOO_SOON
        val profile = me.profile ?: return SosResult.NO_MESH
        val body = text.trim().take(SOS_MAX_CHARS)
        val id = sendRoom("", RoomKind.SOS, body, null) ?: return SosResult.NO_MESH
        lastSosAt = now
        db.tx {
            db.messages().insertOrIgnore(
                MessageEntity(
                    id = id,
                    conversationId = NEARBY_CONVERSATION,
                    senderId = myId()?.toHex() ?: "",
                    senderNickname = profile.nickname,
                    body = body,
                    sentAt = now,
                    sortKey = now,
                    outgoing = true,
                    status = -1,
                    hops = 0,
                    seen = true,
                    kind = MessageKind.SOS,
                ),
            )
            touchConversation(NEARBY_CONVERSATION, null, "🆘 You sent an SOS", now, 0)
        }
        myId()?.let { _sos.update { list -> listOf(SosAlert(id, it, profile.nickname, body, now, 0, mine = true)) + list } }
        log.log("CHAT", "SOS sent")
        return SosResult.SENT
    }

    fun dismissSos(id: String) = _sos.update { list -> list.filterNot { it.id == id } }

    /** From the composer; the mesh throttles to one TYPING per 3 s. */
    suspend fun userTyping(peer: PeerId) {
        if (demo?.isDemoPeer(peer) == true) return
        node()?.sendTyping(peer)
    }

    private suspend fun sendRoom(channel: String, kind: RoomKind, body: String, target: PacketId?): String? {
        val key = if (channel.isEmpty()) null else db.channels().get(channel)?.keyHex?.let(Bytes::fromHex)
        val n = node()
        return when {
            n != null -> n.sendRoom(channel, kind, body, target, key, me.publicReach).toHex()
            demo?.isEnabled == true -> PacketId.random(random).toHex()
            else -> null
        }
    }

    // ================================================================== conversation state

    /** Clears the unread badge and sends READ for messages now on screen (if read receipts are on). */
    suspend fun markConversationRead(conversationId: String) {
        db.conversations().markRead(conversationId)
        if (conversationId == NEARBY_CONVERSATION || channelOf(conversationId) != null) return
        val unseen = db.messages().unseenIncoming(conversationId)
        if (unseen.isEmpty()) return
        db.messages().markSeen(unseen.map { it.id })
        if (!me.readReceipts) return
        val peer = PeerId.fromHex(conversationId) ?: return
        val d = demo
        for (m in unseen) {
            if (m.kind != MessageKind.TEXT) continue
            if (d != null && d.isDemoPeer(peer)) continue
            val id = MessageId.fromHex(m.id) ?: continue
            node()?.sendReadReceipt(peer, id)
        }
    }

    suspend fun markAllRead() {
        db.conversations().markAllRead()
        notifier.cancelAll()
    }

    suspend fun setPinned(conversationId: String, pinned: Boolean) = db.conversations().setPinned(conversationId, pinned)
    suspend fun setMuted(conversationId: String, muted: Boolean) = db.conversations().setMuted(conversationId, muted)
    suspend fun saveDraft(conversationId: String, draft: String) {
        if (db.conversations().get(conversationId) != null) db.conversations().setDraft(conversationId, draft.take(4_000))
    }

    suspend fun deleteMessage(id: String) {
        db.messages().delete(id)
        db.reactions().deleteForMessage(id)
    }

    suspend fun clearConversation(conversationId: String) = db.tx {
        db.messages().deleteConversation(conversationId)
        db.reactions().deleteForConversation(conversationId)
        db.conversations().get(conversationId)?.let {
            db.conversations().upsert(it.copy(preview = "", unread = 0))
        }
    }

    suspend fun deleteConversation(conversationId: String) = db.tx {
        db.messages().deleteConversation(conversationId)
        db.reactions().deleteForConversation(conversationId)
        db.conversations().delete(conversationId)
    }

    // ================================================================== channels

    suspend fun joinChannel(input: String, password: String?): JoinResult {
        val name = Channels.normalize(input) ?: return JoinResult.Invalid
        val key = password?.takeIf { it.isNotEmpty() }?.let { pw ->
            withContext(Dispatchers.Default) { ChannelCrypto.deriveKey(name, pw) }
        }
        val now = clock.now()
        db.tx {
            db.channels().upsert(ChannelEntity(name, key?.let(Bytes::toHex), now))
            insertSystemLocked(
                channelConversation(name),
                null,
                if (key != null) "You joined #$name 🔒 (only people with the password can read it)" else "You joined #$name",
                unread = false,
            )
        }
        log.log("CHAT", "joined #$name${if (key != null) " (password)" else ""}")
        return JoinResult.Joined(name)
    }

    /** Sets, changes or (with null/empty) removes the password of a channel I'm in. */
    suspend fun setChannelPassword(name: String, password: String?) {
        val existing = db.channels().get(name) ?: return
        val key = password?.takeIf { it.isNotEmpty() }?.let { pw ->
            withContext(Dispatchers.Default) { ChannelCrypto.deriveKey(name, pw) }
        }
        db.tx {
            db.channels().upsert(existing.copy(keyHex = key?.let(Bytes::toHex)))
            insertSystemLocked(
                channelConversation(name),
                null,
                if (key != null) {
                    "🔒 Password set. You'll see messages from everyone using the same password."
                } else {
                    "Password removed: #$name is open to anyone who joins it."
                },
                unread = false,
            )
        }
        log.log("CHAT", "#$name password ${if (key != null) "set" else "removed"}")
    }

    /** Sends an invitation (with the key, for a password channel) in an encrypted DM. */
    suspend fun sendInvite(peer: PeerId, channel: String): InviteResult {
        val ch = db.channels().get(channel) ?: return InviteResult.NOT_A_MEMBER
        val body = ChannelInvites.body(channel, ch.keyHex?.let(Bytes::fromHex))
        val id = MessageId.random(random)
        val sent = if (demo?.isDemoPeer(peer) == true) true else node()?.sendDirectControl(peer, DmKind.CHANNEL_INVITE, id, body) == true
        if (!sent) return InviteResult.UNREACHABLE
        val conversation = peer.toHex()
        val now = clock.now()
        db.tx {
            val conv = db.conversations().get(conversation)
            db.messages().insertOrIgnore(
                MessageEntity(
                    id = id.toHex(),
                    conversationId = conversation,
                    senderId = myId()?.toHex() ?: "",
                    senderNickname = me.profile?.nickname.orEmpty(),
                    body = body,
                    sentAt = now,
                    sortKey = now,
                    outgoing = true,
                    status = -1,
                    hops = 0,
                    seen = true,
                    kind = MessageKind.INVITE,
                    expiresAt = expiryFor(conv, now),
                ),
            )
            touchConversation(conversation, conversation, "You invited them to #$channel", now, unreadDelta = 0)
        }
        log.log("CHAT", "invited ${nicknameOf(peer)} to #$channel")
        return InviteResult.SENT
    }

    /** Joins the channel from an invite message. Returns the channel's conversation id. */
    suspend fun acceptInvite(messageIdHex: String): String? {
        val msg = db.messages().get(messageIdHex)?.takeIf { it.kind == MessageKind.INVITE } ?: return null
        val invite = ChannelInvites.parse(msg.body) ?: return null
        val keyHex = invite.key?.let(Bytes::toHex)
        val existing = db.channels().get(invite.channel)
        val from = if (msg.outgoing) null else peers().firstOrNull { it.id.toHex() == msg.senderId }?.name ?: msg.senderNickname
        val lock = if (keyHex != null) " 🔒" else ""
        val line = when {
            existing == null -> "You joined #${invite.channel}$lock${from?.let { " (invited by $it)" } ?: ""}"
            existing.keyHex != keyHex -> "#${invite.channel} now uses ${from?.let { "$it's" } ?: "the invite's"} ${if (keyHex != null) "password" else "open setting (no password)"}"
            else -> null
        }
        db.tx {
            db.channels().upsert(ChannelEntity(invite.channel, keyHex, existing?.joinedAt ?: clock.now()))
            if (line != null) insertSystemLocked(channelConversation(invite.channel), null, line, unread = false)
        }
        return channelConversation(invite.channel)
    }

    /**
     * Channel discovery, and the hints that explain an empty channel: someone nearby uses the same
     * name with a password (or without), or with a different password.
     */
    private suspend fun onChannelSeen(e: MeshEvent.ChannelSeen) {
        val now = clock.now()
        _nearbyChannels.update { m ->
            (m + ((e.channel to e.encrypted) to NearbyChannel(e.channel, e.encrypted, now)))
                .filterValues { now - it.lastSeen < NEARBY_CHANNEL_MILLIS }
        }
        val member = db.channels().get(e.channel) ?: return
        val hint = when {
            member.keyHex == null && e.encrypted ->
                "🔒 Someone nearby uses #${e.channel} with a password, so you can't see their messages. Ask them for it, then ⋮ → Password."
            member.keyHex != null && !e.encrypted ->
                "Someone nearby uses #${e.channel} without a password, so you can't see each other. To join them: ⋮ → Password → leave it empty."
            member.keyHex != null && !e.readable ->
                "🔒 A message in #${e.channel} couldn't be unlocked: your password is different from theirs. Check it with them: ⋮ → Password."
            else -> return
        }
        if (now - (lastChannelHintAt[e.channel] ?: 0L) < CHANNEL_HINT_MILLIS) return
        lastChannelHintAt[e.channel] = now
        insertSystem(channelConversation(e.channel), null, hint, unread = false)
    }

    suspend fun leaveChannel(name: String) {
        db.channels().delete(name)
        deleteConversation(channelConversation(name))
        log.log("CHAT", "left #$name")
    }

    // ================================================================== incoming

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
                is MeshEvent.RoomMessage -> receiveRoom(
                    packetIdHex = event.packetId.toHex(),
                    sender = event.senderId,
                    channel = event.channel,
                    encrypted = event.encrypted,
                    kind = event.kind,
                    nickname = event.nickname,
                    targetHex = event.target?.toHex(),
                    body = event.body,
                    sentAt = event.timestamp,
                    hops = event.hops,
                )
                is MeshEvent.DirectControl -> receiveDirectControl(event.senderId, event.kind, event.messageId.toHex(), event.body)
                is MeshEvent.ChannelSeen -> onChannelSeen(event)
                is MeshEvent.LinkIdentified, is MeshEvent.CallAudio -> Unit
            }
        } catch (e: Exception) {
            log.log("CHAT", "failed to store ${event.javaClass.simpleName}: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    suspend fun receivePublic(packetIdHex: String, sender: PeerId, nickname: String, text: String, sentAt: Long, hops: Int) {
        receiveRoomText(NEARBY_CONVERSATION, packetIdHex, sender, nickname, text, sentAt, hops) { conv, mention ->
            !conv.muted && (mention || me.nearbyNotifications)
        }
    }

    private suspend fun receiveRoomText(
        conversationId: String,
        packetIdHex: String,
        sender: PeerId,
        nickname: String,
        text: String,
        sentAt: Long,
        hops: Int,
        shouldNotify: (ConversationEntity, Boolean) -> Boolean,
    ) {
        if (isBlocked(sender)) return
        val now = clock.now()
        val open = openConversation.value == conversationId
        val mention = Mentions.mentions(text, me.profile?.nickname)
        val conv = db.tx {
            val row = db.messages().insertOrIgnore(
                MessageEntity(
                    id = packetIdHex,
                    conversationId = conversationId,
                    senderId = sender.toHex(),
                    senderNickname = nickname,
                    body = text,
                    sentAt = sentAt,
                    sortKey = now,
                    outgoing = false,
                    status = -1,
                    hops = hops,
                    seen = true,
                    mentionsMe = mention,
                ),
            )
            if (row == -1L) null else touchConversation(conversationId, null, "$nickname: ${Replies.parse(text).text}", now, unreadDelta = if (open) 0 else 1)
        } ?: return
        if (!open && shouldNotify(conv, mention)) {
            notifier.showRoom(conversationId, roomTitle(conversationId), nickname, text, mention, hideContent)
        }
    }

    suspend fun receiveDirect(messageIdHex: String, sender: PeerId, nickname: String, body: String, sentAt: Long, hops: Int) {
        if (isBlocked(sender)) return
        typingUntil.update { it - sender }
        val conversationId = sender.toHex()
        val now = clock.now()
        val open = openConversation.value == conversationId
        val conv = db.tx {
            val existing = db.conversations().get(conversationId)
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
                    expiresAt = expiryFor(existing, now),
                ),
            )
            if (row == -1L) null else touchConversation(conversationId, conversationId, Replies.parse(body).text, now, unreadDelta = if (open) 0 else 1)
        } ?: return
        if (open) {
            markConversationRead(conversationId)
        } else if (!conv.muted) {
            val peer = peers().firstOrNull { it.id == sender }
            notifier.showDirect(sender, peer?.name ?: nickname, peer?.emoji ?: PeerRepository.DEFAULT_EMOJI, body, hideContent)
        }
    }

    suspend fun receiveRoom(
        packetIdHex: String,
        sender: PeerId,
        channel: String,
        encrypted: Boolean,
        kind: RoomKind,
        nickname: String,
        targetHex: String?,
        body: String,
        sentAt: Long,
        hops: Int,
    ) {
        if (isBlocked(sender)) return
        val conversationId = if (channel.isEmpty()) {
            NEARBY_CONVERSATION
        } else {
            val joined = db.channels().get(channel) ?: return // not a member
            if ((joined.keyHex != null) != encrypted) return // an open channel and a password channel can share a name
            channelConversation(channel)
        }
        when (kind) {
            RoomKind.TEXT -> receiveRoomText(conversationId, packetIdHex, sender, nickname, body, sentAt, hops) { conv, mention ->
                !conv.muted && (mention || conversationId != NEARBY_CONVERSATION || me.nearbyNotifications)
            }
            RoomKind.REACTION -> targetHex?.let { applyReaction(conversationId, it, sender.toHex(), body) }
            RoomKind.RETRACT -> targetHex?.let { applyRetraction(conversationId, it, sender.toHex()) }
            RoomKind.SOS -> if (conversationId == NEARBY_CONVERSATION) receiveSos(packetIdHex, sender, nickname, body, sentAt, hops)
        }
    }

    private suspend fun receiveSos(packetIdHex: String, sender: PeerId, nickname: String, body: String, sentAt: Long, hops: Int) {
        val now = clock.now()
        val inserted = db.tx {
            val row = db.messages().insertOrIgnore(
                MessageEntity(
                    id = packetIdHex,
                    conversationId = NEARBY_CONVERSATION,
                    senderId = sender.toHex(),
                    senderNickname = nickname,
                    body = body.take(SOS_MAX_CHARS),
                    sentAt = sentAt,
                    sortKey = now,
                    outgoing = false,
                    status = -1,
                    hops = hops,
                    seen = true,
                    kind = MessageKind.SOS,
                ),
            )
            if (row != -1L) touchConversation(NEARBY_CONVERSATION, null, "🆘 $nickname: ${body.ifEmpty { "needs help" }}", now, 1)
            row != -1L
        }
        if (!inserted) return
        _sos.update { list -> (listOf(SosAlert(packetIdHex, sender, nickname, body, now, hops, mine = false)) + list).take(5) }
        scope.launch {
            delay(SOS_BANNER_MILLIS)
            dismissSos(packetIdHex)
        }
        // Emergency alerts always notify (blocked senders were filtered above).
        notifier.showSos(nickname, body, hops)
        log.log("CHAT", "SOS from $nickname ($hops hops)")
    }

    suspend fun receiveDirectControl(sender: PeerId, kind: DmKind, messageIdHex: String, body: String) {
        if (isBlocked(sender)) return
        val conversationId = sender.toHex()
        val name = peers().firstOrNull { it.id == sender }?.name ?: nicknameOf(sender)
        when (kind) {
            DmKind.REACTION -> applyReaction(conversationId, messageIdHex, sender.toHex(), body)
            DmKind.RETRACT -> applyRetraction(conversationId, messageIdHex, sender.toHex())
            DmKind.WAVE -> {
                val open = openConversation.value == conversationId
                val conv = insertSystem(conversationId, conversationId, "$name waved at you 👋", unread = !open)
                if (!open && conv?.muted != true) notifier.showWave(sender, name)
                _waves.value = sender to clock.now()
            }
            DmKind.CHANNEL_INVITE -> {
                val invite = ChannelInvites.parse(body) ?: return
                val now = clock.now()
                val open = openConversation.value == conversationId
                val conv = db.tx {
                    val existing = db.conversations().get(conversationId)
                    val row = db.messages().insertOrIgnore(
                        MessageEntity(
                            id = messageIdHex,
                            conversationId = conversationId,
                            senderId = conversationId,
                            senderNickname = nicknameOf(sender),
                            body = body,
                            sentAt = now,
                            sortKey = now,
                            outgoing = false,
                            status = -1,
                            hops = 0,
                            seen = true,
                            kind = MessageKind.INVITE,
                            expiresAt = expiryFor(existing, now),
                        ),
                    )
                    val preview = "${if (invite.locked) "🔒 " else ""}Invite to #${invite.channel}"
                    if (row == -1L) null else touchConversation(conversationId, conversationId, preview, now, unreadDelta = if (open) 0 else 1)
                } ?: return
                if (!open && !conv.muted) notifier.showInvite(sender, name, invite.channel, hideContent)
            }
            DmKind.TIMER -> {
                val seconds = body.toLongOrNull()?.coerceIn(0, Disappearing.MAX_SECONDS) ?: return
                ensureConversation(conversationId, conversationId)
                db.conversations().setDisappear(conversationId, seconds)
                insertSystem(
                    conversationId,
                    conversationId,
                    if (seconds == 0L) "$name turned off disappearing messages" else "$name set messages to disappear after ${Disappearing.label(seconds)}",
                    unread = false,
                )
            }
            else -> Unit
        }
    }

    private val _waves = MutableStateFlow<Pair<PeerId, Long>?>(null)

    /** Latest wave received (for a haptic when that chat is open). */
    val waves: StateFlow<Pair<PeerId, Long>?> = _waves.asStateFlow()

    private suspend fun applyReaction(conversationId: String, targetHex: String, reactorHex: String, emoji: String) {
        val target = db.messages().get(targetHex) ?: return
        if (target.conversationId != conversationId || target.retracted) return
        if (emoji.isEmpty()) {
            db.reactions().delete(targetHex, reactorHex)
        } else if (Murmur.utf8Size(emoji) <= Murmur.MAX_EMOJI_BYTES) {
            db.reactions().upsert(ReactionEntity(targetHex, conversationId, reactorHex, emoji, clock.now()))
        }
    }

    private suspend fun applyRetraction(conversationId: String, targetHex: String, senderHex: String) {
        if (db.messages().retract(targetHex, conversationId, senderHex) > 0) {
            db.reactions().deleteForMessage(targetHex)
            refreshPreview(conversationId)
        }
    }

    suspend fun updateDelivery(messageIdHex: String, peer: PeerId, status: DeliveryStatus) {
        val now = clock.now()
        if (status == DeliveryStatus.DELIVERED || status == DeliveryStatus.READ) {
            db.messages().upgradeStatus(messageIdHex, peer.toHex(), status.ordinal)
            db.messages().setDeliveredAt(messageIdHex, peer.toHex(), now)
            if (status == DeliveryStatus.READ) db.messages().setReadAt(messageIdHex, peer.toHex(), now)
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

    // ================================================================== maintenance

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
            refreshPreview(NEARBY_CONVERSATION)
            db.reactions().deleteOrphans()
        }
    }

    /** Disappearing messages whose time is up. */
    suspend fun purgeExpired() {
        val removed = db.messages().deleteExpired(clock.now())
        if (removed > 0) {
            db.reactions().deleteOrphans()
            log.log("CHAT", "$removed disappearing messages removed")
        }
    }

    // ================================================================== helpers

    private fun expiryFor(conv: ConversationEntity?, now: Long): Long =
        conv?.disappearSeconds?.takeIf { it > 0 }?.let { now + it * 1000 } ?: 0L

    private suspend fun ensureConversation(id: String, peerId: String?) {
        if (db.conversations().get(id) == null) {
            db.conversations().upsert(ConversationEntity(id, peerId, "", clock.now(), 0))
        }
    }

    /** A call-history line in the DM ("📞 Outgoing call · 2:31", "📞 Missed call"). */
    suspend fun logCall(peer: PeerId, text: String, unread: Boolean) {
        val conversation = peer.toHex()
        insertSystem(conversation, conversation, text, unread = unread && openConversation.value != conversation)
    }

    /** A centered system line ("Luna waved at you 👋"). */
    private suspend fun insertSystem(conversationId: String, peerId: String?, text: String, unread: Boolean): ConversationEntity? =
        db.tx { insertSystemLocked(conversationId, peerId, text, unread) }

    private suspend fun insertSystemLocked(conversationId: String, peerId: String?, text: String, unread: Boolean): ConversationEntity {
        val now = clock.now()
        val conv = db.conversations().get(conversationId)
        db.messages().insertOrIgnore(
            MessageEntity(
                id = "sys-" + PacketId.random(random).toHex(),
                conversationId = conversationId,
                senderId = "",
                senderNickname = "",
                body = text,
                sentAt = now,
                sortKey = now,
                outgoing = false,
                status = -1,
                hops = 0,
                seen = true,
                kind = MessageKind.SYSTEM,
                expiresAt = expiryFor(conv, now),
            ),
        )
        return touchConversation(conversationId, peerId, text, now, if (unread) 1 else 0)
    }

    private suspend fun refreshPreview(conversationId: String) {
        val conv = db.conversations().get(conversationId) ?: return
        val latest = db.messages().latest(conversationId)
        val preview = when {
            latest == null -> ""
            latest.retracted -> "Message deleted"
            latest.kind == MessageKind.SYSTEM -> latest.body
            latest.kind == MessageKind.INVITE -> ChannelInvites.parse(latest.body)?.let { "Invite to #${it.channel}" }.orEmpty()
            latest.outgoing -> "You: ${Replies.parse(latest.body).text}"
            conversationId == NEARBY_CONVERSATION || channelOf(conversationId) != null -> "${latest.senderNickname}: ${Replies.parse(latest.body).text}"
            else -> Replies.parse(latest.body).text
        }
        db.conversations().upsert(conv.copy(preview = preview.take(PREVIEW_CHARS)))
    }

    private suspend fun touchConversation(id: String, peerId: String?, preview: String, time: Long, unreadDelta: Int): ConversationEntity {
        val existing = db.conversations().get(id)
        val updated = (existing ?: ConversationEntity(id, peerId, "", time, 0)).copy(
            peerId = existing?.peerId ?: peerId,
            preview = preview.take(PREVIEW_CHARS),
            lastActivity = maxOf(time, existing?.lastActivity ?: 0L),
            unread = (existing?.unread ?: 0) + unreadDelta,
        )
        db.conversations().upsert(updated)
        return updated
    }

    private fun roomTitle(conversationId: String): String = channelOf(conversationId)?.let { "#$it" } ?: "#nearby"

    private fun isBlocked(peer: PeerId): Boolean = peers().firstOrNull { it.id == peer }?.blocked == true

    private fun nicknameOf(peer: PeerId): String =
        peers().firstOrNull { it.id == peer }?.nickname ?: PeerRepository.fallbackName(peer)

    companion object {
        const val MESSAGE_WINDOW = 1_000
        const val PREVIEW_CHARS = 140
        const val TYPING_VISIBLE_MILLIS = 5_000L
        const val PENDING_EXPIRY_MILLIS = 24 * 60 * 60 * 1000L
        const val NEARBY_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        const val SOS_INTERVAL_MILLIS = 2 * 60 * 1000L
        const val SOS_BANNER_MILLIS = 30 * 60 * 1000L
        const val SOS_MAX_CHARS = 200
        const val WAVE_INTERVAL_MILLIS = 10_000L
        const val NEARBY_CHANNEL_MILLIS = 30 * 60_000L
        const val CHANNEL_HINT_MILLIS = 10 * 60_000L
        val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    }
}

/** What the chat repository needs from demo mode (kept narrow so demo never touches the mesh). */
interface DemoGateway {
    val isEnabled: Boolean
    fun isDemoPeer(peer: PeerId): Boolean
    fun onOutgoingDirect(peer: PeerId, messageIdHex: String, body: String)
    fun onOutgoingNearby(body: String)
    fun onOutgoingChannel(channel: String, body: String) {}
    fun onReaction(peer: PeerId, messageIdHex: String, emoji: String) {}
    fun onWave(peer: PeerId) {}
}
