package app.murmur.core.protocol

import app.murmur.core.BASE_TIME
import app.murmur.core.Murmur
import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.Identity
import app.murmur.core.decodeOk
import app.murmur.core.link.Reassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {
    private val random = SeededRandomSource(42)
    private val alice = Identity.generate(random)
    private val bob = Identity.generate(random)

    private fun samples(): List<Packet> = listOf(
        PacketCodec.create(
            alice.signing,
            Payload.Announce("Alice", "🦊", 3, alice.agreement.publicKey),
            PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME,
        ),
        PacketCodec.create(
            alice.signing,
            Payload.Public("Alice", "Hello #nearby ✨ — ünïcödé"),
            PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME + 1,
        ),
        PrivateMessages.create(
            alice, bob.peerId, bob.agreement.publicKey,
            DmContent(DmKind.TEXT, MessageId.random(random), alice.agreement.publicKey, "secret"),
            7, PacketId.random(random), BASE_TIME + 2, random,
        )!!,
        PacketCodec.create(alice.signing, Payload.Leave, PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME + 3),
    )

    @Test
    fun everyPacketTypeRoundTrips() {
        val packets = samples()
        assertEquals(PacketType.entries.toSet(), packets.map { it.type }.toSet())
        for (p in packets) {
            val bytes = p.encode()
            assertEquals(PacketCodec.OVERHEAD + PacketCodec.encodePayload(p.payload).size, bytes.size)
            val d = decodeOk(bytes)
            assertEquals(p.version, d.version)
            assertEquals(p.type, d.type)
            assertEquals(p.ttl, d.ttl)
            assertEquals(p.packetId, d.packetId)
            assertEquals(p.senderId, d.senderId)
            assertEquals(p.recipientId, d.recipientId)
            assertEquals(p.timestamp, d.timestamp)
            assertArrayEquals(p.senderKey, d.senderKey)
            assertEquals(p.payload, d.payload)
            assertArrayEquals(p.signature, d.signature)
            assertArrayEquals(bytes, d.encode())
            assertEquals(VerifyResult.OK, PacketCodec.verify(d))
        }
    }

    @Test
    fun maximumSizedPublicMessageRoundTrips() {
        val nick = "N".repeat(Murmur.NICKNAME_MAX_CHARS)
        val text = "é".repeat(Murmur.MAX_TEXT_BYTES / 2)
        assertEquals(Murmur.MAX_TEXT_BYTES, Murmur.utf8Size(text))
        val p = PacketCodec.create(alice.signing, Payload.Public(nick, text), PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME)
        val d = decodeOk(p.encode())
        assertEquals(Payload.Public(nick, text), d.payload)
        assertTrue(p.encode().size <= PacketCodec.MAX_PACKET)
    }

    @Test
    fun dmContentRoundTripsForEveryKind() {
        for (kind in DmKind.entries) {
            val body = if (kind == DmKind.TEXT) "hi 👋" else ""
            val c = DmContent(kind, MessageId.random(random), alice.agreement.publicKey, body)
            val decoded = DmContent.decode(c.encode())
            assertNotNull(decoded)
            val d = decoded!!
            assertEquals(kind, d.kind)
            assertEquals(c.messageId, d.messageId)
            assertArrayEquals(c.senderAgreementKey, d.senderAgreementKey)
            assertEquals(body, d.body)
        }
    }

    @Test
    fun tenThousandRandomByteArraysReturnErrorsAndNeverThrow() {
        val rnd = java.util.Random(1234)
        repeat(10_000) {
            val bytes = ByteArray(rnd.nextInt(700)).also(rnd::nextBytes)
            val result = PacketCodec.decode(bytes)
            assertTrue("random input decoded as a packet", result is DecodeResult.Error)
            DmContent.decode(bytes)
        }
    }

    @Test
    fun mutatedAndTruncatedPacketsNeverThrowAndNeverVerify() {
        val rnd = java.util.Random(99)
        val reassembler = Reassembler(clock = { BASE_TIME })
        for (p in samples()) {
            val original = p.encode()
            repeat(2_500) {
                val bytes = original.copyOf(if (rnd.nextInt(4) == 0) rnd.nextInt(original.size + 1) else original.size)
                val flips = 1 + rnd.nextInt(3)
                var touched = false
                repeat(flips) {
                    if (bytes.isNotEmpty()) {
                        val i = rnd.nextInt(bytes.size)
                        if (i != PacketCodec.TTL_OFFSET) {
                            bytes[i] = (bytes[i].toInt() xor (1 + rnd.nextInt(255))).toByte()
                            touched = true
                        }
                    }
                }
                val result = PacketCodec.decode(bytes)
                val changed = touched || bytes.size != original.size
                if (changed && result is DecodeResult.Ok) {
                    assertNotEquals(VerifyResult.OK, PacketCodec.verify(result.packet))
                }
                reassembler.accept(bytes)
            }
        }
    }

    @Test
    fun peerIdOrderingIsUnsignedAndHexRoundTrips() {
        val low = PeerId(0x7FFFFFFFFFFFFFFFL)
        val high = PeerId(-2L) // 0xFFFF...FE
        assertTrue(low < high)
        assertEquals(high, PeerId.fromHex(high.toHex()))
        assertEquals(alice.peerId, PeerId.fromPublicKey(alice.signing.publicKey))
        val id = PacketId.random(random)
        assertEquals(id, PacketId.fromHex(id.toHex()))
    }
}
