package app.murmur.core.mesh

import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.DmCrypto
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketType
import app.murmur.core.protocol.Payload
import app.murmur.core.protocol.PrivateMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNodeTest {
    private val ids = SeededRandomSource(4242)

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

    private suspend fun TestScope.assertFloodReachesEveryoneOnce(sim: MeshSim, origin: String) {
        val packetId = sim[origin].mesh.sendPublic("hello from $origin")
        settle()
        for (node in sim.nodes.values) {
            val expected = if (node.name == origin) 0 else 1
            assertEquals("${node.name} received count", expected, node.publicMessages(packetId).size)
            assertTrue("${node.name} relayed more than once", (node.relayed[packetId] ?: 0) <= 1)
        }
        assertEquals(null, sim[origin].relayed[packetId])
    }

    @Test
    fun nearbyMessageOnLineReachesEveryNodeExactlyOnce() = runTest {
        val sim = line()
        assertFloodReachesEveryoneOnce(sim, "A")
        assertFloodReachesEveryoneOnce(sim, "C")
    }

    @Test
    fun nearbyMessageOnRingOfSixReachesEveryNodeExactlyOnce() = runTest {
        val sim = MeshSim(this)
        val names = (0 until 6).map { "N$it" }
        names.forEach { sim.add(it) }
        for (i in names.indices) sim.connect(names[i], names[(i + 1) % names.size])
        settle()
        assertFloodReachesEveryoneOnce(sim, "N0")
        assertFloodReachesEveryoneOnce(sim, "N3")
    }

    @Test
    fun nearbyMessageOnFullMeshOfFiveReachesEveryNodeExactlyOnce() = runTest {
        val sim = MeshSim(this)
        val names = (0 until 5).map { "M$it" }
        names.forEach { sim.add(it) }
        for (i in names.indices) for (j in i + 1 until names.size) sim.connect(names[i], names[j])
        settle()
        assertFloodReachesEveryoneOnce(sim, "M0")
        assertFloodReachesEveryoneOnce(sim, "M4")
    }

    @Test
    fun ttlTwoOnLineReachesOnlyBAndC() = runTest {
        val sim = line()
        val packetId = sim["A"].mesh.sendPublic("short range", ttl = 2)
        settle()
        assertEquals(1, sim["B"].publicMessages(packetId).size)
        assertEquals(1, sim["C"].publicMessages(packetId).size)
        assertEquals(0, sim["D"].publicMessages(packetId).size)
        assertEquals(0, sim["E"].publicMessages(packetId).size)
    }

    @Test
    fun dmAcrossLineIsRelayedButOnlyReadableByRecipient() = runTest {
        val sim = line()
        val a = sim["A"]
        val e = sim["E"]
        assertNotNull("A should know E's key from its ANNOUNCE", a.mesh.peers.value[e.id]?.agreementKey)
        assertEquals(PeerStatus.VIA_MESH, a.mesh.peers.value[e.id]?.status)
        assertEquals(PeerStatus.NEARBY, a.mesh.peers.value[sim["B"].id]?.status)

        val messageId = MessageId.random(ids)
        a.mesh.sendDirectMessage(e.id, messageId, "hi E, it's A")
        settle(2_000)

        val dms = e.directMessages()
        assertEquals(1, dms.size)
        assertEquals("hi E, it's A", dms.single().body)
        assertEquals(a.id, dms.single().senderId)
        assertEquals("A→B→C→D→E is 4 hops", 4, dms.single().hops)

        val textPacket = sim.privatePackets(a.id, e.id).single()
        for (relay in listOf("B", "C", "D")) {
            val node = sim[relay]
            assertTrue("$relay should not show the DM", node.directMessages().isEmpty())
            assertEquals("$relay should relay the DM once", 1, node.relayed[textPacket.packetId])
            val payload = textPacket.payload as Payload.Private
            val aad = PrivateMessages.aad(textPacket.packetId, textPacket.senderId, textPacket.recipientId, textPacket.timestamp)
            assertNull(
                "$relay must not be able to decrypt",
                DmCrypto.open(DmCrypto.Sealed(payload.ephemeralKey, payload.nonce, payload.ciphertext), node.identity.agreement, aad),
            )
        }
        assertEquals(DmKind.TEXT, PrivateMessages.open(textPacket, e.identity)?.kind)

        val statuses = a.deliveries(messageId)
        assertEquals(DeliveryStatus.SENDING, statuses.first())
        assertTrue(DeliveryStatus.SENT in statuses)
        assertEquals(DeliveryStatus.DELIVERED, statuses.last())
    }

    @Test
    fun lostDeliveredReceiptTriggersRetryAndRecipientShowsMessageOnce() = runTest {
        val sim = line()
        val a = sim["A"]
        val e = sim["E"]
        var droppedReceipts = 0
        sim.net.interceptor = { from, to, bytes ->
            val packet = (PacketCodec.decode(bytes) as? app.murmur.core.protocol.DecodeResult.Ok)?.packet
            val isReceiptFromE = packet != null && packet.type == PacketType.PRIVATE &&
                packet.senderId == e.id && packet.recipientId == a.id
            if (from == "C" && to == "B" && isReceiptFromE && droppedReceipts == 0) {
                droppedReceipts++
                null
            } else {
                bytes
            }
        }

        val messageId = MessageId.random(ids)
        a.mesh.sendDirectMessage(e.id, messageId, "are you there?")
        settle(5_000)
        assertEquals(1, droppedReceipts)
        assertEquals(DeliveryStatus.SENT, a.deliveries(messageId).last())
        assertEquals(1, e.directMessages().size)

        settle(30_000) // ack timeout → resend with the same messageId
        assertEquals(DeliveryStatus.DELIVERED, a.deliveries(messageId).last())
        assertEquals("E shows the message once", 1, e.directMessages().size)
        assertEquals("A sent two TEXT packets", 2, sim.privatePackets(a.id, e.id).count { PrivateMessages.open(it, e.identity)?.kind == DmKind.TEXT })

        settle(60_000)
        assertEquals(DeliveryStatus.DELIVERED, a.deliveries(messageId).last())
    }

    @Test
    fun dmToOfflinePeerIsPendingThenDeliveredAfterRelink() = runTest {
        val sim = line()
        val a = sim["A"]
        val e = sim["E"]
        assertNotNull(a.mesh.peers.value[e.id]?.agreementKey)

        sim.disconnect("D", "E")
        settle(95_000)
        assertEquals(PeerStatus.OFFLINE, a.mesh.peers.value[e.id]?.status)

        val messageId = MessageId.random(ids)
        a.mesh.sendDirectMessage(e.id, messageId, "catch you later")
        settle()
        assertEquals(listOf(DeliveryStatus.PENDING), a.deliveries(messageId))
        assertTrue(e.directMessages().isEmpty())

        settle(60_000) // still unreachable: stays pending, no retries burn
        assertEquals(listOf(DeliveryStatus.PENDING), a.deliveries(messageId))

        sim.connect("D", "E")
        settle(2_000)
        assertEquals(1, e.directMessages().size)
        assertEquals(DeliveryStatus.DELIVERED, a.deliveries(messageId).last())
    }

    @Test
    fun pendingDmFailsAfter24Hours() = runTest {
        val sim = MeshSim(this)
        sim.add("A", "B")
        sim.connect("A", "B")
        settle()
        val b = sim["B"]
        sim.disconnect("A", "B")
        settle(100_000)
        val messageId = MessageId.random(ids)
        sim["A"].mesh.sendDirectMessage(b.id, messageId, "hello?")
        settle()
        assertEquals(DeliveryStatus.PENDING, sim["A"].deliveries(messageId).last())
        settle(24 * 60 * 60 * 1000L + 10_000)
        assertEquals(DeliveryStatus.FAILED, sim["A"].deliveries(messageId).last())
    }

    @Test
    fun unacknowledgedDmFailsAfterThreeResends() = runTest {
        val sim = line()
        val a = sim["A"]
        val e = sim["E"]
        // Swallow every PRIVATE packet from A at D: E never gets it, but E stays reachable (its announces flow).
        sim.net.interceptor = { from, _, bytes ->
            val type = PacketCodec.peekType(bytes)
            if (from == "D" && type == PacketType.PRIVATE) null else bytes
        }
        val messageId = MessageId.random(ids)
        a.mesh.sendDirectMessage(e.id, messageId, "lost in the void")
        settle(4 * 30_000 + 5_000)
        assertEquals(DeliveryStatus.FAILED, a.deliveries(messageId).last())
        assertEquals(4, sim.privatePackets(a.id, e.id).size)
    }

    @Test
    fun relayOffOnCStopsNearbyMessageBeforeDAndE() = runTest {
        val sim = line()
        sim["C"].mesh.setRelayEnabled(false)
        val packetId = sim["A"].mesh.sendPublic("anyone?")
        settle()
        assertEquals(1, sim["B"].publicMessages(packetId).size)
        assertEquals(1, sim["C"].publicMessages(packetId).size)
        assertEquals(0, sim["D"].publicMessages(packetId).size)
        assertEquals(0, sim["E"].publicMessages(packetId).size)
        assertNull(sim["C"].relayed[packetId])
    }

    @Test
    fun corruptedByteAtBIsRejectedByCAndNotRelayed() = runTest {
        val sim = line()
        val a = sim["A"]
        sim.net.interceptor = { from, _, bytes ->
            if (from == "B" && PacketCodec.peekType(bytes) == PacketType.PUBLIC) {
                bytes.copyOf().also { it[42] = (it[42].toInt() xor 0x01).toByte() } // a timestamp byte
            } else {
                bytes
            }
        }
        val invalidBefore = sim["C"].mesh.stats.value.droppedInvalid
        val packetId = a.mesh.sendPublic("tamper with me")
        settle()
        assertEquals(1, sim["B"].publicMessages(packetId).size)
        assertEquals(0, sim["C"].publicMessages(packetId).size)
        assertEquals(0, sim["D"].publicMessages(packetId).size)
        assertEquals(0, sim["E"].publicMessages(packetId).size)
        assertNull(sim["C"].relayed[packetId])
        assertTrue(DropReason.BAD_SIGNATURE in sim["C"].dropped)
        assertEquals(invalidBefore + 1, sim["C"].mesh.stats.value.droppedInvalid)
        assertTrue(sim.net.wire.none { it.from == "C" && it.packet?.packetId == packetId })
    }

    @Test
    fun receiptsTypingAndBlocking() = runTest {
        val sim = MeshSim(this)
        sim.add("A", "B")
        sim.connect("A", "B")
        settle()
        val a = sim["A"]
        val b = sim["B"]

        val messageId = MessageId.random(ids)
        a.mesh.sendDirectMessage(b.id, messageId, "read me")
        settle()
        b.mesh.sendReadReceipt(a.id, messageId)
        settle()
        assertEquals(DeliveryStatus.READ, a.deliveries(messageId).last())

        a.mesh.sendTyping(b.id)
        a.mesh.sendTyping(b.id) // throttled: within 3 s
        settle()
        assertEquals(1, b.events.count { it is MeshEvent.Typing })
        settle(3_000)
        a.mesh.sendTyping(b.id)
        settle()
        assertEquals(2, b.events.count { it is MeshEvent.Typing })

        b.mesh.setBlocked(setOf(a.id))
        val hidden = a.mesh.sendPublic("you can't see me")
        val hiddenDm = MessageId.random(ids)
        a.mesh.sendDirectMessage(b.id, hiddenDm, "nor this")
        settle()
        assertTrue(b.publicMessages(hidden).isEmpty())
        assertFalse(b.directMessages().any { it.messageId == hiddenDm })
    }

    @Test
    fun linkPeerIsIdentifiedAndLeaveMarksOffline() = runTest {
        val sim = MeshSim(this)
        sim.add("A", "B", "C")
        sim.connect("A", "B")
        sim.connect("B", "C")
        settle()
        val a = sim["A"]
        assertEquals(setOf(sim["B"].id), a.mesh.linkPeers.value.values.toSet())
        assertTrue(a.events.any { it is MeshEvent.LinkIdentified && it.peerId == sim["B"].id })
        assertEquals(PeerStatus.VIA_MESH, a.mesh.peers.value[sim["C"].id]?.status)
        assertEquals(2, a.mesh.peers.value[sim["C"].id]?.hops)

        sim["C"].mesh.sendLeave()
        settle()
        assertEquals(PeerStatus.OFFLINE, a.mesh.peers.value[sim["C"].id]?.status)

        sim.disconnect("A", "B")
        settle()
        assertTrue(a.mesh.linkPeers.value.isEmpty())
        assertEquals(PeerStatus.VIA_MESH, a.mesh.peers.value[sim["B"].id]?.status)
        settle(91_000)
        assertEquals(PeerStatus.OFFLINE, a.mesh.peers.value[sim["B"].id]?.status)
    }
}
