package app.murmur.core.call

import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.CallCrypto
import app.murmur.core.crypto.Identity
import app.murmur.core.protocol.DecodeResult
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketType
import app.murmur.core.protocol.Payload
import app.murmur.core.protocol.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2: voice-call packets, crypto, signalling, AMR framing and the jitter buffer. */
class CallTest {
    private val random = SeededRandomSource(1212)
    private val alice = Identity.generate(random)
    private val bob = Identity.generate(random)

    @Test
    fun callPacketRoundTripsAndMustBeAddressed() {
        val callId = MessageId.random(random)
        val payload = Payload.Call(callId, 0xFFFF_FFF0L, ByteArray(40) { it.toByte() })
        val packet = PacketCodec.create(alice.signing, payload, bob.peerId, 2, app.murmur.core.protocol.PacketId.random(random), 1_000L)
        val decoded = (PacketCodec.decode(packet.encode()) as DecodeResult.Ok).packet
        assertEquals(PacketType.CALL, decoded.type)
        assertEquals(payload, decoded.payload)
        assertEquals(2, decoded.ttl)

        // A broadcast CALL packet is malformed.
        val broadcast = PacketCodec.create(alice.signing, payload, PeerId.BROADCAST, 2, app.murmur.core.protocol.PacketId.random(random), 1_000L)
        assertTrue(PacketCodec.decode(broadcast.encode()) is DecodeResult.Error)
    }

    @Test
    fun callCryptoSeparatesDirectionsAndSequenceNumbers() {
        val key = CallCrypto.newKey(random)
        val id = MessageId.random(random).toBytes()
        val aad = CallCrypto.aad(id, alice.peerId, bob.peerId, 7)
        val audio = byteArrayOf(0x3C, 1, 2, 3)
        val sealed = CallCrypto.seal(key, fromCaller = true, seq = 7, aad = aad, plaintext = audio)
        assertArrayEquals(audio, CallCrypto.open(key, true, 7, aad, sealed))
        assertNull("wrong direction", CallCrypto.open(key, false, 7, aad, sealed))
        assertNull("wrong seq", CallCrypto.open(key, true, 8, aad, sealed))
        assertNull("wrong sender", CallCrypto.open(key, true, 7, CallCrypto.aad(id, bob.peerId, alice.peerId, 7), sealed))
        assertNull("wrong key", CallCrypto.open(CallCrypto.newKey(random), true, 7, aad, sealed))
        assertFalse(CallCrypto.nonce(true, 7).contentEquals(CallCrypto.nonce(false, 7)))
    }

    @Test
    fun offerBodyRoundTrips() {
        val key = CallCrypto.newKey(random)
        val offer = CallSignal.parseOffer(CallSignal.offerBody(key))
        assertNotNull(offer)
        assertEquals(CallSignal.CODEC_AMR_NB, offer!!.codec)
        assertArrayEquals(key, offer.key)
        // Later versions may append fields.
        assertNotNull(CallSignal.parseOffer(CallSignal.offerBody(key) + " extra"))
        assertNull(CallSignal.parseOffer("2 amrnb " + "00".repeat(32)))
        assertNull(CallSignal.parseOffer("1 amrnb 1234"))
        assertNull(CallSignal.parseOffer(""))
        assertEquals(CallSignal.Answer.ACCEPT, CallSignal.Answer.fromWire("accept"))
        assertNull(CallSignal.Answer.fromWire("maybe"))
        assertEquals(CallSignal.EndReason.HANGUP, CallSignal.EndReason.fromWire("something new"))
    }

    @Test
    fun amrFramesSplit() {
        // 7.95 kbit/s frames (type 5: 1 + 20 bytes) and a NO_DATA frame (type 15: 1 byte).
        val speech = ByteArray(21).also { it[0] = (5 shl 3 or 4).toByte() }
        val noData = byteArrayOf((15 shl 3 or 4).toByte())
        val frames = Amr.split(speech + noData + speech)
        assertEquals(listOf(21, 1, 21), frames!!.map { it.size })
        assertNull("truncated", Amr.split(speech.copyOf(10)))
        assertNull("reserved type", Amr.split(byteArrayOf((10 shl 3).toByte())))
        assertNull("empty", Amr.split(ByteArray(0)))
    }

    @Test
    fun jitterBufferReordersAndBuffersBeforePlaying() {
        val jb = JitterBuffer(startDepth = 3)
        jb.push(1, byteArrayOf(1))
        jb.push(0, byteArrayOf(0))
        assertNull("still buffering", jb.pop())
        jb.push(2, byteArrayOf(2))
        assertEquals(0, jb.pop()!![0].toInt())
        assertEquals(1, jb.pop()!![0].toInt())
        assertEquals(2, jb.pop()!![0].toInt())
        jb.push(1, byteArrayOf(9))
        assertEquals("already played → late", 1, jb.late)
    }

    @Test
    fun jitterBufferPlaysSilenceForSmallGapsAndSkipsBigOnes() {
        val jb = JitterBuffer(startDepth = 1, maxGap = 3)
        jb.push(0, byteArrayOf(0))
        assertEquals(0, jb.pop()!![0].toInt())
        jb.push(2, byteArrayOf(2))
        assertNull("1 is missing: silence", jb.pop())
        assertEquals(2, jb.pop()!![0].toInt())
        jb.push(20, byteArrayOf(20))
        assertEquals("long gap skipped", 20, jb.pop()!![0].toInt())
        assertTrue(jb.lost >= 17)
    }

    @Test
    fun jitterBufferCapsLatency() {
        val jb = JitterBuffer(startDepth = 2, maxDepth = 4)
        for (seq in 0L until 10L) jb.push(seq, byteArrayOf(seq.toByte()))
        assertEquals(4, jb.depth)
        assertEquals("oldest dropped", 6, jb.pop()!![0].toInt())
    }
}
