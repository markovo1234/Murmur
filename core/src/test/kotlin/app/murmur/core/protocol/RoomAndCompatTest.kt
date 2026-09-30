package app.murmur.core.protocol

import app.murmur.core.BASE_TIME
import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.ChannelCrypto
import app.murmur.core.crypto.Identity
import app.murmur.core.crypto.PinHasher
import app.murmur.core.decodeOk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1 additions: ROOM packets, channel crypto, DM control kinds, forward compatibility, PIN hashing. */
class RoomAndCompatTest {
    private val random = SeededRandomSource(1101)
    private val alice = Identity.generate(random)

    // Low iteration count keeps tests fast; production uses ChannelCrypto.DEFAULT_ITERATIONS.
    private fun key(channel: String, password: String) = ChannelCrypto.deriveKey(channel, password, iterations = 1_000)

    @Test
    fun plainRoomPacketRoundTrips() {
        val content = RoomContent(RoomKind.TEXT, "Alice", null, "hello #hiking")
        val packet = PacketCodec.create(
            alice.signing, Payload.Room("hiking", false, content.encode()), PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME,
        )
        val d = decodeOk(packet.encode())
        assertEquals(PacketType.ROOM, d.type)
        assertEquals(VerifyResult.OK, PacketCodec.verify(d))
        val room = d.payload as Payload.Room
        assertEquals("hiking", room.channel)
        assertFalse(room.encrypted)
        val decoded = RoomContent.decode(room.body)!!
        assertEquals(RoomKind.TEXT, decoded.kind)
        assertEquals("Alice", decoded.nickname)
        assertEquals("hello #hiking", decoded.body)
        assertNull(decoded.target)
        assertArrayEquals(packet.encode(), d.encode())
    }

    @Test
    fun everyRoomKindRoundTrips() {
        val target = PacketId.random(random)
        for (kind in RoomKind.entries) {
            val body = when (kind) {
                RoomKind.TEXT -> "text"
                RoomKind.REACTION -> "👍"
                RoomKind.RETRACT -> ""
                RoomKind.SOS -> "Need help at the north gate"
            }
            val t = if (kind == RoomKind.REACTION || kind == RoomKind.RETRACT) target else null
            val d = RoomContent.decode(RoomContent(kind, "Alice", t, body).encode())
            assertNotNull("$kind", d)
            assertEquals(kind, d!!.kind)
            assertEquals(t, d.target)
            assertEquals(body, d.body)
        }
    }

    @Test
    fun encryptedChannelOnlyOpensWithTheRightPassword() {
        val packetId = PacketId.random(random)
        val aad = ChannelCrypto.aad(packetId.toBytes(), alice.peerId.toBytes(), BASE_TIME, "secret")
        val plain = RoomContent(RoomKind.TEXT, "Alice", null, "for members").encode()
        val sealed = ChannelCrypto.seal(key("secret", "pa55"), plain, aad, random)

        assertArrayEquals(plain, ChannelCrypto.open(key("secret", "pa55"), sealed, aad))
        assertNull("wrong password", ChannelCrypto.open(key("secret", "nope"), sealed, aad))
        assertNull("same password, other channel", ChannelCrypto.open(key("other", "pa55"), sealed, aad))
        val badAad = ChannelCrypto.aad(packetId.toBytes(), alice.peerId.toBytes(), BASE_TIME + 1, "secret")
        assertNull("tampered AAD", ChannelCrypto.open(key("secret", "pa55"), sealed, badAad))

        val packet = PacketCodec.create(alice.signing, Payload.Room("secret", true, sealed), PeerId.BROADCAST, 7, packetId, BASE_TIME)
        val room = decodeOk(packet.encode()).payload as Payload.Room
        assertTrue(room.encrypted)
        assertArrayEquals(sealed, room.body)
    }

    @Test
    fun channelKeysAreDeterministic() {
        assertArrayEquals(key("party", "x"), key("party", "x"))
        assertEquals(32, key("party", "x").size)
        assertFalse(key("party", "x").contentEquals(key("party", "y")))
    }

    @Test
    fun channelNamesNormalizeAndValidate() {
        assertEquals("night-owls", Channels.normalize("#Night Owls"))
        assertEquals("a_b-1", Channels.normalize("  a_b-1 "))
        assertNull(Channels.normalize("###"))
        assertNull(Channels.normalize("nearby"))
        assertFalse(Channels.isValid("UPPER"))
        assertFalse(Channels.isValid("x".repeat(25)))
        assertTrue(Channels.isValid("x".repeat(24)))
    }

    @Test
    fun unknownPacketTypesDecodeVerifyAndAreNotDelivered() {
        val future = PacketCodec.create(alice.signing, Payload.Unknown(42, byteArrayOf(1, 2, 3)), PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME)
        val d = decodeOk(future.encode())
        assertEquals(PacketType.UNKNOWN, d.type)
        assertEquals(42, d.payload.typeCode)
        assertEquals(VerifyResult.OK, PacketCodec.verify(d))
        assertArrayEquals(future.encode(), d.encode())
    }

    @Test
    fun laterVersionsMayAppendFields() {
        // A DM TEXT with extra trailing bytes (a future extension) still decodes.
        val content = DmContent(DmKind.TEXT, MessageId.random(random), alice.agreement.publicKey, "hi").encode()
        val extended = content + byteArrayOf(9, 0, 2, 7, 7)
        assertEquals("hi", DmContent.decode(extended)?.body)
        // Unknown DM kinds are ignored rather than misread.
        val unknownKind = content.copyOf().also { it[0] = 99 }
        assertNull(DmContent.decode(unknownKind))
        // Room content with trailing bytes still decodes.
        val room = RoomContent(RoomKind.TEXT, "Alice", null, "x").encode() + byteArrayOf(1, 2, 3)
        assertEquals("x", RoomContent.decode(room)?.body)
    }

    @Test
    fun newDmKindsRoundTrip() {
        for (kind in listOf(DmKind.REACTION, DmKind.RETRACT, DmKind.WAVE, DmKind.TIMER)) {
            val body = when (kind) {
                DmKind.REACTION -> "❤️"
                DmKind.TIMER -> "3600"
                else -> ""
            }
            val c = DmContent(kind, MessageId.random(random), alice.agreement.publicKey, body)
            val d = DmContent.decode(c.encode())
            assertEquals(kind, d?.kind)
            assertEquals(body, d?.body)
            assertEquals(c.messageId, d?.messageId)
        }
    }

    @Test
    fun malformedRoomContentNeverThrows() {
        val rnd = java.util.Random(77)
        repeat(5_000) {
            RoomContent.decode(ByteArray(rnd.nextInt(80)).also(rnd::nextBytes))
        }
        // Reactions and retractions need a target.
        assertNull(RoomContent.decode(RoomContent(RoomKind.TEXT, "Alice", null, "x").encode().also { it[0] = RoomKind.RETRACT.code.toByte() }))
    }

    @Test
    fun pinHashVerifies() {
        val salt = PinHasher.newSalt(random)
        val hash = PinHasher.hash("2468", salt, iterations = 1_000)
        assertTrue(PinHasher.verify("2468", salt, hash, iterations = 1_000))
        assertFalse(PinHasher.verify("2469", salt, hash, iterations = 1_000))
        assertFalse(PinHasher.verify("2468", PinHasher.newSalt(random), hash, iterations = 1_000))
    }
}
