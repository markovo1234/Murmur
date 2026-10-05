import Foundation

/// Splits one packet into link-sized fragments. Every fragment, header included, fits in the chunk size.
///
///     0  u8   marker 0x4D ('M')
///     1  u32  streamId   (per link, increments per packet)
///     5  u16  index      (0-based)
///     7  u16  count      (>= 1)
///     9  ...  data
public enum Fragmenter {
    public static let marker: UInt8 = 0x4D
    public static let headerSize = 9
    public static let minChunkSize = headerSize + 1
    public static let maxFragments = 0xFFFF

    /// BLE rule: fragments must fit min(MTU - 3, 512).
    public static func chunkSizeForMtu(_ mtu: Int) -> Int { max(minChunkSize, min(mtu - 3, 512)) }

    public static func fragment(_ data: [UInt8], chunkSize: Int, streamId: UInt32) -> [[UInt8]] {
        precondition(chunkSize >= minChunkSize, "chunk size too small: \(chunkSize)")
        precondition(!data.isEmpty, "nothing to fragment")
        let perFragment = chunkSize - headerSize
        let count = (data.count + perFragment - 1) / perFragment
        precondition(count <= maxFragments, "too many fragments: \(count)")
        return (0..<count).map { index in
            let start = index * perFragment
            let end = min(data.count, start + perFragment)
            var out: [UInt8] = [
                marker,
                UInt8(truncatingIfNeeded: streamId >> 24), UInt8(truncatingIfNeeded: streamId >> 16),
                UInt8(truncatingIfNeeded: streamId >> 8), UInt8(truncatingIfNeeded: streamId),
                UInt8(truncatingIfNeeded: index >> 8), UInt8(truncatingIfNeeded: index),
                UInt8(truncatingIfNeeded: count >> 8), UInt8(truncatingIfNeeded: count),
            ]
            out.append(contentsOf: data[start..<end])
            return out
        }
    }
}

/// Reassembles fragments from one link. Streams may interleave. A stream with no progress for
/// `timeoutMillis` is dropped (on the next fragment or `purgeExpired`). Not thread-safe: use it from
/// the owning link's queue.
public final class Reassembler {
    private final class Stream {
        let count: Int
        var lastActivity: Int64
        var parts: [[UInt8]?]
        var received = 0
        var bytes = 0

        init(count: Int, now: Int64) {
            self.count = count
            lastActivity = now
            parts = Array(repeating: nil, count: count)
        }
    }

    private let clock: Clock
    private let timeoutMillis: Int64
    private let maxStreams: Int
    private let maxStreamBytes: Int
    private var streams: [UInt32: Stream] = [:]
    private var order: [UInt32] = [] // insertion order, oldest first

    /// Fragments rejected as malformed.
    public private(set) var rejected = 0

    public var pendingStreams: Int { streams.count }
    public var pendingBytes: Int { streams.values.reduce(0) { $0 + $1.bytes } }

    public init(clock: Clock, timeoutMillis: Int64 = 30_000, maxStreams: Int = 32, maxStreamBytes: Int = 16 * 1024) {
        self.clock = clock
        self.timeoutMillis = timeoutMillis
        self.maxStreams = maxStreams
        self.maxStreamBytes = maxStreamBytes
    }

    /// The complete payload once the last missing fragment arrives, otherwise nil.
    public func accept(_ fragment: [UInt8]) -> [UInt8]? {
        let now = clock.now()
        purgeExpired(now: now)
        guard fragment.count >= Fragmenter.minChunkSize, fragment[0] == Fragmenter.marker else {
            rejected += 1
            return nil
        }
        let streamId = UInt32(fragment[1]) << 24 | UInt32(fragment[2]) << 16 | UInt32(fragment[3]) << 8 | UInt32(fragment[4])
        let index = Int(fragment[5]) << 8 | Int(fragment[6])
        let count = Int(fragment[7]) << 8 | Int(fragment[8])
        if count == 0 || index >= count || count > maxStreamBytes {
            rejected += 1
            return nil
        }
        let data = Array(fragment[Fragmenter.headerSize...])
        if count == 1 {
            remove(streamId)
            return data
        }
        var stream = streams[streamId]
        if let s = stream, s.count != count {
            // A new stream reusing the id (e.g. the counter wrapped after a reconnect): start over.
            remove(streamId)
            stream = nil
        }
        if stream == nil {
            if streams.count >= maxStreams, let oldest = order.first { remove(oldest) }
            let s = Stream(count: count, now: now)
            streams[streamId] = s
            order.append(streamId)
            stream = s
        }
        guard let s = stream else { return nil }
        s.lastActivity = now
        if s.parts[index] != nil { return nil } // duplicate
        if s.bytes + data.count > maxStreamBytes {
            remove(streamId)
            rejected += 1
            return nil
        }
        s.parts[index] = data
        s.received += 1
        s.bytes += data.count
        if s.received < count { return nil }
        remove(streamId)
        var out = [UInt8]()
        out.reserveCapacity(s.bytes)
        for part in s.parts { out.append(contentsOf: part!) }
        return out
    }

    public func purgeExpired() { purgeExpired(now: clock.now()) }

    public func clear() {
        streams.removeAll()
        order.removeAll()
    }

    private func purgeExpired(now: Int64) {
        if streams.isEmpty { return }
        for (id, s) in streams where now - s.lastActivity >= timeoutMillis { remove(id) }
    }

    private func remove(_ id: UInt32) {
        if streams.removeValue(forKey: id) != nil { order.removeAll { $0 == id } }
    }
}
