import Foundation

/// 8-byte peer identifier = first 8 bytes of SHA-256(Ed25519 public key). Ordering is unsigned
/// big-endian, i.e. the same as comparing the raw bytes.
public struct PeerId: Hashable, Comparable, CustomStringConvertible, Sendable {
    public let raw: UInt64

    public init(raw: UInt64) { self.raw = raw }

    public static let size = 8
    public static let broadcast = PeerId(raw: .max)

    public var isBroadcast: Bool { raw == .max }

    public func toBytes() -> [UInt8] { Hex.bytes(raw) }
    public func toHex() -> String { Hex.encode(toBytes()) }

    /// Last 4 hex digits, used as a "#1a2b" tag when nicknames collide.
    public var shortTag: String { String(toHex().suffix(4)) }

    public var description: String { toHex() }

    public static func < (a: PeerId, b: PeerId) -> Bool { a.raw < b.raw }

    public static func fromBytes(_ bytes: [UInt8], offset: Int = 0) -> PeerId {
        PeerId(raw: Hex.uint64(bytes, offset))
    }

    public static func fromPublicKey(_ ed25519PublicKey: [UInt8]) -> PeerId {
        fromBytes(Sha256.hash(ed25519PublicKey))
    }

    public static func fromHex(_ hex: String) -> PeerId? {
        guard hex.utf8.count == size * 2, let b = Hex.decode(hex) else { return nil }
        return fromBytes(b)
    }
}

/// 16-byte identifier (packets and messages share the layout).
public struct Id128: Hashable, CustomStringConvertible, Sendable {
    public let hi: UInt64
    public let lo: UInt64

    public init(hi: UInt64, lo: UInt64) {
        self.hi = hi
        self.lo = lo
    }

    public static let size = 16
    public static let zero = Id128(hi: 0, lo: 0)

    public func toBytes() -> [UInt8] { Hex.bytes(hi) + Hex.bytes(lo) }
    public func toHex() -> String { Hex.encode(toBytes()) }
    public var description: String { toHex() }

    public static func fromBytes(_ bytes: [UInt8], offset: Int = 0) -> Id128 {
        Id128(hi: Hex.uint64(bytes, offset), lo: Hex.uint64(bytes, offset + 8))
    }

    public static func random(_ random: RandomSource) -> Id128 { fromBytes(random.nextBytes(size)) }

    public static func fromHex(_ hex: String) -> Id128? {
        guard hex.utf8.count == size * 2, let b = Hex.decode(hex) else { return nil }
        return fromBytes(b)
    }
}

/// 16-byte random packet identifier.
public typealias PacketId = Id128

/// 16-byte message identifier, stable across resends of the same DM.
public typealias MessageId = Id128
