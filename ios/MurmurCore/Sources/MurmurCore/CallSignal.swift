import Foundation

/// Bodies of the CALL_OFFER / CALL_ANSWER / CALL_END direct messages (Android 1.2+). The iPhone app
/// doesn't place or take calls yet; it answers offers with `unsupported` so the caller hears right away
/// that this phone can't take calls instead of ringing into the void.
public enum CallSignal {
    public static let version = 1
    public static let codecAmrNb = "amrnb"

    public struct Offer: Equatable {
        public let codec: String
        public let key: [UInt8]
    }

    /// "1 amrnb <64 hex digits>".
    public static func offerBody(key: [UInt8], codec: String = codecAmrNb) -> String {
        precondition(key.count == CallCrypto.keySize)
        return "\(version) \(codec) \(Hex.encode(key))"
    }

    /// Nil if malformed or from an incompatible version. Extra fields are ignored.
    public static func parseOffer(_ body: String) -> Offer? {
        let parts = Murmur.kotlinTrim(body).split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, Int(parts[0]) == version, let key = Hex.decode(parts[2]),
              key.count == CallCrypto.keySize, !parts[1].isEmpty else { return nil }
        return Offer(codec: parts[1], key: key)
    }

    public enum Answer: String {
        case ringing, accept, decline, busy
        /// The callee's app can't do calls with this codec (or at all right now).
        case unsupported

        public var wire: String { rawValue }

        public static func fromWire(_ s: String) -> Answer? { Answer(rawValue: Murmur.kotlinTrim(s)) }
    }

    public enum EndReason: String {
        case hangup, cancel, timeout, failed

        public var wire: String { rawValue }

        public static func fromWire(_ s: String) -> EndReason { EndReason(rawValue: Murmur.kotlinTrim(s)) ?? .hangup }
    }
}
