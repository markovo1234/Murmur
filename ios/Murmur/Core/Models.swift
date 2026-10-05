import Foundation
import MurmurCore

/// Conversation ids, the same scheme as the Android app: "nearby", "#<channel>" or a peer's hex id.
enum ConversationId {
    static let nearby = "nearby"

    static func channel(_ name: String) -> String { "#\(name)" }

    static func channelName(_ id: String) -> String? { id.hasPrefix("#") ? String(id.dropFirst()) : nil }

    static func peer(_ id: String) -> PeerId? { PeerId.fromHex(id) }
}

struct AppSettings: Codable, Equatable {
    var profile: Profile?
    var onboardingDone = false
    var readReceipts = true
    var relay = true
    /// Notify for every #nearby message (DMs, joined channels and @mentions always notify).
    var nearbyNotifications = false
    var favoriteAlerts = true
    var hideNotificationContent = false
    /// How far my #nearby and channel messages travel (7 = normal, 3 = short).
    var publicReach = 7
}

/// Someone this phone has heard from (kept after they leave, like Android's "Met before").
struct KnownPeer: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var emoji: String
    var colorIndex: Int
    var lastSeen: Int64
    var signingKeyHex: String?
    var favorite = false
    var blocked = false
}

struct JoinedChannel: Codable, Equatable, Identifiable {
    var name: String
    /// The 32-byte key of a password channel, or nil for an open one.
    var keyHex: String?
    var joinedAt: Int64

    var id: String { name }
    var locked: Bool { keyHex != nil }
    var key: [UInt8]? { keyHex.flatMap(Hex.decode) }
}

enum MessageKind: String, Codable {
    case text, system, invite, sos
}

struct ChatMessage: Codable, Equatable, Identifiable {
    /// Packet id (rooms), message id (DMs) or a local id (system lines).
    var id: String
    var conversationId: String
    var senderId: String
    var senderName: String
    var body: String
    /// The sender's clock.
    var sentAt: Int64
    /// When it arrived here (or was written); the order messages are shown in.
    var sortKey: Int64
    var outgoing: Bool
    /// DMs I sent: a `DeliveryStatus` raw value.
    var status: Int?
    var hops: Int
    var kind: MessageKind = .text
    var retracted = false
    /// reactor peer hex → emoji
    var reactions: [String: String] = [:]
    var expiresAt: Int64?
    var mentionsMe = false
    /// Incoming DMs: whether I've read it (and sent the read receipt).
    var seen = true

    var delivery: DeliveryStatus? { status.flatMap(DeliveryStatus.init(rawValue:)) }
}

struct Conversation: Codable, Equatable, Identifiable {
    var id: String
    var title: String
    var lastActivity: Int64
    var preview: String
    var unread = 0
    var muted = false
    /// Disappearing messages in this chat (seconds, 0 = off).
    var disappearSeconds: Int64 = 0

    var isNearby: Bool { id == ConversationId.nearby }
    var channelName: String? { ConversationId.channelName(id) }
    var peerId: PeerId? { ConversationId.peer(id) }
}

/// A channel someone nearby is using (joined or not).
struct NearbyChannel: Equatable, Identifiable {
    var name: String
    var locked: Bool
    var readable: Bool
    var lastSeen: Int64

    var id: String { "\(name)|\(locked)" }
}

/// What the Bluetooth radio is doing, for the UI.
struct BleStatus: Equatable {
    enum Radio: Equatable { case unknown, poweredOn, poweredOff, unauthorized, unsupported, resetting }

    var radio: Radio = .unknown
    var scanning = false
    var advertising = false
    var links = 0
    var candidates = 0
    var lastError: String?
}

/// Avatar colours, in the same order as the Android palette (RGB), so a colour index looks the same on both.
enum AvatarPalette {
    static let colors: [UInt32] = [0x5EEAD4, 0xA78BFA, 0xFBBF24, 0xFB7185, 0x60A5FA, 0x34D399, 0xF472B6, 0xF97316, 0x22D3EE, 0xC084FC]
    static let names = ["mint", "lavender", "amber", "rose", "blue", "green", "pink", "orange", "cyan", "purple"]

    /// The avatar emojis offered when making a profile (Android's list).
    static let emojis = [
        "🦊", "🐙", "🐧", "🦉", "🐢", "🐝",
        "🦋", "🐬", "🌙", "⭐", "🌸", "🍀",
        "🔥", "🌊", "⚡", "🍄", "🎧", "🎸",
        "🚀", "🛰️", "🎈", "🍩", "🧭", "👾",
    ]
}
