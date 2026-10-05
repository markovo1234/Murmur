import Foundation

/// Thrown by `ByteReader`; codecs catch it and turn it into a decode error.
struct DecodeError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

/// Big-endian writer (same layout as the Kotlin `ByteWriter`).
struct ByteWriter {
    private(set) var bytes: [UInt8] = []

    init(capacity: Int = 256) { bytes.reserveCapacity(capacity) }

    mutating func u8(_ v: Int) {
        precondition(v >= 0 && v <= 0xFF, "u8 out of range: \(v)")
        bytes.append(UInt8(v))
    }

    mutating func u16(_ v: Int) {
        precondition(v >= 0 && v <= 0xFFFF, "u16 out of range: \(v)")
        bytes.append(UInt8(v >> 8))
        bytes.append(UInt8(v & 0xFF))
    }

    mutating func u32(_ v: UInt32) {
        for i in stride(from: 3, through: 0, by: -1) { bytes.append(UInt8(truncatingIfNeeded: v >> (8 * UInt32(i)))) }
    }

    mutating func i64(_ v: Int64) {
        let u = UInt64(bitPattern: v)
        for i in stride(from: 7, through: 0, by: -1) { bytes.append(UInt8(truncatingIfNeeded: u >> (8 * UInt64(i)))) }
    }

    mutating func append(_ b: [UInt8]) { bytes.append(contentsOf: b) }

    /// u8 length prefix + UTF-8 bytes.
    mutating func string8(_ s: String) {
        let b = Array(s.utf8)
        u8(b.count)
        append(b)
    }

    /// u16 length prefix + UTF-8 bytes.
    mutating func string16(_ s: String) {
        let b = Array(s.utf8)
        u16(b.count)
        append(b)
    }
}

/// Big-endian reader over a byte array (same semantics as the Kotlin `ByteReader`).
struct ByteReader {
    private let data: [UInt8]
    private var pos: Int
    private let end: Int

    init(_ data: [UInt8]) {
        self.data = data
        pos = 0
        end = data.count
    }

    var remaining: Int { end - pos }

    private func need(_ n: Int) throws {
        if n < 0 || remaining < n { throw DecodeError("truncated: need \(n), have \(remaining)") }
    }

    mutating func u8() throws -> Int {
        try need(1)
        defer { pos += 1 }
        return Int(data[pos])
    }

    mutating func u16() throws -> Int {
        try need(2)
        defer { pos += 2 }
        return Int(data[pos]) << 8 | Int(data[pos + 1])
    }

    mutating func u32() throws -> UInt32 {
        try need(4)
        var v: UInt32 = 0
        for _ in 0..<4 {
            v = v << 8 | UInt32(data[pos])
            pos += 1
        }
        return v
    }

    mutating func i64() throws -> Int64 {
        try need(8)
        var v: UInt64 = 0
        for _ in 0..<8 {
            v = v << 8 | UInt64(data[pos])
            pos += 1
        }
        return Int64(bitPattern: v)
    }

    mutating func bytes(_ n: Int) throws -> [UInt8] {
        try need(n)
        defer { pos += n }
        return Array(data[pos..<(pos + n)])
    }

    mutating func rest() throws -> [UInt8] { try bytes(remaining) }

    mutating func string8(maxBytes: Int) throws -> String {
        let n = try u8()
        if n > maxBytes { throw DecodeError("string too long: \(n) > \(maxBytes)") }
        return try ByteReader.strictUtf8(bytes(n))
    }

    mutating func string16(maxBytes: Int) throws -> String {
        let n = try u16()
        if n > maxBytes { throw DecodeError("string too long: \(n) > \(maxBytes)") }
        return try ByteReader.strictUtf8(bytes(n))
    }

    /// Ignores the remaining bytes (fields added by later protocol versions).
    mutating func skipRest() { pos = end }

    func expectEnd() throws {
        if remaining != 0 { throw DecodeError("\(remaining) trailing bytes") }
    }

    /// Rejects malformed UTF-8 so every accepted string re-encodes to the identical bytes.
    static func strictUtf8(_ bytes: [UInt8]) throws -> String {
        // String(validating:) is iOS 18+; decode by hand and reject any repair.
        var it = bytes.makeIterator()
        var decoder = UTF8()
        var scalars = String.UnicodeScalarView()
        loop: while true {
            switch decoder.decode(&it) {
            case let .scalarValue(s): scalars.append(s)
            case .emptyInput: break loop
            case .error: throw DecodeError("invalid UTF-8")
            }
        }
        return String(scalars)
    }
}

/// Hex and byte helpers (Kotlin `Bytes`).
public enum Hex {
    private static let digits = Array("0123456789abcdef".utf8)

    public static func encode(_ bytes: [UInt8]) -> String {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count * 2)
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    public static func decode(_ hex: String) -> [UInt8]? {
        let chars = Array(hex.utf8)
        if chars.count % 2 != 0 { return nil }
        var out = [UInt8]()
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = value(chars[i]), let lo = value(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    private static func value(_ c: UInt8) -> UInt8? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return c - UInt8(ascii: "A") + 10
        default: return nil
        }
    }

    /// Unsigned lexicographic comparison.
    public static func compare(_ a: [UInt8], _ b: [UInt8]) -> Int {
        for i in 0..<min(a.count, b.count) where a[i] != b[i] {
            return Int(a[i]) - Int(b[i])
        }
        return a.count - b.count
    }

    static func uint64(_ b: [UInt8], _ offset: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<8 { v = v << 8 | UInt64(b[offset + i]) }
        return v
    }

    static func bytes(_ v: UInt64) -> [UInt8] {
        (0..<8).map { UInt8(truncatingIfNeeded: v >> (56 - 8 * UInt64($0))) }
    }
}
