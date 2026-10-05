import Foundation

/// The mesh: routing, dedupe, TTL, relaying, announces, DM delivery tracking, retries and the pending
/// queue. A port of the Kotlin `MeshNode`.
///
/// Not thread-safe: every method, every `scheduler` action and every callback runs on one serial queue
/// (the Bluetooth transport delivers its events on the same queue). The node reaches transports only
/// through `MeshLink`s and `linkUp` / `received` / `linkDown`.
public final class MeshNode {
    public let identity: Identity
    public var myId: PeerId { identity.peerId }

    private let clock: Clock
    private let random: RandomSource
    private let scheduler: Scheduler
    private let config: MeshConfig
    private let log: (String) -> Void

    private var profile: Profile
    private var relayEnabled = true
    private var background = false
    private var blocked = Set<PeerId>()
    private var channelKeys: [String: [UInt8]] = [:]
    private var powerSave = false

    private final class LinkState {
        let link: MeshLink
        var peerId: PeerId?
        init(_ link: MeshLink) { self.link = link }
    }

    private final class PeerRecord {
        let id: PeerId
        var nickname: String?
        var emoji: String?
        var colorIndex = 0
        var signingKey: [UInt8]?
        var agreementKey: [UInt8]?
        var lastHeard: Int64 = MeshNode.never
        var hops = 0
        var departed = false
        init(_ id: PeerId) { self.id = id }
    }

    private final class OutgoingDm {
        let to: PeerId
        let messageId: MessageId
        let body: String
        let createdAt: Int64
        var status: DeliveryStatus?
        var resends = 0
        var timer: Cancellable?

        init(to: PeerId, messageId: MessageId, body: String, createdAt: Int64) {
            self.to = to
            self.messageId = messageId
            self.body = body
            self.createdAt = createdAt
        }
    }

    private struct SeenMessage: Hashable {
        let sender: PeerId
        let id: MessageId
    }

    private struct ChannelKey: Hashable {
        let channel: String
        let encrypted: Bool
    }

    private var links: [String: LinkState] = [:]
    private var linkOrder: [String] = []
    private var peerRecords: [PeerId: PeerRecord] = [:]
    private var seenPackets: BoundedSet<PacketId>
    private var seenMessages: BoundedSet<SeenMessage>
    private var outgoing: [MessageId: OutgoingDm] = [:]
    private var outgoingOrder: [MessageId] = []
    private var lastTypingSent: [PeerId: Int64] = [:]
    private var channelSeenAt: [ChannelKey: Int64] = [:]

    private var started = false
    private var tickTimer: Cancellable?
    private var announceTimer: Cancellable?

    /// Messages, receipts, typing and link identification.
    public var onEvent: ((MeshEvent) -> Void)?
    public var onPeersChanged: (([PeerId: PeerInfo]) -> Void)?
    /// Ready links and the peer on each (nil until its first direct ANNOUNCE).
    public var onLinksChanged: (([String: PeerId?]) -> Void)?
    public var onStatsChanged: ((MeshStats) -> Void)?

    public private(set) var peers: [PeerId: PeerInfo] = [:]
    public private(set) var stats = MeshStats() {
        didSet { onStatsChanged?(stats) }
    }

    public init(identity: Identity, profile: Profile, clock: Clock, random: RandomSource, scheduler: Scheduler,
                config: MeshConfig = MeshConfig(), log: @escaping (String) -> Void = { _ in }) {
        self.identity = identity
        self.profile = profile
        self.clock = clock
        self.random = random
        self.scheduler = scheduler
        self.config = config
        self.log = log
        seenPackets = BoundedSet(capacity: config.dedupeCapacity)
        seenMessages = BoundedSet(capacity: config.dedupeCapacity)
    }

    // MARK: lifecycle

    public func start() {
        precondition(!started, "MeshNode already started")
        started = true
        log("mesh start as \(myId)")
        scheduleTick()
        restartAnnounceLoop()
    }

    /// Floods LEAVE on every link. Call before tearing the transport down.
    public func sendLeave() {
        guard !links.isEmpty, let p = try? PacketCodec.create(signing: identity.signing, payload: .leave, recipientId: .broadcast,
                                                              ttl: Murmur.initialTtl, packetId: newPacketId(), timestamp: clock.now()) else { return }
        originate(p)
    }

    public func stop() {
        started = false
        tickTimer?.cancel()
        tickTimer = nil
        announceTimer?.cancel()
        announceTimer = nil
        outgoing.values.forEach { $0.timer?.cancel() }
        log("mesh stop")
    }

    // MARK: public API

    @discardableResult
    public func sendPublic(_ text: String, ttl: Int = Murmur.initialTtl) throws -> PacketId {
        let packet = try PacketCodec.create(signing: identity.signing, payload: .publicMessage(nickname: profile.nickname, text: text),
                                            recipientId: .broadcast, ttl: ttl, packetId: newPacketId(), timestamp: clock.now())
        originate(packet)
        return packet.packetId
    }

    /// Queues a DM. Status goes pending → sending → sent → delivered → read, or failed. Calling it again
    /// with the same messageId (manual retry) restarts delivery.
    public func sendDirectMessage(to: PeerId, messageId: MessageId, body: String, createdAt: Int64? = nil) throws {
        guard to != myId, !to.isBroadcast else { throw PacketError("bad recipient") }
        guard !body.isEmpty, Murmur.utf8Size(body) <= Murmur.maxTextBytes else { throw PacketError("bad body") }
        removeOutgoing(messageId)?.timer?.cancel()
        let dm = OutgoingDm(to: to, messageId: messageId, body: body, createdAt: createdAt ?? clock.now())
        addOutgoing(dm)
        attempt(dm, resend: false)
    }

    /// At most one per `typingInterval` per peer; never queued.
    public func sendTyping(to: PeerId) {
        let now = clock.now()
        if let last = lastTypingSent[to], now - last < config.typingInterval { return }
        guard let rec = peerRecords[to], isReachable(rec, now) else { return }
        lastTypingSent[to] = now
        sendControl(to, .typing, MessageId.random(random))
    }

    /// False if the receipt couldn't be sent (no key or no links). Receipts are not queued.
    @discardableResult
    public func sendReadReceipt(to: PeerId, messageId: MessageId) -> Bool {
        sendControl(to, .read, messageId)
    }

    public func updateProfile(_ newProfile: Profile) throws {
        guard newProfile.isValid else { throw PacketError("invalid profile") }
        profile = newProfile
        floodAnnounce()
    }

    public func setRelayEnabled(_ enabled: Bool) {
        relayEnabled = enabled
        log("relay \(enabled ? "on" : "off")")
    }

    /// Background mode announces every 60 s instead of 30 s.
    public func setBackground(_ isBackground: Bool) {
        if background != isBackground {
            background = isBackground
            if started { restartAnnounceLoop() }
        }
    }

    /// Blocked peers' packets are still relayed, but never surfaced as messages or typing.
    public func setBlocked(_ ids: Set<PeerId>) { blocked = ids }

    /// Keys of the password-protected channels this user joined (name → 32-byte key).
    public func setChannelKeys(_ keys: [String: [UInt8]]) { channelKeys = keys }

    /// Battery saver: announce every 2 minutes in the background.
    public func setPowerSave(_ on: Bool) {
        if powerSave != on {
            powerSave = on
            if started && background { restartAnnounceLoop() }
        }
    }

    /// Sends a ROOM packet to #nearby (channel "") or a named channel, encrypted when `key` is given.
    /// Returns its packetId, which identifies the message for replies, reactions and retractions.
    @discardableResult
    public func sendRoom(channel: String, kind: RoomKind, body: String, target: PacketId? = nil, key: [UInt8]? = nil,
                         ttl: Int = Murmur.initialTtl) throws -> PacketId {
        let content = try RoomContent(kind: kind, nickname: profile.nickname, target: target, body: body).encode()
        let packetId = newPacketId()
        let timestamp = clock.now()
        let body: [UInt8]
        if let key {
            body = ChannelCrypto.seal(key: key, plaintext: content,
                                      aad: ChannelCrypto.aad(packetId: packetId.toBytes(), senderId: myId.toBytes(), timestamp: timestamp, channel: channel),
                                      random: random)
        } else {
            body = content
        }
        let packet = try PacketCodec.create(signing: identity.signing, payload: .room(channel: channel, encrypted: key != nil, body: body),
                                            recipientId: .broadcast, ttl: ttl, packetId: packetId, timestamp: timestamp)
        originate(packet)
        return packetId
    }

    /// Sends a reaction, retraction, wave, timer, call signal or invite. Not queued and not retried:
    /// false if the peer's key is unknown or there are no links.
    @discardableResult
    public func sendDirectControl(to: PeerId, kind: DmKind, messageId: MessageId, body: String = "") -> Bool {
        precondition(kind.isControl, "not a control kind")
        return sendControl(to, kind, messageId, body)
    }

    /// Hop distance to `peer` as far as the mesh knows (1 = direct link), or nil if unknown.
    public func hopsTo(_ peer: PeerId) -> Int? {
        guard let rec = peerRecords[peer] else { return nil }
        if links.values.contains(where: { $0.peerId == peer }) { return 1 }
        return rec.hops > 0 ? rec.hops : nil
    }

    // MARK: link events

    public func linkUp(_ link: MeshLink) {
        if links[link.id] != nil { return }
        links[link.id] = LinkState(link)
        linkOrder.append(link.id)
        log("link up \(link.id) (\(links.count) links)")
        publishLinks()
        if let a = buildAnnounce() { originate(a, only: link) }
    }

    public func linkDown(_ linkId: String) {
        guard let state = links.removeValue(forKey: linkId) else { return }
        linkOrder.removeAll { $0 == linkId }
        log("link down \(linkId) peer=\(state.peerId.map { "\($0)" } ?? "nil") (\(links.count) links)")
        publishLinks()
        publishPeers()
    }

    public func received(linkId: String, bytes: [UInt8]) {
        guard let state = links[linkId] else { return }
        let packet: Packet
        switch PacketCodec.decode(bytes) {
        case let .ok(p): packet = p
        case let .error(reason):
            drop(.malformed, linkId, reason)
            return
        }
        if seenPackets.contains(packet.packetId) {
            drop(.duplicate, linkId, packet.description)
            return
        }
        switch PacketCodec.verify(packet) {
        case .ok: break
        case .senderKeyMismatch:
            drop(.senderKeyMismatch, linkId, packet.description)
            return
        case .badSignature:
            drop(.badSignature, linkId, packet.description)
            return
        }
        let now = clock.now()
        if packet.timestamp > now + config.maxFutureSkew || packet.timestamp < now - config.maxAge {
            drop(.staleTimestamp, linkId, packet.description)
            return
        }
        seenPackets.insert(packet.packetId)
        if packet.senderId == myId { return }
        stats.received += 1
        relay(packet, from: linkId)
        if case let .call(callId, seq, sealed) = packet.payload {
            deliverCall(packet, callId: callId, seq: seq, sealed: sealed, now: now)
        } else {
            deliver(packet, state: state, linkId: linkId, now: now)
        }
    }

    // MARK: delivery

    /// Call audio: ~12 packets a second, so it skips the peer bookkeeping other packets trigger.
    private func deliverCall(_ packet: Packet, callId: MessageId, seq: UInt32, sealed: [UInt8], now: Int64) {
        guard packet.recipientId == myId else { return }
        let sender = packet.senderId
        peerRecords[sender]?.lastHeard = now
        if blocked.contains(sender) { return }
        emit(.callAudio(senderId: sender, callId: callId, seq: seq, sealed: sealed))
    }

    private func relay(_ packet: Packet, from fromLinkId: String) {
        if packet.recipientId == myId || !relayEnabled || packet.ttl <= 1 { return }
        let bytes = packet.withTtl(packet.ttl - 1).raw
        var count = 0
        for id in linkOrder where id != fromLinkId {
            if links[id]?.link.send(bytes) == true { count += 1 }
        }
        if count > 0 && packet.type != .call { stats.relayed += 1 }
    }

    private func deliver(_ packet: Packet, state: LinkState, linkId: String, now: Int64) {
        let sender = packet.senderId
        let rec = peerRecords[sender] ?? {
            let r = PeerRecord(sender)
            peerRecords[sender] = r
            return r
        }()
        rec.lastHeard = now
        // Only announces reliably start at ttl 7; other packets may use a shorter reach.
        if case .announce = packet.payload { rec.hops = packet.hops } else if rec.hops == 0 { rec.hops = packet.hops }
        rec.departed = false
        rec.signingKey = packet.senderKey

        switch packet.payload {
        case let .announce(nickname, emoji, colorIndex, agreementKey):
            rec.nickname = nickname
            rec.emoji = emoji
            rec.colorIndex = colorIndex
            rec.agreementKey = agreementKey
            if packet.ttl == Murmur.initialTtl && state.peerId == nil {
                state.peerId = sender
                log("link \(state.link.id) is \(sender) (\(nickname))")
                state.link.onPeerIdentified(sender)
                publishLinks()
                emit(.linkIdentified(linkId: state.link.id, peerId: sender))
            }
        case let .publicMessage(nickname, text):
            rec.nickname = nickname
            if !blocked.contains(sender) {
                emit(.publicMessage(packetId: packet.packetId, senderId: sender, nickname: nickname, text: text,
                                    timestamp: packet.timestamp, hops: hopsOf(rec, packet)))
            }
        case .privateMessage:
            if packet.recipientId == myId { handlePrivate(packet, rec: rec, linkId: linkId) }
        case .leave:
            rec.departed = true
            log("\(sender) left")
        case let .room(channel, encrypted, body):
            deliverRoom(packet, channel: channel, encrypted: encrypted, body: body, rec: rec)
        case .unknown, .call:
            break // relayed above; nothing to show
        }
        publishPeers()
        if !rec.departed { flushPending(sender) }
    }

    private func deliverRoom(_ packet: Packet, channel: String, encrypted: Bool, body: [UInt8], rec: PeerRecord) {
        let sender = packet.senderId
        let plain: [UInt8]?
        if encrypted {
            plain = channelKeys[channel].flatMap { key in
                ChannelCrypto.open(key: key, sealed: body,
                                   aad: ChannelCrypto.aad(packetId: packet.packetId.toBytes(), senderId: sender.toBytes(), timestamp: packet.timestamp, channel: channel))
            }
        } else {
            plain = body
        }
        if !channel.isEmpty { noteChannel(channel, encrypted: encrypted, readable: plain != nil, sender: sender) }
        guard let plain, let content = RoomContent.decode(plain) else { return } // not a member: relayed, not shown
        rec.nickname = content.nickname
        if blocked.contains(sender) { return }
        emit(.roomMessage(MeshEvent.RoomMessage(
            packetId: packet.packetId, senderId: sender, channel: channel, encrypted: encrypted, kind: content.kind,
            nickname: content.nickname, target: content.target, body: content.body, timestamp: packet.timestamp,
            hops: hopsOf(rec, packet)
        )))
    }

    /// Channel discovery: rate-limited per channel and lock state.
    private func noteChannel(_ channel: String, encrypted: Bool, readable: Bool, sender: PeerId) {
        if blocked.contains(sender) { return }
        let now = clock.now()
        let key = ChannelKey(channel: channel, encrypted: encrypted)
        if let last = channelSeenAt[key], now - last < config.channelSeenInterval { return }
        if channelSeenAt.count > MeshNode.maxChannelsTracked { channelSeenAt.removeAll() }
        channelSeenAt[key] = now
        emit(.channelSeen(channel: channel, encrypted: encrypted, readable: readable, senderId: sender))
    }

    private func handlePrivate(_ packet: Packet, rec: PeerRecord, linkId: String) {
        guard let content = PrivateMessages.open(packet, me: identity) else {
            drop(.undecryptable, linkId, packet.description)
            return
        }
        rec.agreementKey = content.senderAgreementKey
        let sender = packet.senderId
        switch content.kind {
        case .text:
            // DELIVERED for every copy, so a sender whose receipt got lost stops retrying.
            sendControl(sender, .delivered, content.messageId)
            let firstTime = seenMessages.insert(SeenMessage(sender: sender, id: content.messageId))
            if firstTime && !blocked.contains(sender) {
                emit(.directMessage(messageId: content.messageId, senderId: sender, body: content.body,
                                    timestamp: packet.timestamp, hops: hopsOf(rec, packet)))
            }
        case .delivered: onReceipt(sender, content.messageId, .delivered)
        case .read: onReceipt(sender, content.messageId, .read)
        case .typing: if !blocked.contains(sender) { emit(.typing(peerId: sender)) }
        case .reaction, .retract, .wave, .timer, .callOffer, .callAnswer, .callEnd, .channelInvite:
            if !blocked.contains(sender) {
                emit(.directControl(senderId: sender, kind: content.kind, messageId: content.messageId, body: content.body, timestamp: packet.timestamp))
            }
        }
    }

    private func onReceipt(_ sender: PeerId, _ messageId: MessageId, _ status: DeliveryStatus) {
        if let dm = outgoing[messageId] {
            if dm.to != sender { return }
            dm.timer?.cancel()
            removeOutgoing(messageId)
            dm.status = status
        }
        emit(.delivery(messageId: messageId, peerId: sender, status: status))
    }

    // MARK: DM delivery

    private func attempt(_ dm: OutgoingDm, resend: Bool) {
        dm.timer?.cancel()
        dm.timer = nil
        let now = clock.now()
        if now - dm.createdAt >= config.pendingExpiry {
            finish(dm, .failed)
            return
        }
        guard let rec = peerRecords[dm.to], let key = rec.agreementKey, isReachable(rec, now) else {
            toPending(dm)
            return
        }
        if !resend { setStatus(dm, .sending) }
        let content = DmContent(kind: .text, messageId: dm.messageId, senderAgreementKey: identity.agreement.publicKey, body: dm.body)
        guard let packet = try? PrivateMessages.create(sender: identity, recipientId: dm.to, recipientAgreementKey: key, content: content,
                                                       ttl: Murmur.initialTtl, packetId: newPacketId(), timestamp: now, random: random) else {
            log("unusable key for \(dm.to)")
            finish(dm, .failed)
            return
        }
        if originate(packet) == 0 {
            toPending(dm)
            return
        }
        setStatus(dm, .sent)
        dm.timer = scheduler.schedule(afterMillis: config.ackTimeout) { [weak self, weak dm] in
            guard let self, let dm else { return }
            self.onAckTimeout(dm)
        }
    }

    private func onAckTimeout(_ dm: OutgoingDm) {
        guard outgoing[dm.messageId] === dm else { return }
        dm.timer = nil
        guard let rec = peerRecords[dm.to], isReachable(rec, clock.now()) else {
            toPending(dm)
            return
        }
        if dm.resends >= config.maxResends {
            log("DM \(dm.messageId) to \(dm.to) failed after \(dm.resends) resends")
            finish(dm, .failed)
            return
        }
        dm.resends += 1
        log("resend DM \(dm.messageId) to \(dm.to) (\(dm.resends)/\(config.maxResends))")
        attempt(dm, resend: true)
    }

    private func toPending(_ dm: OutgoingDm) {
        dm.resends = 0
        setStatus(dm, .pending)
    }

    private func flushPending(_ peer: PeerId) {
        if outgoing.isEmpty { return }
        let due = outgoingOrder.compactMap { outgoing[$0] }.filter { $0.to == peer && $0.status == .pending }
        for dm in due { attempt(dm, resend: false) }
    }

    private func setStatus(_ dm: OutgoingDm, _ status: DeliveryStatus) {
        if dm.status == status { return }
        dm.status = status
        emit(.delivery(messageId: dm.messageId, peerId: dm.to, status: status))
    }

    private func finish(_ dm: OutgoingDm, _ status: DeliveryStatus) {
        dm.timer?.cancel()
        removeOutgoing(dm.messageId)
        setStatus(dm, status)
    }

    private func addOutgoing(_ dm: OutgoingDm) {
        outgoing[dm.messageId] = dm
        outgoingOrder.append(dm.messageId)
    }

    @discardableResult
    private func removeOutgoing(_ id: MessageId) -> OutgoingDm? {
        guard let dm = outgoing.removeValue(forKey: id) else { return nil }
        outgoingOrder.removeAll { $0 == id }
        return dm
    }

    @discardableResult
    private func sendControl(_ to: PeerId, _ kind: DmKind, _ messageId: MessageId, _ body: String = "") -> Bool {
        guard let key = peerRecords[to]?.agreementKey else { return false }
        let content = DmContent(kind: kind, messageId: messageId, senderAgreementKey: identity.agreement.publicKey, body: body)
        guard let packet = try? PrivateMessages.create(sender: identity, recipientId: to, recipientAgreementKey: key, content: content,
                                                       ttl: Murmur.initialTtl, packetId: newPacketId(), timestamp: clock.now(), random: random) else {
            return false
        }
        return originate(packet) > 0
    }

    // MARK: periodic work

    private func scheduleTick() {
        tickTimer = scheduler.schedule(afterMillis: config.statusTick) { [weak self] in
            guard let self, self.started else { return }
            self.tick()
            self.scheduleTick()
        }
    }

    private func restartAnnounceLoop() {
        announceTimer?.cancel()
        let interval: Int64
        if !background {
            interval = config.announceIntervalForeground
        } else if powerSave {
            interval = config.announceIntervalPowerSave
        } else {
            interval = config.announceIntervalBackground
        }
        scheduleAnnounce(interval)
    }

    private func scheduleAnnounce(_ interval: Int64) {
        announceTimer = scheduler.schedule(afterMillis: interval) { [weak self] in
            guard let self, self.started else { return }
            self.floodAnnounce()
            self.scheduleAnnounce(interval)
        }
    }

    private func floodAnnounce() {
        if !links.isEmpty, let a = buildAnnounce() { originate(a) }
    }

    private func tick() {
        let now = clock.now()
        if !outgoing.isEmpty {
            outgoingOrder.compactMap { outgoing[$0] }
                .filter { $0.status == .pending && now - $0.createdAt >= config.pendingExpiry }
                .forEach { finish($0, .failed) }
        }
        // Forget peers silent for a day that nothing is waiting on.
        let stale = peerRecords.values.filter { rec in
            rec.lastHeard != MeshNode.never && now - rec.lastHeard > config.pendingExpiry &&
                !links.values.contains(where: { $0.peerId == rec.id }) && !outgoing.values.contains(where: { $0.to == rec.id })
        }
        stale.forEach { peerRecords.removeValue(forKey: $0.id) }
        publishPeers()
    }

    // MARK: helpers

    /// Hops to show for a message. `8 - ttl` is only an upper bound (a "short reach" packet starts below
    /// ttl 7), so prefer what a direct link or the sender's last announce says.
    private func hopsOf(_ rec: PeerRecord, _ packet: Packet) -> Int {
        if links.values.contains(where: { $0.peerId == rec.id }) { return 1 }
        return rec.hops > 0 ? min(rec.hops, packet.hops) : packet.hops
    }

    private func isReachable(_ rec: PeerRecord, _ now: Int64) -> Bool { statusOf(rec, now) != .offline }

    private func statusOf(_ rec: PeerRecord, _ now: Int64) -> PeerStatus {
        if rec.departed { return .offline }
        if links.values.contains(where: { $0.peerId == rec.id }) { return .nearby }
        if rec.lastHeard != MeshNode.never && now - rec.lastHeard < config.offlineAfter { return .viaMesh }
        return .offline
    }

    @discardableResult
    private func originate(_ packet: Packet, only: MeshLink? = nil) -> Int {
        seenPackets.insert(packet.packetId)
        stats.sent += 1
        let bytes = packet.raw
        if let only { return only.send(bytes) ? 1 : 0 }
        return linkOrder.reduce(0) { n, id in n + (links[id]?.link.send(bytes) == true ? 1 : 0) }
    }

    private func buildAnnounce() -> Packet? {
        try? PacketCodec.create(
            signing: identity.signing,
            payload: .announce(nickname: profile.nickname, emoji: profile.emoji, colorIndex: profile.colorIndex,
                               agreementKey: identity.agreement.publicKey),
            recipientId: .broadcast, ttl: Murmur.initialTtl, packetId: newPacketId(), timestamp: clock.now()
        )
    }

    private func newPacketId() -> PacketId { PacketId.random(random) }

    private func drop(_ reason: DropReason, _ linkId: String, _ detail: String) {
        if reason == .duplicate { stats.droppedDuplicate += 1 } else { stats.droppedInvalid += 1 }
        if reason != .duplicate { log("dropped \(reason) on \(linkId): \(detail)") }
    }

    private func emit(_ event: MeshEvent) { onEvent?(event) }

    private func publishPeers() {
        let now = clock.now()
        var out: [PeerId: PeerInfo] = [:]
        for rec in peerRecords.values {
            out[rec.id] = PeerInfo(
                id: rec.id, nickname: rec.nickname, emoji: rec.emoji, colorIndex: rec.colorIndex,
                signingKey: rec.signingKey, agreementKey: rec.agreementKey, lastHeard: rec.lastHeard, hops: rec.hops,
                status: statusOf(rec, now),
                linkIds: Set(links.filter { $0.value.peerId == rec.id }.keys)
            )
        }
        peers = out
        onPeersChanged?(out)
    }

    private func publishLinks() {
        onLinksChanged?(links.mapValues { $0.peerId })
    }

    private static let never: Int64 = 0
    private static let maxChannelsTracked = 256
}
