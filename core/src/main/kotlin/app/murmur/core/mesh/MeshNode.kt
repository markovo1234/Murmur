package app.murmur.core.mesh

import app.murmur.core.Clock
import app.murmur.core.Murmur
import app.murmur.core.RandomSource
import app.murmur.core.crypto.ChannelCrypto
import app.murmur.core.crypto.Identity
import app.murmur.core.protocol.DecodeResult
import app.murmur.core.protocol.DmContent
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.Packet
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.Payload
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.PrivateMessages
import app.murmur.core.protocol.RoomContent
import app.murmur.core.protocol.RoomKind
import app.murmur.core.protocol.VerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The mesh: routing, dedupe, TTL, relaying, announces, DM delivery tracking, retries and the pending queue.
 *
 * All state is confined to [scope]'s dispatcher, which MUST be single-threaded (e.g.
 * `Dispatchers.Default.limitedParallelism(1)` or a test dispatcher). Public suspend functions hop onto it.
 * The node reaches transports only through [Link]s and the [LinkEvent] flow given to [start].
 */
class MeshNode(
    val identity: Identity,
    profile: Profile,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val random: RandomSource,
    private val config: MeshConfig = MeshConfig(),
    private val tracer: MeshTracer = MeshTracer.None,
    private val log: (String) -> Unit = {},
) {
    val myId: PeerId = identity.peerId

    private val confined: CoroutineContext = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

    private var profile: Profile = profile
    private var relayEnabled = true
    private var background = false
    private var blocked: Set<PeerId> = emptySet()
    private var channelKeys: Map<String, ByteArray> = emptyMap()
    private var powerSave = false

    private class LinkState(val link: Link) {
        var peerId: PeerId? = null
    }

    private class PeerRecord(val id: PeerId) {
        var nickname: String? = null
        var emoji: String? = null
        var colorIndex = 0
        var signingKey: ByteArray? = null
        var agreementKey: ByteArray? = null
        var lastHeard = NEVER
        var hops = 0
        var departed = false
    }

    private class OutgoingDm(val to: PeerId, val messageId: MessageId, val body: String, val createdAt: Long) {
        var status: DeliveryStatus? = null
        var resends = 0
        var timer: Job? = null
    }

    private val links = LinkedHashMap<String, LinkState>()
    private val peerRecords = HashMap<PeerId, PeerRecord>()
    private val seenPackets = BoundedSet<PacketId>(config.dedupeCapacity)
    private val seenMessages = BoundedSet<Pair<PeerId, MessageId>>(config.dedupeCapacity)
    private val outgoing = LinkedHashMap<MessageId, OutgoingDm>()
    private val lastTypingSent = HashMap<PeerId, Long>()

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = Int.MAX_VALUE)

    /** Messages, receipts, typing and link identification. Subscribe before [start]. */
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    private val _peers = MutableStateFlow<Map<PeerId, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<PeerId, PeerInfo>> = _peers.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private val _linkPeers = MutableStateFlow<Map<String, PeerId?>>(emptyMap())

    /** Ready links and the peer on each (null until its first direct ANNOUNCE). */
    val linkPeers: StateFlow<Map<String, PeerId?>> = _linkPeers.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private var announceJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    fun start(linkEvents: Flow<LinkEvent>) {
        check(jobs.isEmpty()) { "MeshNode already started" }
        log("mesh start as $myId")
        jobs += scope.launch { linkEvents.collect { onLinkEvent(it) } }
        jobs += scope.launch {
            while (isActive) {
                delay(config.statusTick)
                tick()
            }
        }
        restartAnnounceLoop()
    }

    /** Floods LEAVE on every link. Call before tearing the transport down. */
    suspend fun sendLeave() = onNode {
        if (links.isNotEmpty()) {
            originate(PacketCodec.create(identity.signing, Payload.Leave, PeerId.BROADCAST, Murmur.INITIAL_TTL, newPacketId(), clock.now()))
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        announceJob?.cancel()
        announceJob = null
        outgoing.values.forEach { it.timer?.cancel() }
        log("mesh stop")
    }

    // ---------------------------------------------------------------- public API

    suspend fun sendPublic(text: String, ttl: Int = Murmur.INITIAL_TTL): PacketId = onNode {
        val packet = PacketCodec.create(
            identity.signing,
            Payload.Public(profile.nickname, text),
            PeerId.BROADCAST,
            ttl,
            newPacketId(),
            clock.now(),
        )
        originate(packet)
        packet.packetId
    }

    /**
     * Queues a DM. Status goes Pending → Sending → Sent → Delivered → Read, or Failed. Calling it again with
     * the same [messageId] (manual retry) restarts delivery.
     */
    suspend fun sendDirectMessage(to: PeerId, messageId: MessageId, body: String, createdAt: Long? = null) = onNode {
        require(to != myId && !to.isBroadcast) { "bad recipient" }
        require(body.isNotEmpty() && Murmur.utf8Size(body) <= Murmur.MAX_TEXT_BYTES) { "bad body" }
        outgoing.remove(messageId)?.timer?.cancel()
        val dm = OutgoingDm(to, messageId, body, createdAt ?: clock.now())
        outgoing[messageId] = dm
        attempt(dm, resend = false)
    }

    /** At most one per [MeshConfig.typingInterval] per peer; never queued. */
    suspend fun sendTyping(to: PeerId) = onNode {
        val now = clock.now()
        val last = lastTypingSent[to]
        if (last != null && now - last < config.typingInterval) return@onNode
        val rec = peerRecords[to] ?: return@onNode
        if (!isReachable(rec, now)) return@onNode
        lastTypingSent[to] = now
        sendControl(to, DmKind.TYPING, MessageId.random(random))
        Unit
    }

    /** Returns false if the receipt couldn't be sent (no key or no links). Receipts are not queued. */
    suspend fun sendReadReceipt(to: PeerId, messageId: MessageId): Boolean = onNode {
        sendControl(to, DmKind.READ, messageId)
    }

    suspend fun updateProfile(newProfile: Profile) = onNode {
        require(newProfile.isValid) { "invalid profile" }
        profile = newProfile
        floodAnnounce()
    }

    suspend fun setRelayEnabled(enabled: Boolean) = onNode {
        relayEnabled = enabled
        log("relay ${if (enabled) "on" else "off"}")
    }

    /** Background mode announces every 60 s instead of 30 s. */
    suspend fun setBackground(isBackground: Boolean) = onNode {
        if (background != isBackground) {
            background = isBackground
            if (jobs.isNotEmpty()) restartAnnounceLoop()
        }
    }

    /** Blocked peers' packets are still relayed, but never surfaced as messages or typing. */
    suspend fun setBlocked(ids: Set<PeerId>) = onNode { blocked = ids.toSet() }

    /** Keys of the password-protected channels this user joined (name → 32-byte key). */
    suspend fun setChannelKeys(keys: Map<String, ByteArray>) = onNode { channelKeys = keys.toMap() }

    /** Battery saver: announce every 2 minutes in the background. */
    suspend fun setPowerSave(on: Boolean) = onNode {
        if (powerSave != on) {
            powerSave = on
            if (jobs.isNotEmpty() && background) restartAnnounceLoop()
        }
    }

    /**
     * Sends a ROOM packet to #nearby ([channel] = "") or a named channel, encrypted when [key] is given.
     * Returns its packetId, which identifies the message for replies, reactions and retractions.
     */
    suspend fun sendRoom(
        channel: String,
        kind: RoomKind,
        body: String,
        target: PacketId? = null,
        key: ByteArray? = null,
        ttl: Int = Murmur.INITIAL_TTL,
    ): PacketId = onNode {
        val content = RoomContent(kind, profile.nickname, target, body).encode()
        val packetId = newPacketId()
        val timestamp = clock.now()
        val payloadBody = if (key != null) {
            ChannelCrypto.seal(key, content, ChannelCrypto.aad(packetId.toBytes(), myId.toBytes(), timestamp, channel), random)
        } else {
            content
        }
        val packet = PacketCodec.create(
            identity.signing,
            Payload.Room(channel, key != null, payloadBody),
            PeerId.BROADCAST,
            ttl,
            packetId,
            timestamp,
        )
        originate(packet)
        packetId
    }

    /**
     * Sends a reaction, retraction, wave or disappearing-messages timer. Not queued and not retried:
     * returns false if the peer's key is unknown or there are no links.
     */
    suspend fun sendDirectControl(to: PeerId, kind: DmKind, messageId: MessageId, body: String = ""): Boolean = onNode {
        require(kind == DmKind.REACTION || kind == DmKind.RETRACT || kind == DmKind.WAVE || kind == DmKind.TIMER) { "not a control kind" }
        sendControl(to, kind, messageId, body)
    }

    // ---------------------------------------------------------------- link events

    private fun onLinkEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.Up -> onLinkUp(event.link)
            is LinkEvent.Received -> onPacketBytes(event.linkId, event.bytes)
            is LinkEvent.Down -> onLinkDown(event.linkId)
        }
    }

    private fun onLinkUp(link: Link) {
        if (links.containsKey(link.id)) return
        links[link.id] = LinkState(link)
        log("link up ${link.id} (${links.size} links)")
        publishLinks()
        originate(buildAnnounce(), only = link)
    }

    private fun onLinkDown(linkId: String) {
        val state = links.remove(linkId) ?: return
        log("link down $linkId peer=${state.peerId} (${links.size} links)")
        publishLinks()
        publishPeers()
    }

    private fun onPacketBytes(linkId: String, bytes: ByteArray) {
        val state = links[linkId] ?: return
        val packet = when (val result = PacketCodec.decode(bytes)) {
            is DecodeResult.Ok -> result.packet
            is DecodeResult.Error -> {
                drop(DropReason.MALFORMED, linkId, result.reason)
                return
            }
        }
        if (packet.packetId in seenPackets) {
            drop(DropReason.DUPLICATE, linkId, packet.toString())
            return
        }
        when (PacketCodec.verify(packet)) {
            VerifyResult.OK -> Unit
            VerifyResult.SENDER_KEY_MISMATCH -> {
                drop(DropReason.SENDER_KEY_MISMATCH, linkId, packet.toString())
                return
            }
            VerifyResult.BAD_SIGNATURE -> {
                drop(DropReason.BAD_SIGNATURE, linkId, packet.toString())
                return
            }
        }
        val now = clock.now()
        if (packet.timestamp > now + config.maxFutureSkew || packet.timestamp < now - config.maxAge) {
            drop(DropReason.STALE_TIMESTAMP, linkId, packet.toString())
            return
        }
        seenPackets.add(packet.packetId)
        if (packet.senderId == myId) return
        _stats.update { it.copy(received = it.received + 1) }
        relay(packet, linkId)
        deliver(packet, state, linkId, now)
    }

    private fun relay(packet: Packet, fromLinkId: String) {
        if (packet.recipientId == myId || !relayEnabled || packet.ttl <= 1) return
        val bytes = packet.withTtl(packet.ttl - 1).rawBytes()
        var count = 0
        for ((id, state) in links) {
            if (id != fromLinkId && state.link.send(bytes)) count++
        }
        if (count > 0) {
            _stats.update { it.copy(relayed = it.relayed + 1) }
            tracer.onRelayed(packet, count)
        }
    }

    private fun deliver(packet: Packet, state: LinkState, linkId: String, now: Long) {
        val sender = packet.senderId
        val rec = peerRecords.getOrPut(sender) { PeerRecord(sender) }
        rec.lastHeard = now
        rec.hops = packet.hops
        rec.departed = false
        rec.signingKey = packet.senderKey

        when (val payload = packet.payload) {
            is Payload.Announce -> {
                rec.nickname = payload.nickname
                rec.emoji = payload.emoji
                rec.colorIndex = payload.colorIndex
                rec.agreementKey = payload.agreementKey
                if (packet.ttl == Murmur.INITIAL_TTL && state.peerId == null) {
                    state.peerId = sender
                    log("link ${state.link.id} is $sender (${payload.nickname})")
                    state.link.onPeerIdentified(sender)
                    publishLinks()
                    emit(MeshEvent.LinkIdentified(state.link.id, sender))
                }
                tracer.onDelivered(packet)
            }
            is Payload.Public -> {
                rec.nickname = payload.nickname
                tracer.onDelivered(packet)
                if (sender !in blocked) {
                    emit(MeshEvent.PublicMessage(packet.packetId, sender, payload.nickname, payload.text, packet.timestamp, packet.hops))
                }
            }
            is Payload.Private -> if (packet.recipientId == myId) handlePrivate(packet, rec, linkId)
            Payload.Leave -> {
                rec.departed = true
                log("$sender left")
            }
            is Payload.Room -> deliverRoom(packet, payload, rec)
            is Payload.Unknown -> Unit // relayed above; nothing to show
        }
        publishPeers()
        if (!rec.departed) flushPending(sender)
    }

    private fun deliverRoom(packet: Packet, payload: Payload.Room, rec: PeerRecord) {
        val sender = packet.senderId
        val plain = if (payload.encrypted) {
            val key = channelKeys[payload.channel] ?: return // not a member: relayed, not shown
            ChannelCrypto.open(key, payload.body, ChannelCrypto.aad(packet.packetId.toBytes(), sender.toBytes(), packet.timestamp, payload.channel))
                ?: return
        } else {
            payload.body
        }
        val content = RoomContent.decode(plain) ?: return
        rec.nickname = content.nickname
        tracer.onDelivered(packet)
        if (sender in blocked) return
        emit(
            MeshEvent.RoomMessage(
                packetId = packet.packetId,
                senderId = sender,
                channel = payload.channel,
                encrypted = payload.encrypted,
                kind = content.kind,
                nickname = content.nickname,
                target = content.target,
                body = content.body,
                timestamp = packet.timestamp,
                hops = packet.hops,
            ),
        )
    }

    private fun handlePrivate(packet: Packet, rec: PeerRecord, linkId: String) {
        val content = PrivateMessages.open(packet, identity)
        if (content == null) {
            drop(DropReason.UNDECRYPTABLE, linkId, packet.toString())
            return
        }
        rec.agreementKey = content.senderAgreementKey
        tracer.onDelivered(packet)
        val sender = packet.senderId
        when (content.kind) {
            DmKind.TEXT -> {
                // DELIVERED for every copy, so a sender whose receipt got lost stops retrying.
                sendControl(sender, DmKind.DELIVERED, content.messageId)
                val firstTime = seenMessages.add(sender to content.messageId)
                if (firstTime && sender !in blocked) {
                    emit(MeshEvent.DirectMessage(content.messageId, sender, content.body, packet.timestamp, packet.hops))
                }
            }
            DmKind.DELIVERED -> onReceipt(sender, content.messageId, DeliveryStatus.DELIVERED)
            DmKind.READ -> onReceipt(sender, content.messageId, DeliveryStatus.READ)
            DmKind.TYPING -> if (sender !in blocked) emit(MeshEvent.Typing(sender))
            DmKind.REACTION, DmKind.RETRACT, DmKind.WAVE, DmKind.TIMER -> if (sender !in blocked) {
                emit(MeshEvent.DirectControl(sender, content.kind, content.messageId, content.body, packet.timestamp))
            }
        }
    }

    private fun onReceipt(sender: PeerId, messageId: MessageId, status: DeliveryStatus) {
        val dm = outgoing[messageId]
        if (dm != null) {
            if (dm.to != sender) return
            dm.timer?.cancel()
            outgoing.remove(messageId)
            dm.status = status
        }
        emit(MeshEvent.Delivery(messageId, sender, status))
    }

    // ---------------------------------------------------------------- DM delivery

    private fun attempt(dm: OutgoingDm, resend: Boolean) {
        dm.timer?.cancel()
        dm.timer = null
        val now = clock.now()
        if (now - dm.createdAt >= config.pendingExpiry) {
            finish(dm, DeliveryStatus.FAILED)
            return
        }
        val rec = peerRecords[dm.to]
        val key = rec?.agreementKey
        if (rec == null || key == null || !isReachable(rec, now)) {
            toPending(dm)
            return
        }
        if (!resend) setStatus(dm, DeliveryStatus.SENDING)
        val content = DmContent(DmKind.TEXT, dm.messageId, identity.agreement.publicKey, dm.body)
        val packet = PrivateMessages.create(identity, dm.to, key, content, Murmur.INITIAL_TTL, newPacketId(), now, random)
        if (packet == null) {
            log("unusable key for ${dm.to}")
            finish(dm, DeliveryStatus.FAILED)
            return
        }
        if (originate(packet) == 0) {
            toPending(dm)
            return
        }
        setStatus(dm, DeliveryStatus.SENT)
        dm.timer = scope.launch {
            delay(config.ackTimeout)
            onAckTimeout(dm)
        }
    }

    private fun onAckTimeout(dm: OutgoingDm) {
        if (outgoing[dm.messageId] !== dm) return
        dm.timer = null
        val rec = peerRecords[dm.to]
        if (rec == null || !isReachable(rec, clock.now())) {
            toPending(dm)
            return
        }
        if (dm.resends >= config.maxResends) {
            log("DM ${dm.messageId} to ${dm.to} failed after ${dm.resends} resends")
            finish(dm, DeliveryStatus.FAILED)
            return
        }
        dm.resends++
        log("resend DM ${dm.messageId} to ${dm.to} (${dm.resends}/${config.maxResends})")
        attempt(dm, resend = true)
    }

    private fun toPending(dm: OutgoingDm) {
        dm.resends = 0
        setStatus(dm, DeliveryStatus.PENDING)
    }

    private fun flushPending(peer: PeerId) {
        if (outgoing.isEmpty()) return
        val due = outgoing.values.filter { it.to == peer && it.status == DeliveryStatus.PENDING }
        for (dm in due) attempt(dm, resend = false)
    }

    private fun setStatus(dm: OutgoingDm, status: DeliveryStatus) {
        if (dm.status == status) return
        dm.status = status
        emit(MeshEvent.Delivery(dm.messageId, dm.to, status))
    }

    private fun finish(dm: OutgoingDm, status: DeliveryStatus) {
        dm.timer?.cancel()
        outgoing.remove(dm.messageId)
        setStatus(dm, status)
    }

    private fun sendControl(to: PeerId, kind: DmKind, messageId: MessageId, body: String = ""): Boolean {
        val key = peerRecords[to]?.agreementKey ?: return false
        val content = DmContent(kind, messageId, identity.agreement.publicKey, body)
        val packet = PrivateMessages.create(identity, to, key, content, Murmur.INITIAL_TTL, newPacketId(), clock.now(), random)
            ?: return false
        return originate(packet) > 0
    }

    // ---------------------------------------------------------------- periodic work

    private fun restartAnnounceLoop() {
        announceJob?.cancel()
        val interval = when {
            !background -> config.announceIntervalForeground
            powerSave -> config.announceIntervalPowerSave
            else -> config.announceIntervalBackground
        }
        announceJob = scope.launch {
            while (isActive) {
                delay(interval)
                floodAnnounce()
            }
        }
    }

    private fun floodAnnounce() {
        if (links.isNotEmpty()) originate(buildAnnounce())
    }

    private fun tick() {
        val now = clock.now()
        if (outgoing.isNotEmpty()) {
            outgoing.values
                .filter { it.status == DeliveryStatus.PENDING && now - it.createdAt >= config.pendingExpiry }
                .forEach { finish(it, DeliveryStatus.FAILED) }
        }
        // Forget peers silent for a day that nothing is waiting on.
        val stale = peerRecords.values.filter { rec ->
            rec.lastHeard != NEVER && now - rec.lastHeard > config.pendingExpiry &&
                links.values.none { it.peerId == rec.id } && outgoing.values.none { it.to == rec.id }
        }
        stale.forEach { peerRecords.remove(it.id) }
        publishPeers()
    }

    // ---------------------------------------------------------------- helpers

    private fun isReachable(rec: PeerRecord, now: Long): Boolean = statusOf(rec, now) != PeerStatus.OFFLINE

    private fun statusOf(rec: PeerRecord, now: Long): PeerStatus = when {
        rec.departed -> PeerStatus.OFFLINE
        links.values.any { it.peerId == rec.id } -> PeerStatus.NEARBY
        rec.lastHeard != NEVER && now - rec.lastHeard < config.offlineAfter -> PeerStatus.VIA_MESH
        else -> PeerStatus.OFFLINE
    }

    private fun originate(packet: Packet, only: Link? = null): Int {
        seenPackets.add(packet.packetId)
        _stats.update { it.copy(sent = it.sent + 1) }
        val bytes = packet.rawBytes()
        return if (only != null) {
            if (only.send(bytes)) 1 else 0
        } else {
            links.values.count { it.link.send(bytes) }
        }
    }

    private fun buildAnnounce(): Packet = PacketCodec.create(
        identity.signing,
        Payload.Announce(profile.nickname, profile.emoji, profile.colorIndex, identity.agreement.publicKey),
        PeerId.BROADCAST,
        Murmur.INITIAL_TTL,
        newPacketId(),
        clock.now(),
    )

    private fun newPacketId(): PacketId = PacketId.random(random)

    private fun drop(reason: DropReason, linkId: String, detail: String) {
        _stats.update {
            if (reason == DropReason.DUPLICATE) it.copy(droppedDuplicate = it.droppedDuplicate + 1)
            else it.copy(droppedInvalid = it.droppedInvalid + 1)
        }
        tracer.onDropped(reason, linkId, detail)
        if (reason != DropReason.DUPLICATE) log("dropped $reason on $linkId: $detail")
    }

    private fun emit(event: MeshEvent) {
        _events.tryEmit(event)
    }

    private fun publishPeers() {
        val now = clock.now()
        _peers.value = peerRecords.values.associate { rec ->
            rec.id to PeerInfo(
                id = rec.id,
                nickname = rec.nickname,
                emoji = rec.emoji,
                colorIndex = rec.colorIndex,
                signingKey = rec.signingKey,
                agreementKey = rec.agreementKey,
                lastHeard = rec.lastHeard,
                hops = rec.hops,
                status = statusOf(rec, now),
                linkIds = links.filterValues { it.peerId == rec.id }.keys.toSet(),
            )
        }
    }

    private fun publishLinks() {
        _linkPeers.value = links.mapValues { it.value.peerId }
    }

    private suspend fun <T> onNode(block: () -> T): T = withContext(confined) { block() }

    private companion object {
        const val NEVER = 0L
    }
}
