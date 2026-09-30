package app.murmur.core.mesh

import app.murmur.core.SeededRandomSource
import app.murmur.core.call.CallSignal
import app.murmur.core.crypto.CallCrypto
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2: call signalling and audio through the mesh. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshCallTest {
    private val random = SeededRandomSource(4242)

    private fun TestScope.settle(millis: Long = 1_000) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.line(): MeshSim = MeshSim(this).apply {
        add("A", "B", "C", "D")
        connect("A", "B")
        connect("B", "C")
        connect("C", "D")
        settle()
    }

    @Test
    fun callSignalsArriveAsDirectControls() = runTest {
        val sim = line()
        val callId = MessageId.random(random)
        val key = CallCrypto.newKey(random)
        assertTrue(sim["A"].mesh.sendDirectControl(sim["C"].identity.peerId, DmKind.CALL_OFFER, callId, CallSignal.offerBody(key)))
        settle()
        val offer = sim["C"].events.filterIsInstance<MeshEvent.DirectControl>().single()
        assertEquals(DmKind.CALL_OFFER, offer.kind)
        assertEquals(callId, offer.messageId)
        assertArrayEquals(key, CallSignal.parseOffer(offer.body)!!.key)
        assertTrue("relays can't see it", sim["B"].events.none { it is MeshEvent.DirectControl })
    }

    @Test
    fun audioReachesOnlyTheCalleeAndStopsAtItsTtl() = runTest {
        val sim = line()
        val c = sim["C"].identity.peerId
        assertEquals(2, sim["A"].mesh.hopsTo(c))
        val callId = MessageId.random(random)
        for (seq in 0L until 5L) {
            assertTrue(sim["A"].mesh.sendCallAudio(c, callId, seq, ByteArray(40) { seq.toByte() }, ttl = 2))
        }
        settle()
        val got = sim["C"].events.filterIsInstance<MeshEvent.CallAudio>()
        assertEquals((0L until 5L).toList(), got.map { it.seq })
        assertTrue(got.all { it.callId == callId })
        assertTrue(sim["B"].events.none { it is MeshEvent.CallAudio })
        // ttl 2 = one relay (B). C must not pass it on to D.
        assertTrue(sim["D"].events.none { it is MeshEvent.CallAudio })
        assertFalse(sim.net.wire.any { it.from == "C" && it.to == "D" && app.murmur.core.protocol.PacketCodec.peekType(it.bytes) == PacketType.CALL })
    }

    @Test
    fun relayedCallAudioIsNotCountedAsRelayedMessages() = runTest {
        val sim = line()
        val before = sim["B"].mesh.stats.value.relayed
        sim["A"].mesh.sendCallAudio(sim["C"].identity.peerId, MessageId.random(random), 0, ByteArray(40), ttl = 2)
        settle()
        assertEquals(before, sim["B"].mesh.stats.value.relayed)
    }

    @Test
    fun shortReachMessagesShowTheRealHopCount() = runTest {
        val sim = line()
        // ttl 3 from A: heard directly by B, which must show 1 hop (not 8 - 3 = 5).
        val id = sim["A"].mesh.sendPublic("close by only", ttl = 3)
        settle()
        assertEquals(1, sim["B"].publicMessages(id).single().hops)
        assertEquals(2, sim["C"].publicMessages(id).single().hops)
        // D gets it with ttl 1 (8 - 1 = 7 by the ttl alone) but is really 3 hops away.
        assertEquals(3, sim["D"].publicMessages(id).single().hops)
    }
}
