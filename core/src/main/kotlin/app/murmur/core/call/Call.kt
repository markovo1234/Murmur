package app.murmur.core.call

import app.murmur.core.crypto.CallCrypto
import app.murmur.core.protocol.Bytes
import java.util.TreeMap

/** Bodies of the CALL_OFFER / CALL_ANSWER / CALL_END direct messages (1.2). */
object CallSignal {
    const val VERSION: Int = 1
    const val CODEC_AMR_NB: String = "amrnb"

    class Offer(val codec: String, val key: ByteArray)

    /** "1 amrnb <64 hex digits>". */
    fun offerBody(key: ByteArray, codec: String = CODEC_AMR_NB): String {
        require(key.size == CallCrypto.KEY_SIZE)
        return "$VERSION $codec ${Bytes.toHex(key)}"
    }

    /** Null if malformed or from an incompatible version. Extra fields are ignored. */
    fun parseOffer(body: String): Offer? {
        val parts = body.trim().split(' ')
        if (parts.size < 3 || parts[0].toIntOrNull() != VERSION) return null
        val key = Bytes.fromHex(parts[2]) ?: return null
        if (key.size != CallCrypto.KEY_SIZE || parts[1].isEmpty()) return null
        return Offer(parts[1], key)
    }

    enum class Answer(val wire: String) {
        RINGING("ringing"),
        ACCEPT("accept"),
        DECLINE("decline"),
        BUSY("busy"),

        /** The callee's app can't do calls with this codec (or at all right now). */
        UNSUPPORTED("unsupported"),
        ;

        companion object {
            fun fromWire(s: String): Answer? = entries.firstOrNull { it.wire == s.trim() }
        }
    }

    enum class EndReason(val wire: String) {
        HANGUP("hangup"),
        CANCEL("cancel"),
        TIMEOUT("timeout"),
        FAILED("failed"),
        ;

        companion object {
            fun fromWire(s: String): EndReason = entries.firstOrNull { it.wire == s.trim() } ?: HANGUP
        }
    }
}

/**
 * AMR-NB frames in "storage" format (RFC 4867 §5): one header byte (frame type in bits 3–6) followed by
 * the speech bits. A CALL packet's plaintext is several such frames back to back.
 */
object Amr {
    const val SAMPLE_RATE: Int = 8_000
    const val FRAME_MILLIS: Int = 20
    const val SAMPLES_PER_FRAME: Int = SAMPLE_RATE * FRAME_MILLIS / 1000

    /** Speech bytes after the header, by frame type. -1 = not allowed. 15 = NO_DATA. */
    private val SPEECH_BYTES = intArrayOf(12, 13, 15, 17, 19, 20, 26, 31, 5, -1, -1, -1, -1, -1, -1, 0)

    /** Total size (header included) of the frame starting with [header], or -1 if invalid. */
    fun frameSize(header: Int): Int {
        val n = SPEECH_BYTES[(header ushr 3) and 0x0F]
        return if (n < 0) -1 else n + 1
    }

    /** Splits concatenated frames. Null if the bytes aren't a whole number of valid frames. */
    fun split(bytes: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>(6)
        var pos = 0
        while (pos < bytes.size) {
            val size = frameSize(bytes[pos].toInt() and 0xFF)
            if (size <= 0 || pos + size > bytes.size) return null
            out += bytes.copyOfRange(pos, pos + size)
            pos += size
        }
        return out.takeIf { it.isNotEmpty() }
    }
}

/**
 * Reorders audio chunks by sequence number and smooths out jitter. [pop] is called once per chunk
 * period by the player: it returns the next chunk, or null to play silence (lost, late or still
 * buffering). Not thread-safe: callers synchronise.
 */
class JitterBuffer(
    /** Chunks to collect before (re)starting playback. */
    private val startDepth: Int = 3,
    /** Beyond this, the oldest chunks are dropped to keep latency down. */
    private val maxDepth: Int = 10,
    /** A gap longer than this is skipped instead of played as silence. */
    private val maxGap: Int = 3,
) {
    private val chunks = TreeMap<Long, ByteArray>()
    private var next = -1L
    private var playing = false

    var lost: Long = 0
        private set
    var late: Long = 0
        private set

    val depth: Int get() = chunks.size

    fun push(seq: Long, chunk: ByteArray) {
        if (next >= 0 && seq < next) {
            late++
            return
        }
        chunks[seq] = chunk
        while (chunks.size > maxDepth) {
            chunks.pollFirstEntry()
            if (playing) next = chunks.firstKey()
        }
    }

    fun pop(): ByteArray? {
        if (!playing) {
            if (chunks.size < startDepth) return null
            playing = true
            next = chunks.firstKey()
        }
        chunks.remove(next)?.let {
            next++
            return it
        }
        if (chunks.isEmpty()) {
            // Ran dry: go back to buffering.
            playing = false
            lost++
            next++
            return null
        }
        val first = chunks.firstKey()
        if (first - next > maxGap) {
            lost += first - next
            next = first + 1
            return chunks.remove(first)
        }
        lost++
        next++
        return null
    }
}
