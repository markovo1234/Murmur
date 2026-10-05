import XCTest
@testable import MurmurCore

final class MeshNodeTests: XCTestCase {
    private func publicTexts(_ events: [MeshEvent]) -> [String] {
        events.compactMap { if case let .publicMessage(_, _, _, text, _, _) = $0 { return text } else { return nil } }
    }

    private func directBodies(_ events: [MeshEvent]) -> [String] {
        events.compactMap { if case let .directMessage(_, _, body, _, _) = $0 { return body } else { return nil } }
    }

    private func statuses(_ events: [MeshEvent], _ id: MessageId) -> [DeliveryStatus] {
        events.compactMap { if case let .delivery(m, _, s) = $0, m == id { return s } else { return nil } }
    }

    func testAnnounceIdentifiesLinksAndPeers() {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.connect("A", "B")
        sim.run()
        let a = sim.nodes["A"]!
        XCTAssertEqual(a.peers[sim.id("B")]?.nickname, "B")
        XCTAssertEqual(a.peers[sim.id("B")]?.status, .nearby)
        XCTAssertNotNil(a.peers[sim.id("B")]?.agreementKey)
        XCTAssertTrue(sim.events["A"]!.contains { if case .linkIdentified = $0 { return true } else { return false } })
    }

    func testPublicMessagesRelayThroughTheMiddleOnce() throws {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.add("C")
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.run()
        let relayedBefore = sim.nodes["B"]!.stats.relayed
        try sim.nodes["A"]!.sendPublic("hello mesh")
        sim.run()
        XCTAssertEqual(publicTexts(sim.events["B"]!), ["hello mesh"])
        XCTAssertEqual(publicTexts(sim.events["C"]!), ["hello mesh"])
        XCTAssertEqual(publicTexts(sim.events["A"]!), [], "no echo to the sender")
        let hops = sim.events["C"]!.compactMap { if case let .publicMessage(_, _, _, _, _, h) = $0 { return h } else { return nil } }
        XCTAssertEqual(hops, [2])
        XCTAssertEqual(sim.nodes["B"]!.stats.relayed - relayedBefore, 1, "B relays A's message once")
        XCTAssertEqual(sim.nodes["C"]!.peers[sim.id("A")]?.status, .viaMesh)
    }

    func testTriangleDedupes() throws {
        let sim = MeshSim()
        ["A", "B", "C"].forEach { sim.add($0) }
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.connect("A", "C")
        sim.run()
        try sim.nodes["A"]!.sendPublic("once")
        sim.run()
        XCTAssertEqual(publicTexts(sim.events["B"]!), ["once"])
        XCTAssertEqual(publicTexts(sim.events["C"]!), ["once"])
        XCTAssertGreaterThan(sim.nodes["C"]!.stats.droppedDuplicate, 0)
    }

    func testDirectMessageDeliveredAndRead() throws {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.add("C")
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.run()
        let id = MessageId(hi: 1, lo: 1)
        try sim.nodes["A"]!.sendDirectMessage(to: sim.id("C"), messageId: id, body: "secret")
        sim.run()
        XCTAssertEqual(directBodies(sim.events["C"]!), ["secret"])
        XCTAssertEqual(directBodies(sim.events["B"]!), [], "the relay can't read it")
        XCTAssertEqual(statuses(sim.events["A"]!, id), [.sending, .sent, .delivered])
        sim.nodes["C"]!.sendReadReceipt(to: sim.id("A"), messageId: id)
        sim.run()
        XCTAssertEqual(statuses(sim.events["A"]!, id).last, .read)
    }

    func testDirectMessageWaitsWhileOfflineAndFlushesOnReturn() throws {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.connect("A", "B")
        sim.run()
        sim.disconnect("A", "B")
        sim.run(100_000) // B goes offline
        let id = MessageId(hi: 2, lo: 2)
        try sim.nodes["A"]!.sendDirectMessage(to: sim.id("B"), messageId: id, body: "later")
        sim.run()
        XCTAssertEqual(statuses(sim.events["A"]!, id), [.pending])
        sim.connect("A", "B")
        sim.run()
        XCTAssertEqual(directBodies(sim.events["B"]!), ["later"])
        XCTAssertEqual(statuses(sim.events["A"]!, id).last, .delivered)
    }

    func testUnacknowledgedDmIsResentThenFails() throws {
        var config = MeshConfig()
        config.ackTimeout = 1_000
        let sim = MeshSim()
        let a = sim.add("A", config: config)
        sim.add("B")
        sim.connect("A", "B")
        sim.run()
        // From now on A's packets to B vanish (the link still reports success), so nothing gets acknowledged.
        sim.link(from: "A", to: "B").dropAll = true
        let id = MessageId(hi: 3, lo: 3)
        try a.sendDirectMessage(to: sim.id("B"), messageId: id, body: "hello?")
        sim.run(10_000)
        XCTAssertEqual(statuses(sim.events["A"]!, id), [.sending, .sent, .failed])
        XCTAssertEqual(directBodies(sim.events["B"]!), [])
    }

    func testChannelsEncryptAndAreDiscovered() throws {
        let sim = MeshSim()
        ["A", "B", "C"].forEach { sim.add($0) }
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.run()
        let key = ChannelCrypto.deriveKey(channel: "owls", password: "hoot", iterations: 1_000)
        sim.nodes["C"]!.setChannelKeys(["owls": key])
        try sim.nodes["A"]!.sendRoom(channel: "owls", kind: .text, body: "night", key: key)
        sim.run()
        let cRooms = sim.events["C"]!.compactMap { if case let .roomMessage(m) = $0 { return m.body } else { return nil } }
        XCTAssertEqual(cRooms, ["night"])
        let bRooms = sim.events["B"]!.compactMap { if case let .roomMessage(m) = $0 { return m.body } else { return nil } }
        XCTAssertEqual(bRooms, [], "no key, no message")
        let seen = sim.events["B"]!.compactMap { if case let .channelSeen(ch, enc, readable, _) = $0 { return "\(ch) \(enc) \(readable)" } else { return nil } }
        XCTAssertEqual(seen, ["owls true false"])
    }

    func testBlockedPeersAreRelayedButNotShown() throws {
        let sim = MeshSim()
        ["A", "B", "C"].forEach { sim.add($0) }
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.run()
        sim.nodes["B"]!.setBlocked([sim.id("A")])
        try sim.nodes["A"]!.sendPublic("hi")
        sim.run()
        XCTAssertEqual(publicTexts(sim.events["B"]!), [])
        XCTAssertEqual(publicTexts(sim.events["C"]!), ["hi"])
    }

    func testControlMessagesAndCallOffersSurface() throws {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.connect("A", "B")
        sim.run()
        let callId = MessageId(hi: 9, lo: 9)
        XCTAssertTrue(sim.nodes["A"]!.sendDirectControl(to: sim.id("B"), kind: .callOffer, messageId: callId,
                                                       body: CallSignal.offerBody(key: [UInt8](repeating: 7, count: 32))))
        sim.run()
        let controls = sim.events["B"]!.compactMap { if case let .directControl(_, kind, id, _, _) = $0 { return (kind, id) } else { return nil } }
        XCTAssertEqual(controls.map(\.0), [.callOffer])
        XCTAssertEqual(controls.first?.1, callId)
    }

    func testLeaveMarksPeerOffline() {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.add("C")
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.run()
        sim.nodes["A"]!.sendLeave()
        sim.run()
        XCTAssertEqual(sim.nodes["C"]!.peers[sim.id("A")]?.status, .offline)
    }

    func testStaleAndFuturePacketsAreDropped() throws {
        let sim = MeshSim()
        sim.add("A")
        sim.add("B")
        sim.connect("A", "B")
        sim.run()
        let a = sim.nodes["A"]!
        let old = try PacketCodec.create(signing: a.identity.signing, payload: .publicMessage(nickname: "A", text: "old"), recipientId: .broadcast,
                                         ttl: 7, packetId: .random(sim.random), timestamp: sim.time.now() - 13 * 3_600_000)
        let b = sim.nodes["B"]!
        let linkId = b.peers[sim.id("A")]!.linkIds.first!
        b.received(linkId: linkId, bytes: old.encode())
        XCTAssertEqual(publicTexts(sim.events["B"]!), [])
        XCTAssertGreaterThan(b.stats.droppedInvalid, 0)
    }
}
