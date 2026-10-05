import XCTest
@testable import MurmurCore

final class CodecTests: XCTestCase {
    private let random = SeededRandom(7)
    private lazy var alice = Identity.generate(random)
    private lazy var bob = Identity.generate(random)

    private func decoded(_ p: Packet) throws -> Packet {
        guard case let .ok(d) = PacketCodec.decode(p.encode()) else { throw PacketError("decode failed") }
        return d
    }

    func testRoundTripsEveryType() throws {
        let payloads: [(Payload, PeerId)] = [
            (.announce(nickname: "Alice", emoji: "🌙", colorIndex: 2, agreementKey: alice.agreement.publicKey), .broadcast),
            (.publicMessage(nickname: "Alice", text: "hi"), .broadcast),
            (.leave, .broadcast),
            (.room(channel: "owls", encrypted: false, body: try RoomContent(kind: .text, nickname: "Alice", target: nil, body: "x").encode()), .broadcast),
            (.call(callId: Id128(hi: 5, lo: 6), seq: 99, sealed: [UInt8](repeating: 1, count: 30)), bob.peerId),
            (.unknown(code: 42, bytes: [9, 9]), .broadcast),
        ]
        for (payload, to) in payloads {
            let p = try PacketCodec.create(signing: alice.signing, payload: payload, recipientId: to, ttl: 7, packetId: .random(random), timestamp: baseTime)
            let d = try decoded(p)
            XCTAssertEqual(d.payload, payload)
            XCTAssertEqual(d.senderId, alice.peerId)
            XCTAssertEqual(PacketCodec.verify(d), .ok)
            XCTAssertEqual(d.hops, 1)
        }
    }

    func testRejectsMalformed() throws {
        let p = try PacketCodec.create(signing: alice.signing, payload: .publicMessage(nickname: "Alice", text: "hi"), recipientId: .broadcast,
                                       ttl: 7, packetId: .random(random), timestamp: baseTime)
        var bytes = p.encode()
        if case .ok = PacketCodec.decode(Array(bytes.dropLast())) { XCTFail("truncated accepted") }
        bytes[0] = 2
        if case .ok = PacketCodec.decode(bytes) { XCTFail("version 2 accepted") }
        bytes = p.encode()
        bytes[2] = 0
        if case .ok = PacketCodec.decode(bytes) { XCTFail("ttl 0 accepted") }
        // A PUBLIC packet addressed to one peer is invalid.
        let wrong = try PacketCodec.create(signing: alice.signing, payload: .publicMessage(nickname: "Alice", text: "hi"), recipientId: bob.peerId,
                                           ttl: 7, packetId: .random(random), timestamp: baseTime)
        if case .ok = PacketCodec.decode(wrong.encode()) { XCTFail("unicast PUBLIC accepted") }
        XCTAssertThrowsError(try PacketCodec.create(signing: alice.signing, payload: .publicMessage(nickname: " bad", text: "hi"),
                                                    recipientId: .broadcast, ttl: 7, packetId: .random(random), timestamp: baseTime))
    }

    func testPrivateMessagesOnlyOpenForTheRecipient() throws {
        let content = DmContent(kind: .text, messageId: .random(random), senderAgreementKey: alice.agreement.publicKey, body: "psst")
        let p = try XCTUnwrap(PrivateMessages.create(sender: alice, recipientId: bob.peerId, recipientAgreementKey: bob.agreement.publicKey,
                                                     content: content, ttl: 7, packetId: .random(random), timestamp: baseTime, random: random))
        let d = try decoded(p)
        XCTAssertEqual(PrivateMessages.open(d, me: bob), content)
        XCTAssertNil(PrivateMessages.open(d, me: alice))
    }

    func testStrictUtf8() {
        XCTAssertNoThrow(try ByteReader.strictUtf8(Array("héllo 🌙".utf8)))
        XCTAssertThrowsError(try ByteReader.strictUtf8([0xC3]))
        XCTAssertThrowsError(try ByteReader.strictUtf8([0xED, 0xA0, 0x80])) // encoded surrogate
        XCTAssertThrowsError(try ByteReader.strictUtf8([0xC0, 0xAF])) // overlong
    }

    func testIdsAndOrdering() {
        let a = PeerId(raw: 0x7FFF_FFFF_FFFF_FFFF)
        let b = PeerId(raw: 0x8000_0000_0000_0000)
        XCTAssertTrue(a < b, "unsigned ordering")
        XCTAssertEqual(PeerId.fromHex(b.toHex()), b)
        XCTAssertEqual(b.shortTag, "0000")
        XCTAssertTrue(PeerId.broadcast.isBroadcast)
        let id = Id128(hi: 1, lo: 2)
        XCTAssertEqual(Id128.fromHex(id.toHex()), id)
        XCTAssertNil(PeerId.fromHex("xyz"))
    }

    func testInvitesAndOffersParse() {
        XCTAssertNil(ChannelInvites.parse("2 owls -"))
        XCTAssertNil(ChannelInvites.parse("1 nearby -"))
        XCTAssertEqual(ChannelInvites.parse("1 owls - extra"), ChannelInvites.Invite(channel: "owls", key: nil))
        XCTAssertNil(CallSignal.parseOffer("1 amrnb 00"))
        XCTAssertEqual(CallSignal.Answer.fromWire(" accept "), .accept)
        XCTAssertEqual(CallSignal.EndReason.fromWire("??"), .hangup)
    }

    func testTextRules() {
        XCTAssertTrue(Mentions.mentions("hey @luna!", nickname: "Luna"))
        XCTAssertFalse(Mentions.mentions("hey @lunatic", nickname: "Luna"))
        XCTAssertFalse(Mentions.mentions("mail@luna", nickname: "Luna"))
        XCTAssertEqual(Mentions.partial("hi @Lu"), "Lu")
        XCTAssertNil(Mentions.partial("a@Lu"))
        XCTAssertEqual(Mentions.complete("hi @Lu", nickname: "Luna"), "hi @Luna ")
        let reply = Replies.compose(author: "Luna", quoted: "see you\nat 8", reply: "ok")
        XCTAssertEqual(reply, "> Luna: see you at 8\nok")
        XCTAssertEqual(Replies.parse(reply), Replies.Parsed(quoteAuthor: "Luna", quote: "see you at 8", text: "ok"))
        XCTAssertEqual(Disappearing.label(3600), "1 hour")
        XCTAssertEqual(Disappearing.label(300), "5 minutes")
    }
}

final class FragmentationTests: XCTestCase {
    func testInterleavedStreamsAndDuplicates() {
        let time = VirtualTime()
        let r = Reassembler(clock: time)
        let a = Fragmenter.fragment([UInt8](repeating: 1, count: 50), chunkSize: 20, streamId: 1)
        let b = Fragmenter.fragment([UInt8](repeating: 2, count: 30), chunkSize: 20, streamId: 2)
        XCTAssertNil(r.accept(a[0]))
        XCTAssertNil(r.accept(b[0]))
        XCTAssertNil(r.accept(a[0])) // duplicate
        for f in a.dropFirst().dropLast() { XCTAssertNil(r.accept(f)) }
        XCTAssertEqual(r.accept(a.last!), [UInt8](repeating: 1, count: 50))
        var out: [UInt8]?
        for f in b.dropFirst() { out = r.accept(f) }
        XCTAssertEqual(out, [UInt8](repeating: 2, count: 30))
        XCTAssertEqual(r.pendingStreams, 0)
    }

    func testExpiryAndRejects() {
        let time = VirtualTime()
        let r = Reassembler(clock: time, timeoutMillis: 1_000)
        let a = Fragmenter.fragment([UInt8](repeating: 1, count: 40), chunkSize: 20, streamId: 9)
        XCTAssertNil(r.accept(a[0]))
        time.advance(2_000)
        r.purgeExpired()
        XCTAssertEqual(r.pendingStreams, 0)
        XCTAssertNil(r.accept([0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9]))
        XCTAssertEqual(r.rejected, 1)
        XCTAssertEqual(Fragmenter.chunkSizeForMtu(23), 20)
        XCTAssertEqual(Fragmenter.chunkSizeForMtu(517), 512)
    }
}
