import Foundation

public enum PacketType: Int, Sendable {
    case announce = 1
    case publicMessage = 2
    case privateMessage = 3
    case leave = 4
    /// Since 1.1: #nearby extras (reactions, retractions, SOS) and named channels.
    case room = 5
    /// Since 1.2: one chunk of encrypted call audio, addressed to one peer.
    case call = 6
    /// A type this version doesn't know. Verified and relayed, never delivered (forward compatibility).
    case unknown = 0

    static func fromCode(_ code: Int) -> PacketType? {
        guard let t = PacketType(rawValue: code), t != .unknown else { return nil }
        return t
    }
}

/// Typed packet payloads. Layouts are documented in PROTOCOL.md.
public enum Payload: Equatable {
    /// Who I am and how to encrypt to me.
    case announce(nickname: String, emoji: String, colorIndex: Int, agreementKey: [UInt8])
    /// A #nearby message.
    case publicMessage(nickname: String, text: String)
    /// An end-to-end encrypted `DmContent`. Relays cannot read it.
    case privateMessage(ephemeralKey: [UInt8], nonce: [UInt8], ciphertext: [UInt8])
    /// Graceful shutdown. Empty.
    case leave
    /// A message in #nearby (channel "") or a named channel; `body` is a `RoomContent`, or nonce ||
    /// ChaCha20-Poly1305 ciphertext of one when `encrypted`.
    case room(channel: String, encrypted: Bool, body: [UInt8])
    /// ~80 ms of voice, sealed with the call's session key.
    case call(callId: MessageId, seq: UInt32, sealed: [UInt8])
    /// A packet type this version doesn't understand, kept verbatim so it can be relayed.
    case unknown(code: Int, bytes: [UInt8])

    public var type: PacketType {
        switch self {
        case .announce: return .announce
        case .publicMessage: return .publicMessage
        case .privateMessage: return .privateMessage
        case .leave: return .leave
        case .room: return .room
        case .call: return .call
        case .unknown: return .unknown
        }
    }

    /// The type byte on the wire.
    public var typeCode: Int {
        if case let .unknown(code, _) = self { return code }
        return type.rawValue
    }
}

/// A decoded (or freshly built) packet. `raw` holds the exact wire bytes the signature covers, so
/// relays forward the original bytes with only the ttl byte changed.
public struct Packet: CustomStringConvertible {
    public let version: Int
    public let ttl: Int
    public let packetId: PacketId
    public let senderId: PeerId
    public let recipientId: PeerId
    public let timestamp: Int64
    public let senderKey: [UInt8]
    public let payload: Payload
    public let signature: [UInt8]
    let raw: [UInt8]

    public var type: PacketType { payload.type }
    public var isBroadcast: Bool { recipientId.isBroadcast }

    /// 1 for a packet heard directly from its sender.
    public var hops: Int { Murmur.initialTtl + 1 - ttl }

    /// Wire bytes.
    public func encode() -> [UInt8] { raw }

    /// Same packet with a new ttl. The signature stays valid because it excludes the ttl byte.
    public func withTtl(_ newTtl: Int) -> Packet {
        precondition(newTtl >= 1 && newTtl <= Murmur.initialTtl)
        var bytes = raw
        bytes[PacketCodec.ttlOffset] = UInt8(newTtl)
        return Packet(version: version, ttl: newTtl, packetId: packetId, senderId: senderId, recipientId: recipientId,
                      timestamp: timestamp, senderKey: senderKey, payload: payload, signature: signature, raw: bytes)
    }

    public var description: String {
        "Packet(\(type) ttl=\(ttl) id=\(packetId.toHex().prefix(8)) from=\(senderId) to=\(recipientId))"
    }
}

public enum DecodeResult {
    case ok(Packet)
    case error(String)
}

public enum VerifyResult { case ok, senderKeyMismatch, badSignature }

/// Thrown when building a packet from invalid input (a bug in the caller, or unchecked user input).
public struct PacketError: Error, CustomStringConvertible {
    public let description: String
    init(_ description: String) { self.description = description }
}

public enum PacketCodec {
    public static let ttlOffset = 2
    public static let headerSize = 77
    public static let signatureSize = Ed25519.signatureSize
    public static let overhead = headerSize + signatureSize
    public static let maxPayload = 2048
    public static let maxPacket = overhead + maxPayload
    public static let maxNicknameBytes = 80
    public static let maxCallSeq: UInt32 = .max

    private static let roomFlagEncrypted = 0x01
    /// Smallest valid PRIVATE ciphertext: an empty-bodied DmContent plus the Poly1305 tag.
    private static let minPrivateCiphertext = DmContent.minSize + DmCrypto.tagSize
    private static let minEncryptedRoom = DmCrypto.nonceSize + DmCrypto.tagSize + RoomContent.minSize

    /// Builds and signs a packet.
    public static func create(signing: SigningKeyPair, payload: Payload, recipientId: PeerId, ttl: Int,
                              packetId: PacketId, timestamp: Int64) throws -> Packet {
        try assemble(senderId: PeerId.fromPublicKey(signing.publicKey), signing: signing, payload: payload,
                     recipientId: recipientId, ttl: ttl, packetId: packetId, timestamp: timestamp)
    }

    /// Like `create` but with an arbitrary senderId; only tests use a mismatching one.
    static func assemble(senderId: PeerId, signing: SigningKeyPair, payload: Payload, recipientId: PeerId, ttl: Int,
                         packetId: PacketId, timestamp: Int64) throws -> Packet {
        guard ttl >= 1 && ttl <= Murmur.initialTtl else { throw PacketError("ttl out of range: \(ttl)") }
        let body = try unsignedBytes(senderId: senderId, publicKey: signing.publicKey, payload: payload,
                                     recipientId: recipientId, ttl: ttl, packetId: packetId, timestamp: timestamp)
        let signature = signing.sign(Array(body[0..<ttlOffset]), Array(body[(ttlOffset + 1)...]))
        return Packet(version: Murmur.protocolVersion, ttl: ttl, packetId: packetId, senderId: senderId,
                      recipientId: recipientId, timestamp: timestamp, senderKey: signing.publicKey, payload: payload,
                      signature: signature, raw: body + signature)
    }

    /// Header and payload, without the signature (what the signature covers, ttl aside).
    static func unsignedBytes(senderId: PeerId, publicKey: [UInt8], payload: Payload, recipientId: PeerId, ttl: Int,
                              packetId: PacketId, timestamp: Int64) throws -> [UInt8] {
        let payloadBytes = try encodePayload(payload)
        guard payloadBytes.count <= maxPayload else { throw PacketError("payload too large: \(payloadBytes.count)") }
        var w = ByteWriter(capacity: overhead + payloadBytes.count)
        w.u8(Murmur.protocolVersion)
        w.u8(payload.typeCode)
        w.u8(ttl)
        w.append(packetId.toBytes())
        w.append(senderId.toBytes())
        w.append(recipientId.toBytes())
        w.i64(timestamp)
        w.append(publicKey)
        w.u16(payloadBytes.count)
        w.append(payloadBytes)
        return w.bytes
    }

    /// Structural decode. Never throws; does not check the signature (see `verify`).
    public static func decode(_ bytes: [UInt8]) -> DecodeResult {
        do {
            return .ok(try decodeOrThrow(bytes))
        } catch let e as DecodeError {
            return .error(e.description)
        } catch {
            return .error("malformed")
        }
    }

    /// senderId must equal the hash of the included key, and the signature must cover everything but ttl.
    public static func verify(_ packet: Packet) -> VerifyResult {
        if PeerId.fromPublicKey(packet.senderKey) != packet.senderId { return .senderKeyMismatch }
        let raw = packet.raw
        let sigStart = raw.count - signatureSize
        let ok = Ed25519.verify(publicKey: packet.senderKey, signature: packet.signature,
                                Array(raw[0..<ttlOffset]), Array(raw[(ttlOffset + 1)..<sigStart]))
        return ok ? .ok : .badSignature
    }

    /// Reads the type byte without a full decode.
    public static func peekType(_ bytes: [UInt8]) -> PacketType? {
        bytes.count > 1 ? PacketType.fromCode(Int(bytes[1])) : nil
    }

    private static func decodeOrThrow(_ bytes: [UInt8]) throws -> Packet {
        if bytes.count < overhead { throw DecodeError("too short: \(bytes.count)") }
        if bytes.count > maxPacket { throw DecodeError("too long: \(bytes.count)") }
        var r = ByteReader(bytes)
        let version = try r.u8()
        if version != Murmur.protocolVersion { throw DecodeError("unsupported version \(version)") }
        let typeCode = try r.u8()
        let type = PacketType.fromCode(typeCode) ?? .unknown
        let ttl = try r.u8()
        if ttl < 1 || ttl > Murmur.initialTtl { throw DecodeError("bad ttl \(ttl)") }
        let packetId = PacketId.fromBytes(try r.bytes(PacketId.size))
        let senderId = PeerId.fromBytes(try r.bytes(PeerId.size))
        let recipientId = PeerId.fromBytes(try r.bytes(PeerId.size))
        let timestamp = try r.i64()
        let senderKey = try r.bytes(Ed25519.publicKeySize)
        let length = try r.u16()
        if length > maxPayload || bytes.count != overhead + length { throw DecodeError("length mismatch") }
        let payloadBytes = try r.bytes(length)
        let signature = try r.bytes(signatureSize)
        try r.expectEnd()

        if senderId.isBroadcast { throw DecodeError("broadcast sender") }
        switch type {
        case .privateMessage, .call:
            if recipientId.isBroadcast { throw DecodeError("\(type) to broadcast") }
        case .unknown:
            break
        default:
            if !recipientId.isBroadcast { throw DecodeError("\(type) must be broadcast") }
        }
        let payload = type == .unknown ? Payload.unknown(code: typeCode, bytes: payloadBytes) : try decodePayload(type, payloadBytes)
        return Packet(version: version, ttl: ttl, packetId: packetId, senderId: senderId, recipientId: recipientId,
                      timestamp: timestamp, senderKey: senderKey, payload: payload, signature: signature, raw: bytes)
    }

    public static func encodePayload(_ payload: Payload) throws -> [UInt8] {
        var w = ByteWriter()
        switch payload {
        case let .announce(nickname, emoji, colorIndex, agreementKey):
            guard Murmur.isValidNickname(nickname) else { throw PacketError("invalid nickname") }
            guard agreementKey.count == X25519.publicKeySize else { throw PacketError("bad agreement key") }
            guard colorIndex >= 0 && colorIndex < Murmur.avatarColorCount else { throw PacketError("bad color") }
            guard Murmur.utf8Size(emoji) <= Murmur.maxEmojiBytes else { throw PacketError("bad emoji") }
            w.string8(nickname)
            w.string8(emoji)
            w.u8(colorIndex)
            w.append(agreementKey)
        case let .publicMessage(nickname, text):
            guard Murmur.isValidNickname(nickname) else { throw PacketError("invalid nickname") }
            guard !text.isEmpty && Murmur.utf8Size(text) <= Murmur.maxTextBytes else { throw PacketError("bad text") }
            w.string8(nickname)
            w.string16(text)
        case let .privateMessage(ephemeralKey, nonce, ciphertext):
            w.append(ephemeralKey)
            w.append(nonce)
            w.append(ciphertext)
        case .leave:
            break
        case let .room(channel, encrypted, body):
            guard channel.isEmpty || Channels.isValid(channel) else { throw PacketError("bad channel") }
            guard !body.isEmpty else { throw PacketError("empty room body") }
            w.u8(encrypted ? roomFlagEncrypted : 0)
            w.string8(channel)
            w.append(body)
        case let .call(callId, seq, sealed):
            guard sealed.count >= DmCrypto.tagSize + 1 else { throw PacketError("empty audio") }
            w.append(callId.toBytes())
            w.u32(seq)
            w.append(sealed)
        case let .unknown(_, bytes):
            w.append(bytes)
        }
        return w.bytes
    }

    private static func decodePayload(_ type: PacketType, _ bytes: [UInt8]) throws -> Payload {
        var r = ByteReader(bytes)
        let payload: Payload
        switch type {
        case .announce:
            let nickname = try readNickname(&r)
            let emoji = try r.string8(maxBytes: Murmur.maxEmojiBytes)
            if emoji.isEmpty || emoji.unicodeScalars.contains(where: Murmur.isISOControl) { throw DecodeError("bad emoji") }
            let color = try r.u8()
            if color >= Murmur.avatarColorCount { throw DecodeError("bad color \(color)") }
            payload = .announce(nickname: nickname, emoji: emoji, colorIndex: color, agreementKey: try r.bytes(X25519.publicKeySize))
            r.skipRest()
        case .publicMessage:
            let nickname = try readNickname(&r)
            let text = try r.string16(maxBytes: Murmur.maxTextBytes)
            if text.isEmpty { throw DecodeError("empty text") }
            r.skipRest() // later versions may append fields
            payload = .publicMessage(nickname: nickname, text: text)
        case .privateMessage:
            let eph = try r.bytes(X25519.publicKeySize)
            let nonce = try r.bytes(DmCrypto.nonceSize)
            let ciphertext = try r.rest()
            if ciphertext.count < minPrivateCiphertext { throw DecodeError("ciphertext too short") }
            payload = .privateMessage(ephemeralKey: eph, nonce: nonce, ciphertext: ciphertext)
        case .leave:
            r.skipRest()
            payload = .leave
        case .room:
            let flags = try r.u8()
            let channel = try r.string8(maxBytes: Channels.maxLength)
            if !channel.isEmpty && !Channels.isValid(channel) { throw DecodeError("bad channel") }
            let body = try r.rest()
            let encrypted = flags & roomFlagEncrypted != 0
            if body.isEmpty || (encrypted && body.count < minEncryptedRoom) { throw DecodeError("bad room body") }
            payload = .room(channel: channel, encrypted: encrypted, body: body)
        case .call:
            let callId = MessageId.fromBytes(try r.bytes(MessageId.size))
            let seq = try r.u32()
            let sealed = try r.rest()
            if sealed.count < DmCrypto.tagSize + 1 { throw DecodeError("empty audio") }
            payload = .call(callId: callId, seq: seq, sealed: sealed)
        case .unknown:
            throw DecodeError("unknown type")
        }
        try r.expectEnd()
        return payload
    }

    private static func readNickname(_ r: inout ByteReader) throws -> String {
        let nickname = try r.string8(maxBytes: maxNicknameBytes)
        if !Murmur.isValidNickname(nickname) { throw DecodeError("bad nickname") }
        return nickname
    }
}
