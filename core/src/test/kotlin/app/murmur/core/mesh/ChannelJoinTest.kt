package app.murmur.core.mesh

import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.ChannelCrypto
import app.murmur.core.protocol.ChannelInvites
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.RoomKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.3: finding and joining channels — invites, discovery, password mismatches. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelJoinTest {
    private val random = SeededRandomSource(1313)

    private fun TestScope.settle(millis: Long = 1_000) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.trio(): MeshSim = MeshSim(this).apply {
        add("A", "B", "C")
        connect("A", "B")
        connect("B", "C")
        settle()
    }

    private fun SimNode.seen() = events.filterIsInstance<MeshEvent.ChannelSeen>()

    @Test
    fun inviteBodyRoundTrips() {
        val key = ChannelCrypto.deriveKey("crew", "pw", iterations = 1_000)
        val locked = ChannelInvites.parse(ChannelInvites.body("crew", key))!!
        assertEquals("crew", locked.channel)
        assertArrayEquals(key, locked.key)
        assertTrue(locked.locked)
        val open = ChannelInvites.parse(ChannelInvites.body("hiking", null))!!
        assertNull(open.key)
        assertNotNull("later fields ignored", ChannelInvites.parse(ChannelInvites.body("hiking", null) + " more"))
        assertNull(ChannelInvites.parse("1 Bad-Name -"))
        assertNull(ChannelInvites.parse("1 crew 1234"))
        assertNull(ChannelInvites.parse("9 crew -"))
    }

    @Test
    fun inviteArrivesAsAnEncryptedDirectControl() = runTest {
        val sim = trio()
        val key = ChannelCrypto.deriveKey("crew", "pw", iterations = 1_000)
        assertTrue(sim["A"].mesh.sendDirectControl(sim["C"].identity.peerId, DmKind.CHANNEL_INVITE, MessageId.random(random), ChannelInvites.body("crew", key)))
        settle()
        val got = sim["C"].events.filterIsInstance<MeshEvent.DirectControl>().single()
        assertEquals(DmKind.CHANNEL_INVITE, got.kind)
        assertArrayEquals(key, ChannelInvites.parse(got.body)!!.key)
        assertTrue(sim["B"].events.none { it is MeshEvent.DirectControl })
    }

    @Test
    fun nonMembersDiscoverChannelsButCantReadPasswordOnes() = runTest {
        val sim = trio()
        val key = ChannelCrypto.deriveKey("crew", "pw", iterations = 1_000)
        sim["A"].mesh.setChannelKeys(mapOf("crew" to key))
        sim["A"].mesh.sendRoom("crew", RoomKind.TEXT, "secret", key = key)
        sim["A"].mesh.sendRoom("hiking", RoomKind.TEXT, "open")
        settle()
        val seen = sim["C"].seen().associateBy { it.channel }
        assertEquals(setOf("crew", "hiking"), seen.keys)
        assertTrue(seen.getValue("crew").encrypted)
        assertFalse(seen.getValue("crew").readable)
        assertTrue(seen.getValue("hiking").readable)
        assertTrue("#nearby isn't a channel", sim["C"].seen().none { it.channel.isEmpty() })
    }

    @Test
    fun wrongPasswordIsReportedAsUnreadable() = runTest {
        val sim = trio()
        val right = ChannelCrypto.deriveKey("crew", "right", iterations = 1_000)
        val wrong = ChannelCrypto.deriveKey("crew", "wrong", iterations = 1_000)
        sim["C"].mesh.setChannelKeys(mapOf("crew" to wrong))
        sim["A"].mesh.sendRoom("crew", RoomKind.TEXT, "hi", key = right)
        settle()
        assertFalse(sim["C"].seen().single().readable)
        assertTrue(sim["C"].events.none { it is MeshEvent.RoomMessage })
    }

    @Test
    fun discoveryIsRateLimited() = runTest {
        val sim = trio()
        repeat(5) { sim["A"].mesh.sendRoom("hiking", RoomKind.TEXT, "msg $it") }
        settle()
        assertEquals(1, sim["C"].seen().size)
        settle(25_000)
        sim["A"].mesh.sendRoom("hiking", RoomKind.TEXT, "later")
        settle()
        assertEquals(2, sim["C"].seen().size)
    }
}
