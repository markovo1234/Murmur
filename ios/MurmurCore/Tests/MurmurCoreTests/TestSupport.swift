import Foundation
@testable import MurmurCore

let baseTime: Int64 = 1_790_000_000_000

/// Virtual time: actions run only when a test advances the clock.
final class VirtualTime: Clock, Scheduler {
    private(set) var current: Int64
    private var tasks: [(at: Int64, seq: Int, item: Item)] = []
    private var seq = 0

    init(start: Int64 = baseTime) { current = start }

    func now() -> Int64 { current }

    final class Item: Cancellable {
        var cancelled = false
        let action: () -> Void
        init(_ action: @escaping () -> Void) { self.action = action }
        func cancel() { cancelled = true }
    }

    @discardableResult
    func schedule(afterMillis: Int64, _ action: @escaping () -> Void) -> Cancellable {
        let item = Item(action)
        seq += 1
        tasks.append((current + max(0, afterMillis), seq, item))
        return item
    }

    /// Runs everything due within `millis`, in time order, moving the clock as it goes.
    func advance(_ millis: Int64) {
        let end = current + millis
        while true {
            tasks.removeAll { $0.item.cancelled }
            guard let next = tasks.filter({ $0.at <= end }).min(by: { ($0.at, $0.seq) < ($1.at, $1.seq) }) else { break }
            tasks.removeAll { $0.seq == next.seq }
            current = next.at
            next.item.action()
        }
        current = end
    }
}

/// Deterministic, NOT secure.
final class SeededRandom: RandomSource {
    private var state: UInt64

    init(_ seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }

    func nextBytes(_ count: Int) -> [UInt8] {
        (0..<count).map { _ in
            // splitmix64
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return UInt8(truncatingIfNeeded: z ^ (z >> 31))
        }
    }
}

/// Replays recorded bytes in order (for the cross-platform vectors).
final class ReplayRandom: RandomSource {
    private var bytes: [UInt8]
    init(_ bytes: [UInt8]) { self.bytes = bytes }
    func nextBytes(_ count: Int) -> [UInt8] {
        precondition(bytes.count >= count, "replay ran out of bytes")
        defer { bytes.removeFirst(count) }
        return Array(bytes.prefix(count))
    }
}

/// An in-memory network of mesh nodes joined by links that deliver on the next scheduler turn.
final class MeshSim {
    let time = VirtualTime()
    let random = SeededRandom(42)
    private(set) var nodes: [String: MeshNode] = [:]
    private(set) var events: [String: [MeshEvent]] = [:]
    private var linkCounter = 0

    final class SimLink: MeshLink {
        let id: String
        weak var remote: MeshNode?
        var remoteLinkId = ""
        let time: VirtualTime
        var up = true
        /// Pretend to send but deliver nothing (a one-way radio problem).
        var dropAll = false
        var identified: PeerId?
        init(id: String, time: VirtualTime) {
            self.id = id
            self.time = time
        }
        func send(_ packet: [UInt8]) -> Bool {
            guard up else { return false }
            if dropAll { return true }
            let target = remote
            let linkId = remoteLinkId
            time.schedule(afterMillis: 10) { [weak self] in
                guard self?.up == true else { return }
                target?.received(linkId: linkId, bytes: packet)
            }
            return true
        }
        func onPeerIdentified(_ peerId: PeerId) { identified = peerId }
    }

    private var simLinks: [(SimLink, SimLink, String, String)] = []

    @discardableResult
    func add(_ name: String, config: MeshConfig = MeshConfig()) -> MeshNode {
        let node = MeshNode(identity: Identity.generate(random), profile: Profile(nickname: name, emoji: "🙂", colorIndex: 1),
                            clock: time, random: random, scheduler: time, config: config)
        events[name] = []
        node.onEvent = { [weak self] e in self?.events[name, default: []].append(e) }
        nodes[name] = node
        node.start()
        return node
    }

    func connect(_ a: String, _ b: String) {
        linkCounter += 1
        let la = SimLink(id: "\(a)-\(b)-\(linkCounter)", time: time)
        let lb = SimLink(id: "\(b)-\(a)-\(linkCounter)", time: time)
        la.remote = nodes[b]
        la.remoteLinkId = lb.id
        lb.remote = nodes[a]
        lb.remoteLinkId = la.id
        simLinks.append((la, lb, a, b))
        nodes[a]!.linkUp(la)
        nodes[b]!.linkUp(lb)
    }

    func disconnect(_ a: String, _ b: String) {
        for (la, lb, x, y) in simLinks where (x == a && y == b) || (x == b && y == a) {
            la.up = false
            lb.up = false
            nodes[x]!.linkDown(la.id)
            nodes[y]!.linkDown(lb.id)
        }
        simLinks.removeAll { ($0.2 == a && $0.3 == b) || ($0.2 == b && $0.3 == a) }
    }

    func run(_ millis: Int64 = 1_000) { time.advance(millis) }

    /// The link `from` uses to send to `to`.
    func link(from: String, to: String) -> SimLink {
        for (la, lb, x, y) in simLinks {
            if x == from && y == to { return la }
            if y == from && x == to { return lb }
        }
        preconditionFailure("no link \(from)-\(to)")
    }

    func id(_ name: String) -> PeerId { nodes[name]!.myId }
}

extension Array where Element == UInt8 {
    init(hex: String) { self = Hex.decode(hex)! }
}
