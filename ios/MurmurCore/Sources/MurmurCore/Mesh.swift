import Foundation

public struct Profile: Equatable, Codable, Sendable {
    public var nickname: String
    public var emoji: String
    public var colorIndex: Int

    public init(nickname: String, emoji: String, colorIndex: Int) {
        self.nickname = nickname
        self.emoji = emoji
        self.colorIndex = colorIndex
    }

    public var isValid: Bool {
        Murmur.isValidNickname(nickname) && !emoji.isEmpty && Murmur.utf8Size(emoji) <= Murmur.maxEmojiBytes &&
            colorIndex >= 0 && colorIndex < Murmur.avatarColorCount
    }
}

public enum PeerStatus: Int, Sendable { case nearby, viaMesh, offline }

/// Snapshot of what the mesh knows about one peer.
public struct PeerInfo: Equatable, Sendable {
    public let id: PeerId
    public let nickname: String?
    public let emoji: String?
    public let colorIndex: Int
    /// Ed25519 public key, once any signed packet from this peer arrived.
    public let signingKey: [UInt8]?
    public let agreementKey: [UInt8]?
    public let lastHeard: Int64
    public let hops: Int
    public let status: PeerStatus
    public let linkIds: Set<String>
}

/// DM delivery state. Order matters: a receipt never moves a message backwards.
public enum DeliveryStatus: Int, Comparable, Sendable {
    case pending, sending, sent, failed, delivered, read

    public static func < (a: DeliveryStatus, b: DeliveryStatus) -> Bool { a.rawValue < b.rawValue }
}

public enum MeshEvent {
    case publicMessage(packetId: PacketId, senderId: PeerId, nickname: String, text: String, timestamp: Int64, hops: Int)
    /// A DM shown to the user. Emitted once per messageId, however many copies arrive.
    case directMessage(messageId: MessageId, senderId: PeerId, body: String, timestamp: Int64, hops: Int)
    case delivery(messageId: MessageId, peerId: PeerId, status: DeliveryStatus)
    case typing(peerId: PeerId)
    case linkIdentified(linkId: String, peerId: PeerId)
    /// A ROOM packet: #nearby extras (channel "") or a channel message.
    case roomMessage(RoomMessage)
    /// A named channel is active nearby. `readable` is false for a password channel I have no key for.
    case channelSeen(channel: String, encrypted: Bool, readable: Bool, senderId: PeerId)
    /// One chunk of call audio addressed to me (still sealed with the call key).
    case callAudio(senderId: PeerId, callId: MessageId, seq: UInt32, sealed: [UInt8])
    /// A DM reaction, retraction, wave, disappearing-messages timer, call signal or channel invite.
    case directControl(senderId: PeerId, kind: DmKind, messageId: MessageId, body: String, timestamp: Int64)

    public struct RoomMessage {
        public let packetId: PacketId
        public let senderId: PeerId
        public let channel: String
        public let encrypted: Bool
        public let kind: RoomKind
        public let nickname: String
        public let target: PacketId?
        public let body: String
        public let timestamp: Int64
        public let hops: Int
    }
}

public struct MeshStats: Equatable, Sendable {
    public var sent: Int64 = 0
    public var received: Int64 = 0
    public var relayed: Int64 = 0
    public var droppedDuplicate: Int64 = 0
    public var droppedInvalid: Int64 = 0

    public init() {}
}

public struct MeshConfig {
    public var announceIntervalForeground: Int64 = 30_000
    public var announceIntervalBackground: Int64 = 60_000
    public var announceIntervalPowerSave: Int64 = 120_000
    public var offlineAfter: Int64 = 90_000
    public var ackTimeout: Int64 = 30_000
    public var maxResends = 3
    public var pendingExpiry: Int64 = 24 * 60 * 60 * 1000
    public var dedupeCapacity = 10_000
    public var maxFutureSkew: Int64 = 60 * 60 * 1000
    public var maxAge: Int64 = 12 * 60 * 60 * 1000
    public var typingInterval: Int64 = 3_000
    public var statusTick: Int64 = 5_000
    public var channelSeenInterval: Int64 = 20_000

    public init() {}
}

public enum DropReason { case duplicate, malformed, senderKeyMismatch, badSignature, staleTimestamp, undecryptable }

/// One transport connection to a neighbour (for Bluetooth: one GATT connection). The mesh only ever
/// sees complete packets; fragmentation is the transport's business.
public protocol MeshLink: AnyObject {
    /// Unique for the lifetime of the process.
    var id: String { get }

    /// Queues one complete encoded packet. False if the link can no longer send. Must not call back
    /// into the mesh synchronously.
    func send(_ packet: [UInt8]) -> Bool

    /// Called once the mesh learns who is on the other end (first direct ANNOUNCE, ttl 7).
    func onPeerIdentified(_ peerId: PeerId)
}

/// Insertion-ordered set that forgets its oldest entries beyond `capacity`.
struct BoundedSet<T: Hashable> {
    private let capacity: Int
    private var members = Set<T>()
    private var ring: [T?]
    private var head = 0

    init(capacity: Int) {
        self.capacity = capacity
        ring = Array(repeating: nil, count: capacity)
    }

    func contains(_ value: T) -> Bool { members.contains(value) }

    /// True if `value` was not present.
    @discardableResult
    mutating func insert(_ value: T) -> Bool {
        if members.contains(value) { return false }
        if let old = ring[head] { members.remove(old) }
        ring[head] = value
        head = (head + 1) % capacity
        members.insert(value)
        return true
    }

    var count: Int { members.count }
}
