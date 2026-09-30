package app.murmur.core.mesh

import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.ChannelCrypto
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.Payload
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.RoomKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1: channels, #nearby extras, DM controls and forward-compatible relaying. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRoomTest {
    private val ids = SeededRandomSource(9090)

    private fun TestScope.settle(millis: Long = 1_000) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.line(): MeshSim = MeshSim(this).apply {
        add("A", "B", "C", "D", "E")
        connect("A", "B")
        connect("B", "C")
        connect("C", "D")
        connect("D", "E")
        settle()
    }

    private fun SimNode.rooms() = events.filterIsInstance<MeshEvent.RoomMessage>()

    @Test
    fun publicChannelMessageReachesEveryoneOnce() = runTest {
        val sim = line()
        val id = sim["A"].mesh.sendRoom("hiking", RoomKind.TEXT, "trail at 9?")
        settle()
        for (name in listOf("B", "C", "D", "E")) {
            val got = sim[name].rooms().filter { it.packetId == id }
            assertEquals(name, 1, got.size)
            assertEquals("hiking", got.single().channel)
            assertEquals("trail at 9?", got.single().body)
            assertEquals("A", got.single().nickname)
        }
        assertTrue(sim["A"].rooms().isEmpty())
    }

    @Test
    fun encryptedChannelIsRelayedByEveryoneButReadOnlyByMembers() = runTest {
        val sim = line()
        val key = ChannelCrypto.deriveKey("crew", "hunter2", iterations = 1_000)
        sim["A"].mesh.setChannelKeys(mapOf("crew" to key))
        sim["E"].mesh.setChannelKeys(mapOf("crew" to key))
        sim["C"].mesh.setChannelKeys(mapOf("crew" to ChannelCrypto.deriveKey("crew", "wrong", iterations = 1_000)))
        val id = sim["A"].mesh.sendRoom("crew", RoomKind.TEXT, "members only", key = key)
        settle()
        assertEquals(1, sim["E"].rooms().count { it.packetId == id && it.encrypted })
        for (relay in listOf("B", "C", "D")) {
            assertTrue("$relay must not read it", sim[relay].rooms().none { it.packetId == id })
            assertEquals("$relay relays it", 1, sim[relay].relayed[id])
        }
    }

    @Test
    fun nearbyReactionsRetractionsAndSosFlood() = runTest {
        val sim = line()
        val msg = sim["A"].mesh.sendPublic("hi all")
        settle()
        sim["E"].mesh.sendRoom("", RoomKind.REACTION, "👍", target = msg)
        sim["A"].mesh.sendRoom("", RoomKind.RETRACT, "", target = msg)
        sim["C"].mesh.sendRoom("", RoomKind.SOS, "Need a medic at stage 2")
        settle()
        val reaction = sim["A"].rooms().single { it.kind == RoomKind.REACTION }
        assertEquals(msg, reaction.target)
        assertEquals("👍", reaction.body)
        assertEquals(1, sim["E"].rooms().count { it.kind == RoomKind.RETRACT && it.target == msg })
        for (name in listOf("A", "B", "D", "E")) {
            assertEquals(name, 1, sim[name].rooms().count { it.kind == RoomKind.SOS })
        }
    }

    @Test
    fun directControlsReachOnlyTheRecipient() = runTest {
        val sim = line()
        val target = MessageId.random(ids)
        assertTrue(sim["A"].mesh.sendDirectControl(sim["E"].id, DmKind.REACTION, target, "❤️"))
        sim["A"].mesh.sendDirectControl(sim["E"].id, DmKind.WAVE, MessageId.random(ids))
        sim["A"].mesh.sendDirectControl(sim["E"].id, DmKind.TIMER, MessageId.random(ids), "3600")
        settle()
        val got = sim["E"].events.filterIsInstance<MeshEvent.DirectControl>()
        assertEquals(listOf(DmKind.REACTION, DmKind.WAVE, DmKind.TIMER), got.map { it.kind })
        assertEquals(target, got.first().messageId)
        assertEquals("❤️", got.first().body)
        assertTrue(got.all { it.senderId == sim["A"].id })
        for (relay in listOf("B", "C", "D")) {
            assertTrue(sim[relay].events.none { it is MeshEvent.DirectControl })
        }
    }

    @Test
    fun unknownFuturePacketTypesAreRelayedNotDelivered() = runTest {
        val sim = line()
        val a = sim["A"]
        val future = PacketCodec.create(a.identity.signing, Payload.Unknown(77, byteArrayOf(4, 2)), PeerId.BROADCAST, 7, PacketId.random(ids), sim.clock.now())
        // As if A's future version sent it on its only link.
        sim.inject("A", "B", future.encode())
        settle()
        for (name in listOf("B", "C", "D")) assertEquals(name, 1, sim[name].relayed[future.packetId])
        assertTrue(sim.net.wire.any { it.to == "E" && it.packet?.packetId == future.packetId })
    }

    @Test
    fun blockedSendersRoomMessagesAreHidden() = runTest {
        val sim = line()
        sim["E"].mesh.setBlocked(setOf(sim["A"].id))
        val id = sim["A"].mesh.sendRoom("hiking", RoomKind.TEXT, "hello")
        settle()
        assertTrue(sim["E"].rooms().none { it.packetId == id })
        assertEquals(1, sim["D"].rooms().count { it.packetId == id })
    }
}
