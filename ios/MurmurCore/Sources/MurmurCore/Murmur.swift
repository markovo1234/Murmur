import Foundation

/// Protocol-wide constants shared by the mesh and the Bluetooth transport (same values as the Kotlin
/// `app.murmur.core.Murmur`).
public enum Murmur {
    /// Wire protocol version (first byte of every packet).
    public static let protocolVersion = 1

    /// Every packet type starts at this ttl. hops = initialTtl + 1 - received ttl.
    public static let initialTtl = 7

    /// The single Murmur GATT service.
    public static let serviceUUID = "8a51d968-575d-4be3-871d-5400a225aa2d"

    /// The one characteristic (WRITE + NOTIFY) inside the service.
    public static let characteristicUUID = "4a05bee7-c4c9-45a1-b06d-72d674c025b1"

    public static let nicknameMinChars = 1
    public static let nicknameMaxChars = 20
    public static let maxTextBytes = 1000
    public static let maxEmojiBytes = 32
    public static let avatarColorCount = 10

    /// Unicode code points (what Kotlin's `codePointCount` returns).
    public static func nicknameLength(_ nickname: String) -> Int { nickname.unicodeScalars.count }

    public static func isValidNickname(_ nickname: String) -> Bool {
        if kotlinTrim(nickname) != nickname { return false }
        let n = nicknameLength(nickname)
        return n >= nicknameMinChars && n <= nicknameMaxChars && !nickname.unicodeScalars.contains(where: isISOControl)
    }

    public static func utf8Size(_ text: String) -> Int { text.utf8.count }

    /// Kotlin `Char.isISOControl`: U+0000–U+001F and U+007F–U+009F.
    static func isISOControl(_ s: Unicode.Scalar) -> Bool {
        s.value <= 0x1F || (s.value >= 0x7F && s.value <= 0x9F)
    }

    /// Kotlin `Char.isWhitespace`: Unicode space/line/paragraph separators plus \t \n \u{0B} \f \r and
    /// U+001C–U+001F.
    static func isKotlinWhitespace(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x09...0x0D, 0x1C...0x1F: return true
        default:
            switch s.properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }

    /// Kotlin `String.trim()`.
    static func kotlinTrim(_ s: String) -> String {
        let scalars = Array(s.unicodeScalars)
        var start = 0
        var end = scalars.count
        while start < end && isKotlinWhitespace(scalars[start]) { start += 1 }
        while end > start && isKotlinWhitespace(scalars[end - 1]) { end -= 1 }
        var out = String.UnicodeScalarView()
        out.append(contentsOf: scalars[start..<end])
        return String(out)
    }
}

/// Wall clock in epoch milliseconds. Injected everywhere so tests can run on virtual time.
public protocol Clock: AnyObject {
    func now() -> Int64
}

public final class SystemClock: Clock {
    public init() {}
    public func now() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }
}

/// Source of random bytes.
public protocol RandomSource: AnyObject {
    func nextBytes(_ count: Int) -> [UInt8]
}

/// Cryptographically secure (the system generator is a CSPRNG on Apple platforms).
public final class SecureRandomSource: RandomSource {
    public init() {}
    public func nextBytes(_ count: Int) -> [UInt8] {
        var g = SystemRandomNumberGenerator()
        return (0..<count).map { _ in UInt8.random(in: 0...255, using: &g) }
    }
}

/// A handle to a scheduled action.
public protocol Cancellable: AnyObject {
    func cancel()
}

/// Timers for the mesh. Every action runs on the mesh's serial queue.
public protocol Scheduler: AnyObject {
    @discardableResult
    func schedule(afterMillis: Int64, _ action: @escaping () -> Void) -> Cancellable
}

/// Runs actions on a serial dispatch queue (production).
public final class QueueScheduler: Scheduler {
    private let queue: DispatchQueue

    public init(queue: DispatchQueue) { self.queue = queue }

    private final class Item: Cancellable {
        let work: DispatchWorkItem
        init(_ work: DispatchWorkItem) { self.work = work }
        func cancel() { work.cancel() }
    }

    @discardableResult
    public func schedule(afterMillis: Int64, _ action: @escaping () -> Void) -> Cancellable {
        let work = DispatchWorkItem(block: action)
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(max(0, afterMillis))), execute: work)
        return Item(work)
    }
}
