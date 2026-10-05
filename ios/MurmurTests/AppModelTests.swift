import Foundation
import MurmurCore
import XCTest
@testable import Murmur

/// Whole phones (AppModel + mesh) talking over a fake radio, on virtual time.
final class AppModelTests: XCTestCase {
    private var radio: FakeRadio!

    override func setUp() {
        radio = FakeRadio()
    }

    private func phone(_ name: String, seed: UInt64) -> (AppModel, RecordingNotifier) {
        let notifier = RecordingNotifier()
        let time = radio.time
        let model = AppModel(
            clock: time, random: TestRandom(seed), scheduler: time, post: { time.schedule(afterMillis: 0, $0) },
            file: SnapshotFile(url: nil), identityStore: MemoryIdentityStore(), notifier: notifier,
            makeTransport: { [radio] _ in radio!.makeTransport() }
        )
        model.completeOnboarding(Profile(nickname: name, emoji: "🦊", colorIndex: 2))
        return (model, notifier)
    }

    private func settle(_ millis: Int64 = 2_000) { radio.time.advance(millis) }

    private func texts(_ m: AppModel, _ cid: String) -> [String] { (m.messages[cid] ?? []).filter { $0.kind == .text }.map(\.body) }

    func testPhonesFindEachOtherAndTalkInNearby() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, bNotes) = phone("Bo", seed: 2)
        settle()
        XCTAssertEqual(a.peers[b.myHex]?.name, "Bo")
        XCTAssertEqual(a.status(of: b.myHex), .nearby)
        XCTAssertEqual(a.send("hello everyone", in: ConversationId.nearby), .sent)
        settle()
        XCTAssertEqual(texts(b, ConversationId.nearby), ["hello everyone"])
        XCTAssertEqual(b.conversations[ConversationId.nearby]?.unread, 1)
        XCTAssertTrue(bNotes.items.isEmpty, "plain #nearby messages don't notify by default")
        XCTAssertEqual(a.send("@Bo look", in: ConversationId.nearby), .sent)
        settle()
        XCTAssertEqual(bNotes.items.count, 1, "an @mention notifies")
        XCTAssertTrue(b.messages[ConversationId.nearby]!.last!.mentionsMe)
    }

    func testDirectMessageDeliveredThenRead() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, bNotes) = phone("Bo", seed: 2)
        settle()
        XCTAssertEqual(a.send("secret plan", in: b.myHex), .sent)
        settle()
        XCTAssertEqual(texts(b, a.myHex), ["secret plan"])
        XCTAssertEqual(a.messages[b.myHex]?.last?.delivery, .delivered)
        XCTAssertEqual(bNotes.items.map(\.body), ["secret plan"])
        b.setOpenConversation(a.myHex)
        settle()
        XCTAssertEqual(a.messages[b.myHex]?.last?.delivery, .read)
        XCTAssertEqual(b.conversations[a.myHex]?.unread, 0)
    }

    func testReadReceiptsCanBeTurnedOff() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        settle()
        b.setReadReceipts(false)
        a.send("hi", in: b.myHex)
        settle()
        b.setOpenConversation(a.myHex)
        settle()
        XCTAssertEqual(a.messages[b.myHex]?.last?.delivery, .delivered)
    }

    func testDmWrittenWhileMeshIsOffGoesOutLater() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        settle()
        a.stopMesh()
        settle(100_000)
        a.send("queued", in: b.myHex)
        XCTAssertEqual(a.messages[b.myHex]?.last?.delivery, .pending)
        a.startMesh()
        settle(5_000)
        XCTAssertEqual(texts(b, a.myHex), ["queued"])
        XCTAssertEqual(a.messages[b.myHex]?.last?.delivery, .delivered)
    }

    @MainActor
    func testPasswordChannelOnlyForPeopleWithThePassword() async {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        let (c, _) = phone("Cy", seed: 3)
        settle()
        let joinedA = await a.joinChannel("#Night Owls", password: "hoot")
        let joinedB = await b.joinChannel("night-owls", password: "hoot")
        let joinedC = await c.joinChannel("night owls", password: "wrong")
        XCTAssertEqual(joinedA, .joined("night-owls"))
        XCTAssertEqual(joinedB, .joined("night-owls"))
        XCTAssertEqual(joinedC, .joined("night-owls"))
        XCTAssertEqual(a.send("owls only", in: ConversationId.channel("night-owls")), .sent)
        settle()
        XCTAssertEqual(texts(b, ConversationId.channel("night-owls")), ["owls only"])
        XCTAssertEqual(texts(c, ConversationId.channel("night-owls")), [])
        XCTAssertEqual(c.nearbyChannels.values.first?.readable, false)
    }

    @MainActor
    func testInviteCarriesThePasswordKey() async {
        let (a, _) = phone("Ada", seed: 1)
        let (b, bNotes) = phone("Bo", seed: 2)
        settle()
        _ = await a.joinChannel("vault", password: "s3cret")
        XCTAssertEqual(a.sendInvite(b.myId, to: "vault"), .sent)
        settle()
        let invite = b.messages[a.myHex]?.last
        XCTAssertEqual(invite?.kind, .invite)
        XCTAssertEqual(bNotes.items.count, 1)
        XCTAssertEqual(b.acceptInvite(invite!.id, in: a.myHex), ConversationId.channel("vault"))
        XCTAssertTrue(b.channels["vault"]!.locked)
        a.send("welcome", in: ConversationId.channel("vault"))
        settle()
        XCTAssertEqual(texts(b, ConversationId.channel("vault")), ["welcome"])
    }

    func testReactionsAndDeleteForEveryone() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        settle()
        a.send("react to me", in: b.myHex)
        a.send("hello room", in: ConversationId.nearby)
        settle()
        let dm = b.messages[a.myHex]!.last!
        let room = b.messages[ConversationId.nearby]!.last!
        b.react("❤️", to: dm.id, in: a.myHex)
        b.react("😂", to: room.id, in: ConversationId.nearby)
        settle()
        XCTAssertEqual(a.messages[b.myHex]!.last!.reactions[b.myHex], "❤️")
        XCTAssertEqual(a.messages[ConversationId.nearby]!.last!.reactions[b.myHex], "😂")
        a.retract(dm.id, in: b.myHex)
        a.retract(room.id, in: ConversationId.nearby)
        settle()
        XCTAssertTrue(b.messages[a.myHex]!.first { $0.id == dm.id }!.retracted)
        XCTAssertTrue(b.messages[ConversationId.nearby]!.first { $0.id == room.id }!.retracted)
        // Only the author can delete.
        b.retract(dm.id, in: a.myHex)
        XCTAssertEqual(b.messages[a.myHex]!.first { $0.id == dm.id }!.senderId, a.myHex)
    }

    func testBlockedPeerIsSilenced() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        settle()
        b.setBlocked(a.myHex, true)
        a.send("can you hear me", in: b.myHex)
        a.send("anyone?", in: ConversationId.nearby)
        settle()
        XCTAssertEqual(texts(b, a.myHex), [])
        XCTAssertEqual(texts(b, ConversationId.nearby), [])
    }

    func testDisappearingMessagesExpire() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        settle()
        a.setDisappearing(300, for: b.myId)
        settle()
        XCTAssertEqual(b.conversations[a.myHex]?.disappearSeconds, 300)
        a.send("gone soon", in: b.myHex)
        settle()
        XCTAssertEqual(texts(b, a.myHex), ["gone soon"])
        settle(6 * 60_000)
        XCTAssertEqual(texts(b, a.myHex), [])
        XCTAssertEqual(texts(a, b.myHex), [])
    }

    func testCallsFromAndroidAreAnsweredUnsupported() {
        let (b, bNotes) = phone("Bo", seed: 2)
        let android = radio.rawNode("Droid", seed: 9)
        settle()
        let callId = MessageId(hi: 7, lo: 7)
        XCTAssertTrue(android.node.sendDirectControl(to: b.myId, kind: .callOffer, messageId: callId,
                                                     body: CallSignal.offerBody(key: [UInt8](repeating: 1, count: 32))))
        settle()
        let answers = android.box.events.compactMap { e -> String? in
            if case let .directControl(_, kind, id, body, _) = e, kind == .callAnswer, id == callId { return body }
            return nil
        }
        XCTAssertEqual(answers, ["unsupported"])
        XCTAssertTrue(b.messages[android.node.myId.toHex()]?.last?.body.contains("tried to call") == true)
        XCTAssertEqual(bNotes.items.count, 1)
        // A repeated offer gets the same answer but no second note.
        _ = android.node.sendDirectControl(to: b.myId, kind: .callOffer, messageId: callId, body: CallSignal.offerBody(key: [UInt8](repeating: 1, count: 32)))
        settle()
        XCTAssertEqual(b.messages[android.node.myId.toHex()]?.filter { $0.kind == .system }.count, 1)
    }

    func testWaveAndFavorites() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, bNotes) = phone("Bo", seed: 2)
        settle()
        XCTAssertTrue(a.wave(b.myId))
        settle()
        XCTAssertTrue(b.messages[a.myHex]?.last?.body.contains("waved") == true)
        XCTAssertEqual(bNotes.items.count, 1)
        b.toggleFavorite(a.myHex)
        XCTAssertTrue(b.peers[a.myHex]!.favorite)
        XCTAssertNotNil(a.safetyNumber(b.myHex))
        XCTAssertEqual(a.safetyNumber(b.myHex), b.safetyNumber(a.myHex))
    }

    func testNearbyKeepsADay() {
        let (a, _) = phone("Ada", seed: 1)
        let (b, _) = phone("Bo", seed: 2)
        settle()
        a.send("old news", in: ConversationId.nearby)
        settle()
        settle(25 * 3_600_000)
        XCTAssertEqual(texts(b, ConversationId.nearby), [])
        XCTAssertEqual(texts(a, ConversationId.nearby), [])
    }

    func testEraseEverything() {
        let (a, _) = phone("Ada", seed: 1)
        let oldId = a.myHex
        a.send("x", in: ConversationId.nearby)
        a.eraseEverything()
        XCTAssertFalse(a.settings.onboardingDone)
        XCTAssertNotEqual(a.myHex, oldId)
        XCTAssertEqual(Array(a.conversations.keys), [ConversationId.nearby])
    }
}

// MARK: - fakes

final class RecordingNotifier: Notifier {
    struct Item { let title: String; let body: String; let conversationId: String? }
    private(set) var items: [Item] = []
    func requestPermission() {}
    func notify(id: String, title: String, body: String, conversationId: String?) {
        items.append(Item(title: title, body: body, conversationId: conversationId))
    }
    func cancel(conversationId: String) {}
}

/// Deterministic, NOT secure.
final class TestRandom: RandomSource {
    private var state: UInt64
    init(_ seed: UInt64) { state = seed &* 0x9E37_79B9_7F4A_7C15 &+ 1 }
    func nextBytes(_ count: Int) -> [UInt8] {
        (0..<count).map { _ in
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return UInt8(truncatingIfNeeded: z ^ (z >> 31))
        }
    }
}

/// Virtual time: actions run only when a test advances the clock.
final class TestTime: Clock, Scheduler {
    private(set) var current: Int64 = 1_790_000_000_000
    private var tasks: [(at: Int64, seq: Int, item: Item)] = []
    private var seq = 0

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

/// Every started transport is linked to every other one (everyone in range), delivering after 10 ms.
final class FakeRadio {
    let time = TestTime()
    private var members: [Member] = []
    private var counter = 0

    private final class Member {
        weak var transport: FakeTransport?
        let deliver: (String, [UInt8]) -> Void
        let up: (MeshLink) -> Void
        let down: (String) -> Void
        var links: [FakeLink] = []
        init(transport: FakeTransport?, deliver: @escaping (String, [UInt8]) -> Void, up: @escaping (MeshLink) -> Void, down: @escaping (String) -> Void) {
            self.transport = transport
            self.deliver = deliver
            self.up = up
            self.down = down
        }
    }

    final class FakeLink: MeshLink {
        let id: String
        var peerLinkId = ""
        var alive = true
        /// The other end's receive.
        var deliver: ((String, [UInt8]) -> Void)?
        let time: TestTime

        init(id: String, time: TestTime) {
            self.id = id
            self.time = time
        }

        func send(_ packet: [UInt8]) -> Bool {
            guard alive, let deliver else { return false }
            let target = peerLinkId
            time.schedule(afterMillis: 10) { [weak self] in
                guard self?.alive == true else { return }
                deliver(target, packet)
            }
            return true
        }

        func onPeerIdentified(_ peerId: PeerId) {}
    }

    func makeTransport() -> FakeTransport { FakeTransport(radio: self) }

    /// A bare mesh node (stands in for an Android phone).
    func rawNode(_ name: String, seed: UInt64) -> (node: MeshNode, box: EventBox) {
        let box = EventBox()
        let node = MeshNode(identity: Identity.generate(TestRandom(seed)), profile: Profile(nickname: name, emoji: "🤖", colorIndex: 0),
                            clock: time, random: TestRandom(seed + 100), scheduler: time)
        node.onEvent = { box.events.append($0) }
        node.start()
        join(Member(transport: nil, deliver: { node.received(linkId: $0, bytes: $1) }, up: { node.linkUp($0) }, down: { node.linkDown($0) }))
        return (node, box)
    }

    final class EventBox { var events: [MeshEvent] = [] }

    fileprivate func join(_ transport: FakeTransport) {
        join(Member(transport: transport,
                    deliver: { [weak transport] in transport?.onReceived?($0, $1) },
                    up: { [weak transport] in transport?.onLinkUp?($0) },
                    down: { [weak transport] in transport?.onLinkDown?($0) }))
    }

    fileprivate func leave(_ transport: FakeTransport) {
        guard let i = members.firstIndex(where: { $0.transport === transport }) else { return }
        let gone = members.remove(at: i)
        for link in gone.links { link.alive = false }
        for m in members {
            for link in m.links where gone.links.contains(where: { $0.peerLinkId == link.id }) {
                link.alive = false
                m.down(link.id)
            }
            m.links.removeAll { !$0.alive }
        }
    }

    private func join(_ newcomer: Member) {
        for other in members {
            counter += 1
            let a = FakeLink(id: "L\(counter)a", time: time)
            let b = FakeLink(id: "L\(counter)b", time: time)
            a.peerLinkId = b.id
            b.peerLinkId = a.id
            a.deliver = other.deliver
            b.deliver = newcomer.deliver
            newcomer.links.append(a)
            other.links.append(b)
            newcomer.up(a)
            other.up(b)
        }
        members.append(newcomer)
    }
}

final class FakeTransport: LinkTransport {
    var onLinkUp: ((MeshLink) -> Void)?
    var onReceived: ((String, [UInt8]) -> Void)?
    var onLinkDown: ((String) -> Void)?
    var onStatus: ((BleStatus) -> Void)?
    private weak var radio: FakeRadio?

    init(radio: FakeRadio) { self.radio = radio }

    func start() { radio?.join(self) }
    func stop() { radio?.leave(self) }
}
