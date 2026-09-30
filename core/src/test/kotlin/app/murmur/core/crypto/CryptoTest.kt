package app.murmur.core.crypto

import app.murmur.core.BASE_TIME
import app.murmur.core.SeededRandomSource
import app.murmur.core.decodeOk
import app.murmur.core.protocol.DecodeResult
import app.murmur.core.protocol.DmContent
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.Packet
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.Payload
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.PrivateMessages
import app.murmur.core.protocol.VerifyResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val random = SeededRandomSource(7)
    private val alice = Identity.generate(random)
    private val bob = Identity.generate(random)
    private val eve = Identity.generate(random)

    private fun samplePackets(): List<Packet> = listOf(
        PacketCodec.create(alice.signing, Payload.Announce("Alice", "🌙", 1, alice.agreement.publicKey), PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME),
        PacketCodec.create(alice.signing, Payload.Public("Alice", "hello"), PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME),
        PrivateMessages.create(
            alice, bob.peerId, bob.agreement.publicKey,
            DmContent(DmKind.TEXT, MessageId.random(random), alice.agreement.publicKey, "psst"),
            7, PacketId.random(random), BASE_TIME, random,
        )!!,
        PacketCodec.create(alice.signing, Payload.Leave, PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME),
        PacketCodec.create(
            alice.signing,
            Payload.Room("", false, app.murmur.core.protocol.RoomContent(app.murmur.core.protocol.RoomKind.SOS, "Alice", null, "help").encode()),
            PeerId.BROADCAST, 7, PacketId.random(random), BASE_TIME,
        ),
    )

    @Test
    fun signatureSurvivesTtlChange() {
        for (p in samplePackets()) {
            for (ttl in 1..7) {
                val bytes = p.encode()
                bytes[PacketCodec.TTL_OFFSET] = ttl.toByte()
                val d = decodeOk(bytes)
                assertEquals(ttl, d.ttl)
                assertEquals(VerifyResult.OK, PacketCodec.verify(d))
            }
            assertEquals(VerifyResult.OK, PacketCodec.verify(p.withTtl(1)))
        }
    }

    @Test
    fun flippingAnyOtherByteFailsVerification() {
        for (p in samplePackets()) {
            val original = p.encode()
            for (i in original.indices) {
                if (i == PacketCodec.TTL_OFFSET) continue
                for (mask in intArrayOf(0x01, 0x80)) {
                    val bytes = original.copyOf()
                    bytes[i] = (bytes[i].toInt() xor mask).toByte()
                    val result = PacketCodec.decode(bytes)
                    if (result is DecodeResult.Ok) {
                        assertNotEquals("byte $i mask $mask of ${p.type} still verified", VerifyResult.OK, PacketCodec.verify(result.packet))
                    }
                }
            }
        }
    }

    @Test
    fun senderIdKeyMismatchIsRejected() {
        val forged = PacketCodec.assemble(
            senderId = bob.peerId, // claims to be Bob…
            signing = alice.signing, // …but carries (and is signed with) Alice's key
            payload = Payload.Public("Bob", "trust me"),
            recipientId = PeerId.BROADCAST,
            ttl = 7,
            packetId = PacketId.random(random),
            timestamp = BASE_TIME,
        )
        assertEquals(VerifyResult.SENDER_KEY_MISMATCH, PacketCodec.verify(decodeOk(forged.encode())))
    }

    @Test
    fun dmSealOpenRoundTrips() {
        val aad = PrivateMessages.aad(PacketId.random(random), alice.peerId, bob.peerId, BASE_TIME)
        val plain = "meet at the fountain 🌊".encodeToByteArray()
        val sealed = DmCrypto.seal(plain, bob.agreement.publicKey, aad, random)
        assertNotNull(sealed)
        assertArrayEquals(plain, DmCrypto.open(sealed!!, bob.agreement, aad))

        val content = DmContent(DmKind.TEXT, MessageId.random(random), alice.agreement.publicKey, "hey bob")
        val packet = PrivateMessages.create(alice, bob.peerId, bob.agreement.publicKey, content, 7, PacketId.random(random), BASE_TIME, random)!!
        val opened = PrivateMessages.open(decodeOk(packet.encode()), bob)
        assertNotNull(opened)
        assertEquals("hey bob", opened!!.body)
        assertEquals(content.messageId, opened.messageId)
        assertArrayEquals(alice.agreement.publicKey, opened.senderAgreementKey)
    }

    @Test
    fun wrongKeyFailsToOpen() {
        val aad = PrivateMessages.aad(PacketId.random(random), alice.peerId, bob.peerId, BASE_TIME)
        val sealed = DmCrypto.seal("for bob only".encodeToByteArray(), bob.agreement.publicKey, aad, random)!!
        assertNull(DmCrypto.open(sealed, eve.agreement, aad))
        assertNull(DmCrypto.open(sealed, alice.agreement, aad))
    }

    @Test
    fun tamperedAadOrCiphertextFailsToOpen() {
        val aad = PrivateMessages.aad(PacketId.random(random), alice.peerId, bob.peerId, BASE_TIME)
        val sealed = DmCrypto.seal("integrity".encodeToByteArray(), bob.agreement.publicKey, aad, random)!!
        for (i in aad.indices) {
            val badAad = aad.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("AAD byte $i", DmCrypto.open(sealed, bob.agreement, badAad))
        }
        val badCt = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(DmCrypto.open(DmCrypto.Sealed(sealed.ephemeralKey, sealed.nonce, badCt), bob.agreement, aad))
        val badNonce = sealed.nonce.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(DmCrypto.open(DmCrypto.Sealed(sealed.ephemeralKey, badNonce, sealed.ciphertext), bob.agreement, aad))
    }

    @Test
    fun lowOrderPublicKeyIsRejectedWithoutThrowing() {
        val zero = ByteArray(32)
        assertNull(bob.agreement.agree(zero))
        assertNull(DmCrypto.seal(byteArrayOf(1), zero, ByteArray(0), random))
    }

    @Test
    fun bothSidesComputeTheSameSafetyNumber() {
        val a = SafetyNumber.digits(alice.signing.publicKey, bob.signing.publicKey)
        val b = SafetyNumber.digits(bob.signing.publicKey, alice.signing.publicKey)
        assertEquals(a, b)
        assertEquals(24, a.length)
        assertTrue(a.all { it.isDigit() })
        val formatted = SafetyNumber.formatted(bob.signing.publicKey, alice.signing.publicKey)
        assertEquals(6, formatted.split(" ").size)
        assertTrue(formatted.split(" ").all { it.length == 4 })
        assertNotEquals(a, SafetyNumber.digits(alice.signing.publicKey, eve.signing.publicKey))
    }

    @Test
    fun identityRestoresFromPrivateKeys() {
        val restored = Identity.fromPrivateKeys(alice.signing.privateKey, alice.agreement.privateKey)
        assertEquals(alice.peerId, restored.peerId)
        assertArrayEquals(alice.agreement.publicKey, restored.agreement.publicKey)
        val sig = restored.signing.sign("x".encodeToByteArray())
        assertTrue(Ed25519.verify(alice.signing.publicKey, sig, "x".encodeToByteArray()))
    }
}
