import Foundation

public enum DmKind: Int, Sendable {
    case text = 1
    case delivered = 2
    case read = 3
    case typing = 4

    // Since 1.1. Older versions ignore these (relays never look inside a DM).

    /// messageId = the reacted-to message; body = emoji, or "" to remove the reaction.
    case reaction = 5
    /// Delete for everyone: messageId = the sender's own message.
    case retract = 6
    /// A nudge: "👋 waved at you".
    case wave = 7
    /// Disappearing messages for this chat: body = seconds as decimal text ("0" = off).
    case timer = 8

    // Since 1.2: voice calls. messageId = the call id.

    case callOffer = 9
    case callAnswer = 10
    case callEnd = 11

    // Since 1.3.

    /// An invitation to a channel: body = `ChannelInvites.body` (name + key for password channels).
    case channelInvite = 12

    /// Control kinds are sent once and never answered with receipts.
    public var isControl: Bool { rawValue >= DmKind.reaction.rawValue }
}

/// The inner plaintext of a PRIVATE packet. `senderAgreementKey` travels with every message so the
/// recipient can always reply, even if it never heard the sender's ANNOUNCE.
public struct DmContent: Equatable {
    public let kind: DmKind
    public let messageId: MessageId
    public let senderAgreementKey: [UInt8]
    public let body: String

    public init(kind: DmKind, messageId: MessageId, senderAgreementKey: [UInt8], body: String) {
        self.kind = kind
        self.messageId = messageId
        self.senderAgreementKey = senderAgreementKey
        self.body = body
    }

    /// kind + messageId + key + empty body length.
    public static let minSize = 1 + MessageId.size + X25519.publicKeySize + 2

    public func encode() throws -> [UInt8] {
        guard senderAgreementKey.count == X25519.publicKeySize else { throw PacketError("bad agreement key") }
        guard Murmur.utf8Size(body) <= Murmur.maxTextBytes else { throw PacketError("body too long") }
        var w = ByteWriter(capacity: DmContent.minSize + body.utf8.count)
        w.u8(kind.rawValue)
        w.append(messageId.toBytes())
        w.append(senderAgreementKey)
        w.string16(body)
        return w.bytes
    }

    public static func decode(_ bytes: [UInt8]) -> DmContent? {
        var r = ByteReader(bytes)
        guard let code = try? r.u8(), let kind = DmKind(rawValue: code),
              let id = try? r.bytes(MessageId.size),
              let key = try? r.bytes(X25519.publicKeySize),
              let body = try? r.string16(maxBytes: Murmur.maxTextBytes) else { return nil }
        r.skipRest() // later versions may append fields
        if kind == .text && body.isEmpty { return nil }
        return DmContent(kind: kind, messageId: MessageId.fromBytes(id), senderAgreementKey: key, body: body)
    }
}

/// Builds and opens PRIVATE packets.
public enum PrivateMessages {
    /// AAD = packetId || senderId || recipientId || timestamp (big-endian ms).
    public static func aad(packetId: PacketId, senderId: PeerId, recipientId: PeerId, timestamp: Int64) -> [UInt8] {
        var w = ByteWriter(capacity: 40)
        w.append(packetId.toBytes())
        w.append(senderId.toBytes())
        w.append(recipientId.toBytes())
        w.i64(timestamp)
        return w.bytes
    }

    /// Encrypts `content` for `recipientId` and signs the packet. Nil if the recipient key is unusable.
    public static func create(sender: Identity, recipientId: PeerId, recipientAgreementKey: [UInt8], content: DmContent,
                              ttl: Int, packetId: PacketId, timestamp: Int64, random: RandomSource) throws -> Packet? {
        guard !recipientId.isBroadcast else { throw PacketError("DM to broadcast") }
        let aad = aad(packetId: packetId, senderId: sender.peerId, recipientId: recipientId, timestamp: timestamp)
        guard let sealed = DmCrypto.seal(try content.encode(), recipientAgreementKey: recipientAgreementKey, aad: aad, random: random) else {
            return nil
        }
        return try PacketCodec.create(
            signing: sender.signing,
            payload: .privateMessage(ephemeralKey: sealed.ephemeralKey, nonce: sealed.nonce, ciphertext: sealed.ciphertext),
            recipientId: recipientId, ttl: ttl, packetId: packetId, timestamp: timestamp
        )
    }

    /// Decrypts a PRIVATE packet addressed to `me`. Nil if it isn't for me or fails authentication.
    public static func open(_ packet: Packet, me: Identity) -> DmContent? {
        guard case let .privateMessage(eph, nonce, ciphertext) = packet.payload, packet.recipientId == me.peerId else { return nil }
        let aad = aad(packetId: packet.packetId, senderId: packet.senderId, recipientId: packet.recipientId, timestamp: packet.timestamp)
        guard let plain = DmCrypto.open(DmCrypto.Sealed(ephemeralKey: eph, nonce: nonce, ciphertext: ciphertext),
                                        recipient: me.agreement, aad: aad) else { return nil }
        return DmContent.decode(plain)
    }
}
