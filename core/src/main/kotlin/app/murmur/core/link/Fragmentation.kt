package app.murmur.core.link

import app.murmur.core.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Splits one packet into link-sized fragments. Every fragment, header included, fits in the chunk size.
 *
 * Fragment layout (big-endian):
 * ```
 * 0  u8   marker 0x4D ('M')
 * 1  u32  streamId   (per link, increments per packet)
 * 5  u16  index      (0-based)
 * 7  u16  count      (>= 1)
 * 9  ...  data
 * ```
 */
object Fragmenter {
    const val MARKER: Int = 0x4D
    const val HEADER_SIZE: Int = 9
    const val MIN_CHUNK_SIZE: Int = HEADER_SIZE + 1
    const val MAX_FRAGMENTS: Int = 0xFFFF

    /** BLE rule: fragments must fit min(MTU - 3, 512). */
    fun chunkSizeForMtu(mtu: Int): Int = maxOf(MIN_CHUNK_SIZE, minOf(mtu - 3, 512))

    fun fragment(data: ByteArray, chunkSize: Int, streamId: Int): List<ByteArray> {
        require(chunkSize >= MIN_CHUNK_SIZE) { "chunk size too small: $chunkSize" }
        require(data.isNotEmpty()) { "nothing to fragment" }
        val perFragment = chunkSize - HEADER_SIZE
        val count = (data.size + perFragment - 1) / perFragment
        require(count <= MAX_FRAGMENTS) { "too many fragments: $count" }
        return List(count) { index ->
            val start = index * perFragment
            val end = minOf(data.size, start + perFragment)
            val out = ByteArray(HEADER_SIZE + (end - start))
            out[0] = MARKER.toByte()
            out[1] = (streamId ushr 24).toByte()
            out[2] = (streamId ushr 16).toByte()
            out[3] = (streamId ushr 8).toByte()
            out[4] = streamId.toByte()
            out[5] = (index ushr 8).toByte()
            out[6] = index.toByte()
            out[7] = (count ushr 8).toByte()
            out[8] = count.toByte()
            data.copyInto(out, HEADER_SIZE, start, end)
            out
        }
    }
}

/**
 * Reassembles fragments from one link. Streams may interleave. A stream with no progress for
 * [timeoutMillis] is dropped and its memory freed (on the next fragment, [purgeExpired], or the periodic
 * sweep when a [scope] is given).
 *
 * Not thread-safe: use from the owning link's single thread.
 */
class Reassembler(
    private val clock: Clock,
    scope: CoroutineScope? = null,
    private val timeoutMillis: Long = 30_000,
    private val maxStreams: Int = 32,
    private val maxStreamBytes: Int = 16 * 1024,
    sweepIntervalMillis: Long = 5_000,
) {
    private class Stream(val count: Int, var lastActivity: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val streams = LinkedHashMap<Int, Stream>()
    private val sweeper: Job? = scope?.launch {
        while (isActive) {
            delay(sweepIntervalMillis)
            purgeExpired()
        }
    }

    /** Number of incomplete streams held in memory. */
    val pendingStreams: Int get() = synchronized(this) { streams.size }

    /** Bytes held by incomplete streams. */
    val pendingBytes: Int get() = synchronized(this) { streams.values.sumOf { it.bytes } }

    /** Number of fragments rejected as malformed. */
    var rejected: Int = 0
        private set

    /** Returns the complete payload once the last missing fragment arrives, otherwise null. */
    @Synchronized
    fun accept(fragment: ByteArray): ByteArray? {
        val now = clock.now()
        purgeExpiredLocked(now)
        if (fragment.size < Fragmenter.MIN_CHUNK_SIZE || (fragment[0].toInt() and 0xFF) != Fragmenter.MARKER) {
            rejected++
            return null
        }
        val streamId = ((fragment[1].toInt() and 0xFF) shl 24) or ((fragment[2].toInt() and 0xFF) shl 16) or
            ((fragment[3].toInt() and 0xFF) shl 8) or (fragment[4].toInt() and 0xFF)
        val index = ((fragment[5].toInt() and 0xFF) shl 8) or (fragment[6].toInt() and 0xFF)
        val count = ((fragment[7].toInt() and 0xFF) shl 8) or (fragment[8].toInt() and 0xFF)
        if (count == 0 || index >= count || count > maxStreamBytes) {
            rejected++
            return null
        }
        val dataSize = fragment.size - Fragmenter.HEADER_SIZE
        if (count == 1) {
            streams.remove(streamId)
            return fragment.copyOfRange(Fragmenter.HEADER_SIZE, fragment.size)
        }

        var stream = streams[streamId]
        if (stream != null && stream.count != count) {
            // A new stream reusing the id (e.g. counter wrapped after a reconnect): start over.
            streams.remove(streamId)
            stream = null
        }
        if (stream == null) {
            if (streams.size >= maxStreams) {
                val oldest = streams.keys.first()
                streams.remove(oldest)
            }
            stream = Stream(count, now)
            streams[streamId] = stream
        }
        stream.lastActivity = now
        if (stream.parts[index] != null) return null // duplicate
        if (stream.bytes + dataSize > maxStreamBytes) {
            streams.remove(streamId)
            rejected++
            return null
        }
        stream.parts[index] = fragment.copyOfRange(Fragmenter.HEADER_SIZE, fragment.size)
        stream.received++
        stream.bytes += dataSize
        if (stream.received < count) return null

        streams.remove(streamId)
        val out = ByteArray(stream.bytes)
        var pos = 0
        for (part in stream.parts) {
            part!!.copyInto(out, pos)
            pos += part.size
        }
        return out
    }

    @Synchronized
    fun purgeExpired() = purgeExpiredLocked(clock.now())

    @Synchronized
    fun clear() = streams.clear()

    fun close() {
        sweeper?.cancel()
        clear()
    }

    private fun purgeExpiredLocked(now: Long) {
        if (streams.isEmpty()) return
        val it = streams.values.iterator()
        while (it.hasNext()) {
            if (now - it.next().lastActivity >= timeoutMillis) it.remove()
        }
    }
}
