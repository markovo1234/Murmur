import Foundation

/// Channel names: 1–24 of a–z, 0–9, '-' and '_'. "nearby" is reserved for the default room.
public enum Channels {
    public static let maxLength = 24
    public static let nearby = ""

    private static func isNameChar(_ c: Unicode.Scalar) -> Bool {
        ("a"..."z").contains(c) || ("0"..."9").contains(c) || c == "-" || c == "_"
    }

    public static func isValid(_ name: String) -> Bool {
        let scalars = name.unicodeScalars
        return !scalars.isEmpty && scalars.count <= maxLength && scalars.allSatisfy(isNameChar) && name != "nearby"
    }

    /// "#Night Owls" → "night-owls"; nil if nothing usable is left.
    public static func normalize(_ input: String) -> String? {
        var s = Murmur.kotlinTrim(input)
        if s.hasPrefix("#") { s.removeFirst() }
        s = s.lowercased()
        // Runs of whitespace become one '-' (Kotlin: replace(Regex("\\s+"), "-"); Java's \s is ASCII only).
        var collapsed = String.UnicodeScalarView()
        var inSpace = false
        for c in s.unicodeScalars {
            if c == " " || ("\u{09}"..."\u{0D}").contains(c) {
                if !inSpace { collapsed.append("-") }
                inSpace = true
            } else {
                collapsed.append(c)
                inSpace = false
            }
        }
        var out = String.UnicodeScalarView()
        for c in collapsed where isNameChar(c) {
            if out.count == maxLength { break }
            out.append(c)
        }
        let cleaned = String(out)
        return isValid(cleaned) ? cleaned : nil
    }
}

/// A channel invitation sent in an end-to-end encrypted DM: "1 <name> <64 hex key | ->". For a password
/// channel it carries the derived key (never the password), so the invitee can read it at once.
public enum ChannelInvites {
    public static let version = 1
    private static let keySize = 32

    public struct Invite: Equatable {
        public let channel: String
        public let key: [UInt8]?
        public var locked: Bool { key != nil }
    }

    public static func body(channel: String, key: [UInt8]?) -> String {
        precondition(Channels.isValid(channel), "bad channel")
        precondition(key == nil || key!.count == keySize, "bad key")
        return "\(version) \(channel) \(key.map(Hex.encode) ?? "-")"
    }

    /// Nil if malformed. Extra fields (later versions) are ignored.
    public static func parse(_ body: String) -> Invite? {
        let parts = Murmur.kotlinTrim(body).split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, Int(parts[0]) == version, Channels.isValid(parts[1]) else { return nil }
        if parts[2] == "-" { return Invite(channel: parts[1], key: nil) }
        guard let key = Hex.decode(parts[2]), key.count == keySize else { return nil }
        return Invite(channel: parts[1], key: key)
    }
}

public enum RoomKind: Int, Sendable {
    case text = 1
    /// target = the reacted-to message's packetId; body = emoji, or "" to remove.
    case reaction = 2
    /// Delete for everyone: target = the sender's own message.
    case retract = 3
    /// Emergency alert shown prominently to everyone in range. Body = optional short text.
    case sos = 4
}

/// The (possibly encrypted) content of a ROOM packet.
///
///     u8        kind
///     u8 + n    sender nickname
///     16        target packetId (zeros when unused)
///     u16 + n   body (UTF-8)
///     …         ignored (fields added by later versions)
public struct RoomContent: Equatable {
    public let kind: RoomKind
    public let nickname: String
    public let target: PacketId?
    public let body: String

    public init(kind: RoomKind, nickname: String, target: PacketId?, body: String) {
        self.kind = kind
        self.nickname = nickname
        self.target = target
        self.body = body
    }

    /// kind + empty nickname length + target + empty body length.
    public static let minSize = 1 + 1 + PacketId.size + 2

    public func encode() throws -> [UInt8] {
        guard Murmur.isValidNickname(nickname) else { throw PacketError("invalid nickname") }
        guard Murmur.utf8Size(body) <= Murmur.maxTextBytes else { throw PacketError("body too long") }
        if kind == .text && body.isEmpty { throw PacketError("empty text") }
        var w = ByteWriter(capacity: RoomContent.minSize + nickname.utf8.count + body.utf8.count)
        w.u8(kind.rawValue)
        w.string8(nickname)
        w.append(target?.toBytes() ?? [UInt8](repeating: 0, count: PacketId.size))
        w.string16(body)
        return w.bytes
    }

    /// Nil if malformed or of a kind this version doesn't know. Never throws.
    public static func decode(_ bytes: [UInt8]) -> RoomContent? {
        var r = ByteReader(bytes)
        guard let code = try? r.u8(),
              let nickname = try? r.string8(maxBytes: PacketCodec.maxNicknameBytes),
              let targetBytes = try? r.bytes(PacketId.size),
              let body = try? r.string16(maxBytes: Murmur.maxTextBytes) else { return nil }
        r.skipRest()
        let target = PacketId.fromBytes(targetBytes)
        let t: PacketId? = target == .zero ? nil : target
        guard let kind = RoomKind(rawValue: code), Murmur.isValidNickname(nickname) else { return nil }
        if kind == .text && body.isEmpty { return nil }
        if (kind == .reaction || kind == .retract) && t == nil { return nil }
        if kind == .reaction && Murmur.utf8Size(body) > Murmur.maxEmojiBytes { return nil }
        return RoomContent(kind: kind, nickname: nickname, target: t, body: body)
    }
}
