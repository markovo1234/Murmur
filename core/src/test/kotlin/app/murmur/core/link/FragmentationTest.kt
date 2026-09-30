package app.murmur.core.link

import app.murmur.core.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FragmentationTest {
    private val fixedClock = Clock { 0L }

    @Test
    fun everySizeFrom1To5000ReassemblesAtChunkSizes20And182And512() {
        val rnd = java.util.Random(2024)
        for (chunk in intArrayOf(20, 182, 512)) {
            val reassembler = Reassembler(fixedClock)
            for (size in 1..5000) {
                val data = ByteArray(size).also(rnd::nextBytes)
                val fragments = Fragmenter.fragment(data, chunk, streamId = size * 31 + chunk)
                var out: ByteArray? = null
                fragments.forEachIndexed { i, f ->
                    assertTrue("fragment ${f.size} > chunk $chunk", f.size <= chunk)
                    val result = reassembler.accept(f)
                    if (i < fragments.lastIndex) assertNull(result) else out = result
                }
                assertArrayEquals("size $size chunk $chunk", data, out)
            }
            assertEquals(0, reassembler.pendingStreams)
            assertEquals(0, reassembler.pendingBytes)
        }
    }

    @Test
    fun interleavedStreamsReassemble() {
        val rnd = java.util.Random(5)
        val payloads = List(6) { i -> ByteArray(300 + i * 457).also(rnd::nextBytes) }
        val perStream = payloads.mapIndexed { i, p -> Fragmenter.fragment(p, 20, streamId = 1000 + i).toMutableList() }

        // Round-robin across streams, in order within each stream.
        val reassembler = Reassembler(fixedClock)
        val results = mutableListOf<ByteArray>()
        val queues = perStream.map { ArrayDeque(it) }
        while (queues.any { it.isNotEmpty() }) {
            for (q in queues) q.removeFirstOrNull()?.let { f -> reassembler.accept(f)?.let(results::add) }
        }
        assertEquals(payloads.size, results.size)
        for (p in payloads) assertTrue(results.any { it.contentEquals(p) })

        // Fully shuffled, including out-of-order fragments within a stream, with duplicates.
        val shuffled = (perStream.flatten() + perStream.flatten().take(40)).shuffled(java.util.Random(9))
        val second = Reassembler(fixedClock)
        val results2 = shuffled.mapNotNull { second.accept(it) }
        assertEquals(payloads.size, results2.size)
        for (p in payloads) assertTrue(results2.any { it.contentEquals(p) })
    }

    @Test
    fun streamMissingAFragmentIsDroppedAfter30SecondsAndMemoryFreed() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val reassembler = Reassembler(clock, backgroundScope)
        val fragments = Fragmenter.fragment(ByteArray(900) { it.toByte() }, 182, streamId = 77)
        assertTrue(fragments.size >= 3)
        fragments.dropLast(1).forEach { assertNull(reassembler.accept(it)) }
        assertEquals(1, reassembler.pendingStreams)
        assertTrue(reassembler.pendingBytes > 0)

        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, reassembler.pendingStreams)

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(0, reassembler.pendingStreams)
        assertEquals(0, reassembler.pendingBytes)

        // The late last fragment cannot resurrect the dropped stream.
        assertNull(reassembler.accept(fragments.last()))
        reassembler.close()
    }

    @Test
    fun singleFragmentPacketsNeedNoState() {
        val r = Reassembler(fixedClock)
        val data = byteArrayOf(1, 2, 3)
        val out = r.accept(Fragmenter.fragment(data, 512, 1).single())
        assertNotNull(out)
        assertArrayEquals(data, out)
        assertEquals(0, r.pendingStreams)
    }

    @Test
    fun malformedFragmentsAreRejected() {
        val r = Reassembler(fixedClock)
        assertNull(r.accept(ByteArray(0)))
        assertNull(r.accept(ByteArray(5)))
        val f = Fragmenter.fragment(ByteArray(100), 20, 3)[0].copyOf()
        f[0] = 0 // wrong marker
        assertNull(r.accept(f))
        assertEquals(3, r.rejected)
    }

    @Test
    fun chunkSizeFollowsMtu() {
        assertEquals(20, Fragmenter.chunkSizeForMtu(23))
        assertEquals(182, Fragmenter.chunkSizeForMtu(185))
        assertEquals(512, Fragmenter.chunkSizeForMtu(517))
    }
}
