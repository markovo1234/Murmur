import Foundation
import MurmurCore
import Observation

/// Everything the screens show and do: chats, people, channels and settings, kept in sync with the
/// mesh. The iPhone counterpart of the Android app's ChatRepository + PeerRepository + settings.
///
/// Main thread only. The mesh node and the Bluetooth transport live on the main queue too; mesh events
/// are handled on the next turn of the queue (like Android's event flow), never re-entrantly.
@Observable
final class AppModel {
    // Remembered between launches.
    private(set) var settings: AppSettings
    private(set) var peers: [String: KnownPeer]
    private(set) var channels: [String: JoinedChannel]
    private(set) var conversations: [String: Conversation]
    private(set) var messages: [String: [ChatMessage]]

    // Live.
    private(set) var meshPeers: [PeerId: PeerInfo] = [:]
    private(set) var ble = BleStatus()
    private(set) var stats = MeshStats()
    private(set) var nearbyChannels: [String: NearbyChannel] = [:]
    private(set) var typingUntil: [String: Int64] = [:]
    private(set) var meshRunning = false
    private(set) var isActive = true
    /// The chat on screen.
    private(set) var openConversationId: String?

    @ObservationIgnored let clock: Clock
    @ObservationIgnored let random: RandomSource
    @ObservationIgnored private(set) var identity: Identity
    @ObservationIgnored private let scheduler: Scheduler
    @ObservationIgnored private let post: (@escaping () -> Void) -> Void
    @ObservationIgnored private let file: SnapshotFile
    @ObservationIgnored private let identityStore: IdentityStore
    @ObservationIgnored private let notifier: Notifier
    @ObservationIgnored private let makeTransport: (PeerId) -> LinkTransport
    @ObservationIgnored private let log: (String) -> Void
    @ObservationIgnored private var node: MeshNode?
    @ObservationIgnored private var transport: LinkTransport?
    @ObservationIgnored private var housekeepingTimer: Cancellable?
    @ObservationIgnored private var handledCalls = Set<String>()
    @ObservationIgnored private var favoriteAlertedAt: [String: Int64] = [:]

    var myId: PeerId { identity.peerId }
    var myHex: String { identity.peerId.toHex() }

    init(
        clock: Clock = SystemClock(),
        random: RandomSource = SecureRandomSource(),
        scheduler: Scheduler = QueueScheduler(queue: .main),
        post: @escaping (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) },
        file: SnapshotFile,
        identityStore: IdentityStore,
        notifier: Notifier,
        makeTransport: @escaping (PeerId) -> LinkTransport,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.clock = clock
        self.random = random
        self.scheduler = scheduler
        self.post = post
        self.file = file
        self.identityStore = identityStore
        self.notifier = notifier
        self.makeTransport = makeTransport
        self.log = log
        identity = AppModel.loadOrCreateIdentity(identityStore, random)
        let snapshot = file.load()
        settings = snapshot.settings
        peers = snapshot.peers
        channels = snapshot.channels
        conversations = snapshot.conversations
        messages = snapshot.messages
        ensureNearby()
        scheduleHousekeeping()
    }

    private static func loadOrCreateIdentity(_ store: IdentityStore, _ random: RandomSource) -> Identity {
        if let keys = store.load(), keys.count == 64,
           let id = try? Identity.fromPrivateKeys(signing: Array(keys[0..<32]), agreement: Array(keys[32..<64])) {
            return id
        }
        let id = Identity.generate(random)
        _ = store.save(id.signing.privateKey + id.agreement.privateKey)
        return id
    }

    // MARK: - lifecycle

    func completeOnboarding(_ profile: Profile) {
        guard profile.isValid else { return }
        settings.profile = profile
        settings.onboardingDone = true
        save()
        notifier.requestPermission()
        startMesh()
    }

    /// Starts Bluetooth and the mesh (after onboarding).
    func startMesh() {
        guard node == nil, settings.onboardingDone, let profile = settings.profile, profile.isValid else { return }
        let node = MeshNode(identity: identity, profile: profile, clock: clock, random: random, scheduler: scheduler,
                            log: { [log] in log("MESH \($0)") })
        node.onEvent = { [weak self] event in self?.post { self?.handle(event) } }
        node.onPeersChanged = { [weak self] peers in self?.peersChanged(peers) }
        node.onStatsChanged = { [weak self] s in self?.stats = s }
        node.setRelayEnabled(settings.relay)
        node.setBlocked(blockedIds())
        node.setChannelKeys(channelKeys())
        node.setBackground(!isActive)
        let transport = makeTransport(identity.peerId)
        transport.onLinkUp = { [weak node] in node?.linkUp($0) }
        transport.onReceived = { [weak node] in node?.received(linkId: $0, bytes: $1) }
        transport.onLinkDown = { [weak node] in node?.linkDown($0) }
        transport.onStatus = { [weak self] in self?.ble = $0 }
        self.node = node
        self.transport = transport
        node.start()
        transport.start()
        meshRunning = true
        resendUnfinishedDms()
        log("mesh started as \(identity.peerId)")
    }

    func stopMesh() {
        guard let node else { return }
        node.sendLeave()
        transport?.stop()
        node.stop()
        self.node = nil
        transport = nil
        meshPeers = [:]
        ble = BleStatus()
        meshRunning = false
    }

    /// The app went to the foreground (true) or the background (false).
    func setActive(_ active: Bool) {
        isActive = active
        node?.setBackground(!active)
        if active {
            purgeExpired()
            if let id = openConversationId { markRead(id) }
        } else {
            file.saveNow(snapshot())
        }
    }

    /// The UI says which chat is on screen (nil = none).
    func setOpenConversation(_ id: String?) {
        openConversationId = id
        if let id, isActive { markRead(id) }
    }

    /// Panic button: forget everything, new identity, back to onboarding.
    func eraseEverything() {
        stopMesh()
        identityStore.delete()
        file.erase()
        identity = AppModel.loadOrCreateIdentity(identityStore, random)
        settings = AppSettings()
        peers = [:]
        channels = [:]
        conversations = [:]
        messages = [:]
        nearbyChannels = [:]
        typingUntil = [:]
        openConversationId = nil
        ensureNearby()
    }

    // MARK: - sending

    enum SendResult: Equatable { case sent, meshOff, tooLong, empty, notMember }

    @discardableResult
    func send(_ rawText: String, in conversationId: String) -> SendResult {
        let text = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .empty }
        if Murmur.utf8Size(text) > Murmur.maxTextBytes { return .tooLong }
        let now = clock.now()
        if let peer = ConversationId.peer(conversationId) {
            let id = MessageId.random(random)
            ensureConversation(conversationId)
            insert(ChatMessage(id: id.toHex(), conversationId: conversationId, senderId: myHex, senderName: myName, body: text,
                               sentAt: now, sortKey: now, outgoing: true, status: DeliveryStatus.pending.rawValue, hops: 0,
                               expiresAt: expiry(conversationId, now)))
            touch(conversationId, preview: "You: \(Replies.parse(text).text)", at: now)
            try? node?.sendDirectMessage(to: peer, messageId: id, body: text, createdAt: now)
            save()
            return .sent
        }
        guard let node else { return .meshOff }
        let packetId: PacketId
        if conversationId == ConversationId.nearby {
            guard let id = try? node.sendPublic(text, ttl: settings.publicReach) else { return .tooLong }
            packetId = id
        } else if let name = ConversationId.channelName(conversationId) {
            guard let ch = channels[name] else { return .notMember }
            guard let id = try? node.sendRoom(channel: name, kind: .text, body: text, key: ch.key, ttl: settings.publicReach) else { return .tooLong }
            packetId = id
        } else {
            return .notMember
        }
        insert(ChatMessage(id: packetId.toHex(), conversationId: conversationId, senderId: myHex, senderName: myName, body: text,
                           sentAt: now, sortKey: now, outgoing: true, status: nil, hops: 0))
        touch(conversationId, preview: "You: \(Replies.parse(text).text)", at: now)
        save()
        return .sent
    }

    /// Sends a DM again after it failed.
    func retry(messageId: String, in conversationId: String) {
        guard let peer = ConversationId.peer(conversationId), let id = MessageId.fromHex(messageId),
              let m = message(messageId, in: conversationId), m.outgoing else { return }
        setStatus(messageId, in: conversationId, .pending, force: true)
        try? node?.sendDirectMessage(to: peer, messageId: id, body: m.body, createdAt: clock.now())
    }

    /// Typing indicator for a DM (at most one every 3 s; never queued).
    func userTyping(in conversationId: String) {
        guard let peer = ConversationId.peer(conversationId) else { return }
        node?.sendTyping(to: peer)
    }

    /// Adds my reaction, or removes it when it's the same emoji again.
    func react(_ emoji: String, to messageId: String, in conversationId: String) {
        guard let m = message(messageId, in: conversationId), !m.retracted, m.kind == .text || m.kind == .sos else { return }
        let chosen = m.reactions[myHex] == emoji ? "" : emoji
        if let peer = ConversationId.peer(conversationId) {
            guard let target = MessageId.fromHex(messageId) else { return }
            node?.sendDirectControl(to: peer, kind: .reaction, messageId: target, body: chosen)
        } else {
            guard let target = PacketId.fromHex(messageId) else { return }
            let channel = ConversationId.channelName(conversationId) ?? ""
            _ = try? node?.sendRoom(channel: channel, kind: .reaction, body: chosen, target: target,
                                    key: channels[channel]?.key, ttl: settings.publicReach)
        }
        applyReaction(conversationId, messageId, myHex, chosen)
    }

    /// Delete for everyone (my own messages only).
    func retract(_ messageId: String, in conversationId: String) {
        guard let m = message(messageId, in: conversationId), m.outgoing, !m.retracted else { return }
        if let peer = ConversationId.peer(conversationId) {
            if let target = MessageId.fromHex(messageId) { node?.sendDirectControl(to: peer, kind: .retract, messageId: target) }
        } else if let target = PacketId.fromHex(messageId) {
            let channel = ConversationId.channelName(conversationId) ?? ""
            _ = try? node?.sendRoom(channel: channel, kind: .retract, body: "", target: target, key: channels[channel]?.key, ttl: settings.publicReach)
        }
        applyRetraction(conversationId, messageId, myHex)
    }

    func wave(_ peer: PeerId) -> Bool {
        guard let node, node.sendDirectControl(to: peer, kind: .wave, messageId: MessageId.random(random)) else { return false }
        let cid = peer.toHex()
        ensureConversation(cid)
        system(cid, "You waved 👋")
        return true
    }

    /// Disappearing messages for a DM (0 = off).
    func setDisappearing(_ seconds: Int64, for peer: PeerId) {
        let cid = peer.toHex()
        ensureConversation(cid)
        conversations[cid]?.disappearSeconds = seconds
        node?.sendDirectControl(to: peer, kind: .timer, messageId: MessageId.random(random), body: String(seconds))
        system(cid, seconds == 0 ? "You turned off disappearing messages" : "You set messages to disappear after \(Disappearing.label(seconds))")
    }

    // MARK: - channels

    enum JoinResult: Equatable { case joined(String), invalid }

    /// Joins (or changes the password of) a channel. The password key takes a moment to compute.
    func joinChannel(_ input: String, password: String?) async -> JoinResult {
        guard let name = Channels.normalize(input) else { return .invalid }
        let key: [UInt8]?
        if let password, !password.isEmpty {
            key = await Task.detached(priority: .userInitiated) { ChannelCrypto.deriveKey(channel: name, password: password) }.value
        } else {
            key = nil
        }
        return await MainActor.run { finishJoin(name, key: key, invitedBy: nil) }
    }

    @discardableResult
    private func finishJoin(_ name: String, key: [UInt8]?, invitedBy: String?) -> JoinResult {
        let keyHex = key.map(Hex.encode)
        let existing = channels[name]
        channels[name] = JoinedChannel(name: name, keyHex: keyHex, joinedAt: existing?.joinedAt ?? clock.now())
        let cid = ConversationId.channel(name)
        ensureConversation(cid)
        let lock = keyHex != nil ? " 🔒" : ""
        if existing == nil {
            system(cid, "You joined #\(name)\(lock)\(invitedBy.map { " (invited by \($0))" } ?? "")\(keyHex != nil ? ". Only people with the password can read it" : "")")
        } else if existing?.keyHex != keyHex {
            system(cid, keyHex != nil ? "🔒 Password set. You'll see messages from everyone using the same password." : "#\(name) is now open to anyone who joins it.")
        }
        node?.setChannelKeys(channelKeys())
        save()
        return .joined(name)
    }

    func leaveChannel(_ name: String) {
        channels.removeValue(forKey: name)
        let cid = ConversationId.channel(name)
        conversations.removeValue(forKey: cid)
        messages.removeValue(forKey: cid)
        node?.setChannelKeys(channelKeys())
        save()
    }

    enum InviteResult: Equatable { case sent, notAMember, unreachable }

    func sendInvite(_ peer: PeerId, to channel: String) -> InviteResult {
        guard let ch = channels[channel] else { return .notAMember }
        let body = ChannelInvites.body(channel: channel, key: ch.key)
        let id = MessageId.random(random)
        guard let node, node.sendDirectControl(to: peer, kind: .channelInvite, messageId: id, body: body) else { return .unreachable }
        let cid = peer.toHex()
        let now = clock.now()
        ensureConversation(cid)
        insert(ChatMessage(id: id.toHex(), conversationId: cid, senderId: myHex, senderName: myName, body: body, sentAt: now,
                           sortKey: now, outgoing: true, status: nil, hops: 0, kind: .invite, expiresAt: expiry(cid, now)))
        touch(cid, preview: "You invited them to #\(channel)", at: now)
        save()
        return .sent
    }

    /// Joins the channel an invite is for. Returns its conversation id.
    func acceptInvite(_ messageId: String, in conversationId: String) -> String? {
        guard let m = message(messageId, in: conversationId), m.kind == .invite, let invite = ChannelInvites.parse(m.body) else { return nil }
        finishJoin(invite.channel, key: invite.key, invitedBy: m.outgoing ? nil : displayName(m.senderId))
        return ConversationId.channel(invite.channel)
    }

    // MARK: - people

    func toggleFavorite(_ peerHex: String) {
        guard var p = peers[peerHex] else { return }
        p.favorite.toggle()
        peers[peerHex] = p
        save()
    }

    func setBlocked(_ peerHex: String, _ blocked: Bool) {
        guard var p = peers[peerHex] else { return }
        p.blocked = blocked
        if blocked { p.favorite = false }
        peers[peerHex] = p
        node?.setBlocked(blockedIds())
        save()
    }

    /// Opens (creating if needed) the DM with a peer.
    func dmConversation(with peerHex: String) -> String {
        ensureConversation(peerHex)
        return peerHex
    }

    func deleteConversation(_ id: String) {
        guard id != ConversationId.nearby else { return }
        if let name = ConversationId.channelName(id) {
            leaveChannel(name)
            return
        }
        conversations.removeValue(forKey: id)
        messages.removeValue(forKey: id)
        notifier.cancel(conversationId: id)
        save()
    }

    func setMuted(_ id: String, _ muted: Bool) {
        conversations[id]?.muted = muted
        save()
    }

    func safetyNumber(_ peerHex: String) -> String? {
        guard let theirs = peers[peerHex]?.signingKeyHex.flatMap(Hex.decode) else { return nil }
        return SafetyNumber.formatted(identity.signing.publicKey, theirs)
    }

    func displayName(_ peerHex: String) -> String {
        if peerHex == myHex { return myName }
        if let p = peers[peerHex], !p.name.isEmpty { return p.name }
        return PeerId.fromHex(peerHex).map { "Peer #\($0.shortTag)" } ?? "Someone"
    }

    func status(of peerHex: String) -> PeerStatus {
        guard let id = PeerId.fromHex(peerHex) else { return .offline }
        return meshPeers[id]?.status ?? .offline
    }

    func hops(to peerHex: String) -> Int? {
        guard let id = PeerId.fromHex(peerHex), let info = meshPeers[id], info.status != .offline else { return nil }
        return info.status == .nearby ? 1 : (info.hops > 0 ? info.hops : nil)
    }

    func isTyping(_ peerHex: String) -> Bool { (typingUntil[peerHex] ?? 0) > clock.now() }

    var myName: String { settings.profile?.nickname ?? "Me" }

    // MARK: - settings

    func updateProfile(_ profile: Profile) {
        guard profile.isValid else { return }
        settings.profile = profile
        try? node?.updateProfile(profile)
        save()
    }

    func setReadReceipts(_ on: Bool) { settings.readReceipts = on; save() }
    func setNearbyNotifications(_ on: Bool) { settings.nearbyNotifications = on; save() }
    func setFavoriteAlerts(_ on: Bool) { settings.favoriteAlerts = on; save() }
    func setHideNotificationContent(_ on: Bool) { settings.hideNotificationContent = on; save() }
    func setShortReach(_ on: Bool) { settings.publicReach = on ? 3 : Murmur.initialTtl; save() }

    func setRelay(_ on: Bool) {
        settings.relay = on
        node?.setRelayEnabled(on)
        save()
    }

    // MARK: - reading

    /// Clears the unread count and sends read receipts for the DMs on screen.
    func markRead(_ conversationId: String) {
        guard var conv = conversations[conversationId] else { return }
        if conv.unread != 0 {
            conv.unread = 0
            conversations[conversationId] = conv
        }
        notifier.cancel(conversationId: conversationId)
        guard let peer = ConversationId.peer(conversationId), var list = messages[conversationId] else { return }
        var changed = false
        for i in list.indices where !list[i].outgoing && !list[i].seen {
            list[i].seen = true
            changed = true
            if settings.readReceipts, let id = MessageId.fromHex(list[i].id) { node?.sendReadReceipt(to: peer, messageId: id) }
        }
        if changed {
            messages[conversationId] = list
            save()
        }
    }

    var totalUnread: Int { conversations.values.filter { !$0.muted }.reduce(0) { $0 + $1.unread } }

    /// Chats, most recent first, #nearby pinned on top.
    var sortedConversations: [Conversation] {
        conversations.values.sorted { a, b in
            if a.isNearby != b.isNearby { return a.isNearby }
            return a.lastActivity > b.lastActivity
        }
    }

    // MARK: - mesh events

    private func handle(_ event: MeshEvent) {
        switch event {
        case let .publicMessage(packetId, sender, nickname, text, timestamp, hops):
            receiveRoomText(ConversationId.nearby, packetId.toHex(), sender, nickname, text, timestamp, hops)
        case let .directMessage(messageId, sender, body, timestamp, hops):
            receiveDirect(messageId.toHex(), sender, body, timestamp, hops)
        case let .delivery(messageId, peer, status):
            setStatus(messageId.toHex(), in: peer.toHex(), status, force: false)
        case let .typing(peer):
            typingUntil[peer.toHex()] = clock.now() + 6_000
        case let .roomMessage(m):
            receiveRoom(m)
        case let .channelSeen(channel, encrypted, readable, _):
            let entry = NearbyChannel(name: channel, locked: encrypted, readable: readable, lastSeen: clock.now())
            nearbyChannels[entry.id] = entry
        case let .directControl(sender, kind, messageId, body, _):
            receiveControl(sender, kind, messageId, body)
        case .linkIdentified, .callAudio:
            break
        }
    }

    private func isBlocked(_ peerHex: String) -> Bool { peers[peerHex]?.blocked == true }

    private func receiveRoomText(_ cid: String, _ idHex: String, _ sender: PeerId, _ nickname: String, _ text: String, _ sentAt: Int64, _ hops: Int) {
        let senderHex = sender.toHex()
        if isBlocked(senderHex) { return }
        let now = clock.now()
        let mention = Mentions.mentions(text, nickname: settings.profile?.nickname)
        ensureConversation(cid)
        guard insert(ChatMessage(id: idHex, conversationId: cid, senderId: senderHex, senderName: nickname, body: text, sentAt: sentAt,
                                 sortKey: now, outgoing: false, status: nil, hops: hops, mentionsMe: mention)) else { return }
        let onScreen = isOnScreen(cid)
        touch(cid, preview: "\(nickname): \(Replies.parse(text).text)", at: now, unreadDelta: onScreen ? 0 : 1)
        let conv = conversations[cid]
        let channel = cid != ConversationId.nearby
        if !onScreen, conv?.muted != true, mention || channel || settings.nearbyNotifications {
            let title = mention ? "\(nickname) mentioned you in \(conversationTitle(cid))" : "\(conversationTitle(cid))"
            notifier.notify(id: idHex, title: title, body: hideContent ? "New message" : "\(nickname): \(text)", conversationId: cid)
        }
        save()
    }

    private func receiveDirect(_ idHex: String, _ sender: PeerId, _ body: String, _ sentAt: Int64, _ hops: Int) {
        let cid = sender.toHex()
        if isBlocked(cid) { return }
        typingUntil.removeValue(forKey: cid)
        let now = clock.now()
        ensureConversation(cid)
        let onScreen = isOnScreen(cid)
        guard insert(ChatMessage(id: idHex, conversationId: cid, senderId: cid, senderName: displayName(cid), body: body, sentAt: sentAt,
                                 sortKey: now, outgoing: false, status: nil, hops: hops, expiresAt: expiry(cid, now), seen: false)) else { return }
        touch(cid, preview: Replies.parse(body).text, at: now, unreadDelta: onScreen ? 0 : 1)
        if onScreen {
            markRead(cid)
        } else if conversations[cid]?.muted != true {
            let p = peers[cid]
            notifier.notify(id: idHex, title: hideContent ? "Murmur" : "\(p?.emoji ?? "🙂") \(displayName(cid))",
                            body: hideContent ? "New message" : body, conversationId: cid)
        }
        save()
    }

    private func receiveRoom(_ m: MeshEvent.RoomMessage) {
        let senderHex = m.senderId.toHex()
        if isBlocked(senderHex) { return }
        let cid: String
        if m.channel.isEmpty {
            cid = ConversationId.nearby
        } else {
            // Not a member, or an open channel and a password channel sharing a name.
            guard let joined = channels[m.channel], joined.locked == m.encrypted else { return }
            cid = ConversationId.channel(m.channel)
        }
        switch m.kind {
        case .text:
            receiveRoomText(cid, m.packetId.toHex(), m.senderId, m.nickname, m.body, m.timestamp, m.hops)
        case .reaction:
            if let t = m.target { applyReaction(cid, t.toHex(), senderHex, m.body) }
        case .retract:
            if let t = m.target { applyRetraction(cid, t.toHex(), senderHex) }
        case .sos:
            guard cid == ConversationId.nearby else { return }
            let now = clock.now()
            guard insert(ChatMessage(id: m.packetId.toHex(), conversationId: cid, senderId: senderHex, senderName: m.nickname,
                                     body: String(m.body.prefix(140)), sentAt: m.timestamp, sortKey: now, outgoing: false, status: nil,
                                     hops: m.hops, kind: .sos)) else { return }
            touch(cid, preview: "🆘 \(m.nickname): \(m.body.isEmpty ? "needs help" : m.body)", at: now, unreadDelta: 1)
            // Emergency alerts always notify.
            notifier.notify(id: m.packetId.toHex(), title: "🆘 \(m.nickname) needs help", body: m.body.isEmpty ? "\(m.hops) hops away" : m.body,
                            conversationId: cid)
            save()
        }
    }

    private func receiveControl(_ sender: PeerId, _ kind: DmKind, _ messageId: MessageId, _ body: String) {
        let cid = sender.toHex()
        if isBlocked(cid) { return }
        let name = displayName(cid)
        switch kind {
        case .reaction:
            applyReaction(cid, messageId.toHex(), cid, body)
        case .retract:
            applyRetraction(cid, messageId.toHex(), cid)
        case .wave:
            ensureConversation(cid)
            system(cid, "\(name) waved at you 👋", unread: !isOnScreen(cid))
            if !isOnScreen(cid) { notifier.notify(id: messageId.toHex(), title: "👋", body: "\(name) waved at you", conversationId: cid) }
        case .timer:
            guard let seconds = Int64(body).map({ min(max($0, 0), Disappearing.maxSeconds) }) else { return }
            ensureConversation(cid)
            conversations[cid]?.disappearSeconds = seconds
            system(cid, seconds == 0 ? "\(name) turned off disappearing messages" : "\(name) set messages to disappear after \(Disappearing.label(seconds))")
        case .channelInvite:
            guard let invite = ChannelInvites.parse(body) else { return }
            let now = clock.now()
            ensureConversation(cid)
            let onScreen = isOnScreen(cid)
            guard insert(ChatMessage(id: messageId.toHex(), conversationId: cid, senderId: cid, senderName: name, body: body, sentAt: now,
                                     sortKey: now, outgoing: false, status: nil, hops: 0, kind: .invite, expiresAt: expiry(cid, now))) else { return }
            touch(cid, preview: "\(invite.locked ? "🔒 " : "")Invite to #\(invite.channel)", at: now, unreadDelta: onScreen ? 0 : 1)
            if !onScreen, conversations[cid]?.muted != true {
                notifier.notify(id: messageId.toHex(), title: hideContent ? "Murmur" : "\(name) invited you to #\(invite.channel)",
                                body: "Tap to see the invite", conversationId: cid)
            }
            save()
        case .callOffer:
            // No calls on iPhone yet: say so at once, so the caller doesn't ring into the void.
            node?.sendDirectControl(to: sender, kind: .callAnswer, messageId: messageId, body: CallSignal.Answer.unsupported.wire)
            guard handledCalls.insert(messageId.toHex()).inserted else { return }
            ensureConversation(cid)
            system(cid, "📞 \(name) tried to call you. Calls aren't on iPhone yet.", unread: !isOnScreen(cid))
            if !isOnScreen(cid) {
                notifier.notify(id: messageId.toHex(), title: "📞 \(name) tried to call", body: "Voice calls aren't available on iPhone yet", conversationId: cid)
            }
        case .text, .delivered, .read, .typing, .callAnswer, .callEnd:
            break
        }
    }

    private func applyReaction(_ cid: String, _ targetHex: String, _ reactorHex: String, _ emoji: String) {
        guard var list = messages[cid], let i = list.firstIndex(where: { $0.id == targetHex }), !list[i].retracted else { return }
        if emoji.isEmpty {
            list[i].reactions.removeValue(forKey: reactorHex)
        } else if Murmur.utf8Size(emoji) <= Murmur.maxEmojiBytes {
            list[i].reactions[reactorHex] = emoji
        }
        messages[cid] = list
        save()
    }

    private func applyRetraction(_ cid: String, _ targetHex: String, _ senderHex: String) {
        guard var list = messages[cid], let i = list.firstIndex(where: { $0.id == targetHex }), list[i].senderId == senderHex else { return }
        list[i].retracted = true
        list[i].body = ""
        list[i].reactions = [:]
        messages[cid] = list
        refreshPreview(cid)
        save()
    }

    private func setStatus(_ idHex: String, in cid: String, _ status: DeliveryStatus, force: Bool) {
        guard var list = messages[cid], let i = list.firstIndex(where: { $0.id == idHex && $0.outgoing }) else { return }
        let old = list[i].delivery
        // A receipt never moves a message backwards (a retry after "failed" may).
        if !force, let old, old >= .delivered, status < old { return }
        list[i].status = status.rawValue
        messages[cid] = list
        save()
    }

    private func peersChanged(_ map: [PeerId: PeerInfo]) {
        let previous = meshPeers
        meshPeers = map
        var changed = false
        for (id, info) in map {
            guard let nickname = info.nickname else { continue }
            let hex = id.toHex()
            var p = peers[hex] ?? KnownPeer(id: hex, name: nickname, emoji: info.emoji ?? "🙂", colorIndex: info.colorIndex, lastSeen: 0)
            let before = p
            p.name = nickname
            if let e = info.emoji { p.emoji = e }
            p.colorIndex = info.colorIndex
            if let key = info.signingKey { p.signingKeyHex = Hex.encode(key) }
            if info.lastHeard > p.lastSeen + 60_000 || before.lastSeen == 0 { p.lastSeen = info.lastHeard }
            if p != before {
                peers[hex] = p
                changed = true
                if before.name != p.name, conversations[hex] != nil { conversations[hex]?.title = p.name }
            }
            // A favorite just came into Bluetooth range.
            if settings.favoriteAlerts, p.favorite, info.status == .nearby, previous[id]?.status != .nearby {
                let now = clock.now()
                if now - (favoriteAlertedAt[hex] ?? 0) > 10 * 60_000 {
                    favoriteAlertedAt[hex] = now
                    notifier.notify(id: "fav-\(hex)-\(now)", title: "⭐ \(p.emoji) \(p.name) is nearby", body: "Say hi while you're in range", conversationId: hex)
                }
            }
        }
        if changed { save() }
    }

    // MARK: - storage helpers

    @discardableResult
    private func insert(_ m: ChatMessage) -> Bool {
        var list = messages[m.conversationId] ?? []
        if list.contains(where: { $0.id == m.id }) { return false }
        if let last = list.last, last.sortKey > m.sortKey {
            let at = list.firstIndex { $0.sortKey > m.sortKey } ?? list.endIndex
            list.insert(m, at: at)
        } else {
            list.append(m)
        }
        if list.count > AppModel.maxMessagesPerChat { list.removeFirst(list.count - AppModel.maxMessagesPerChat) }
        messages[m.conversationId] = list
        return true
    }

    private func message(_ id: String, in cid: String) -> ChatMessage? { messages[cid]?.first { $0.id == id } }

    private func system(_ cid: String, _ text: String, unread: Bool = false) {
        let now = clock.now()
        insert(ChatMessage(id: "sys-\(now)-\(Hex.encode(random.nextBytes(4)))", conversationId: cid, senderId: "", senderName: "", body: text,
                           sentAt: now, sortKey: now, outgoing: false, status: nil, hops: 0, kind: .system, expiresAt: expiry(cid, now)))
        touch(cid, preview: text, at: now, unreadDelta: unread ? 1 : 0)
        save()
    }

    private func ensureNearby() {
        if conversations[ConversationId.nearby] == nil {
            conversations[ConversationId.nearby] = Conversation(id: ConversationId.nearby, title: "#nearby", lastActivity: 0,
                                                                preview: "Everyone in Bluetooth range")
        }
    }

    private func ensureConversation(_ cid: String) {
        if conversations[cid] == nil {
            conversations[cid] = Conversation(id: cid, title: conversationTitle(cid), lastActivity: clock.now(), preview: "")
        }
    }

    func conversationTitle(_ cid: String) -> String {
        if cid == ConversationId.nearby { return "#nearby" }
        if let name = ConversationId.channelName(cid) { return "#\(name)" }
        return displayName(cid)
    }

    private func touch(_ cid: String, preview: String, at time: Int64, unreadDelta: Int = 0) {
        ensureConversation(cid)
        conversations[cid]?.preview = preview
        conversations[cid]?.lastActivity = time
        conversations[cid]?.unread += unreadDelta
    }

    private func refreshPreview(_ cid: String) {
        guard let last = messages[cid]?.last(where: { !$0.retracted }) else {
            conversations[cid]?.preview = ""
            return
        }
        conversations[cid]?.preview = last.kind == .system ? last.body : "\(last.outgoing ? "You" : last.senderName): \(Replies.parse(last.body).text)"
    }

    private func expiry(_ cid: String, _ now: Int64) -> Int64? {
        guard let s = conversations[cid]?.disappearSeconds, s > 0 else { return nil }
        return now + s * 1_000
    }

    private func isOnScreen(_ cid: String) -> Bool { isActive && openConversationId == cid }

    private var hideContent: Bool { settings.hideNotificationContent }

    private func blockedIds() -> Set<PeerId> { Set(peers.values.filter(\.blocked).compactMap { PeerId.fromHex($0.id) }) }

    private func channelKeys() -> [String: [UInt8]] {
        var out: [String: [UInt8]] = [:]
        for ch in channels.values { if let k = ch.key { out[ch.name] = k } }
        return out
    }

    /// DMs that were on their way when the app last stopped.
    private func resendUnfinishedDms() {
        let now = clock.now()
        for (cid, list) in messages {
            guard let peer = ConversationId.peer(cid) else { continue }
            for m in list where m.outgoing && m.kind == .text {
                guard let s = m.delivery, s == .pending || s == .sending || s == .sent, now - m.sortKey < 24 * 3_600_000,
                      let id = MessageId.fromHex(m.id) else { continue }
                try? node?.sendDirectMessage(to: peer, messageId: id, body: m.body, createdAt: m.sortKey)
            }
        }
    }

    private func scheduleHousekeeping() {
        housekeepingTimer = scheduler.schedule(afterMillis: 15_000) { [weak self] in
            self?.purgeExpired()
            self?.scheduleHousekeeping()
        }
    }

    func purgeExpired() {
        let now = clock.now()
        for (cid, list) in messages where list.contains(where: { ($0.expiresAt ?? .max) <= now }) {
            messages[cid] = list.filter { ($0.expiresAt ?? .max) > now }
            refreshPreview(cid)
        }
        typingUntil = typingUntil.filter { $0.value > now }
        nearbyChannels = nearbyChannels.filter { now - $0.value.lastSeen < 5 * 60_000 }
    }

    private func save() {
        file.scheduleSave { [weak self] in self?.snapshot() ?? Snapshot() }
    }

    private func snapshot() -> Snapshot {
        Snapshot(settings: settings, peers: peers, channels: channels, conversations: conversations, messages: messages)
    }

    static let maxMessagesPerChat = 1_000
}
