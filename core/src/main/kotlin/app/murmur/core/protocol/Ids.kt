package app.murmur.core.protocol

import app.murmur.core.RandomSource
import app.murmur.core.crypto.Sha256

/**
 * 8-byte peer identifier = first 8 bytes of SHA-256(Ed25519 public key).
 * Ordering is unsigned big-endian, i.e. the same as comparing the raw bytes.
 */
@JvmInline
value class PeerId(val raw: Long) : Comparable<PeerId> {
    override fun compareTo(other: PeerId): Int = java.lang.Long.compareUnsigned(raw, other.raw)

    val isBroadcast: Boolean get() = raw == -1L

    fun toBytes(): ByteArray = Bytes.longToBytes(raw)

    fun toHex(): String = Bytes.toHex(toBytes())

    /** Last 4 hex digits, used as a "#1a2b" tag when nicknames collide. */
    val shortTag: String get() = toHex().takeLast(4)

    override fun toString(): String = toHex()

    companion object {
        const val SIZE: Int = 8
        val BROADCAST: PeerId = PeerId(-1L)

        fun fromBytes(bytes: ByteArray, offset: Int = 0): PeerId = PeerId(Bytes.bytesToLong(bytes, offset))

        fun fromPublicKey(ed25519PublicKey: ByteArray): PeerId = fromBytes(Sha256.hash(ed25519PublicKey), 0)

        fun fromHex(hex: String): PeerId? {
            if (hex.length != SIZE * 2) return null
            val bytes = Bytes.fromHex(hex) ?: return null
            return fromBytes(bytes)
        }
    }
}

/** 16-byte random packet identifier. */
data class PacketId(val hi: Long, val lo: Long) {
    fun toBytes(): ByteArray = Bytes.longToBytes(hi) + Bytes.longToBytes(lo)
    fun toHex(): String = Bytes.toHex(toBytes())
    override fun toString(): String = toHex()

    companion object {
        const val SIZE: Int = 16
        fun fromBytes(bytes: ByteArray, offset: Int = 0): PacketId =
            PacketId(Bytes.bytesToLong(bytes, offset), Bytes.bytesToLong(bytes, offset + 8))

        fun random(random: RandomSource): PacketId = fromBytes(random.nextBytes(SIZE))
        fun fromHex(hex: String): PacketId? =
            if (hex.length == SIZE * 2) Bytes.fromHex(hex)?.let { fromBytes(it) } else null
    }
}

/** 16-byte message identifier, stable across resends of the same DM. */
data class MessageId(val hi: Long, val lo: Long) {
    fun toBytes(): ByteArray = Bytes.longToBytes(hi) + Bytes.longToBytes(lo)
    fun toHex(): String = Bytes.toHex(toBytes())
    override fun toString(): String = toHex()

    companion object {
        const val SIZE: Int = 16
        fun fromBytes(bytes: ByteArray, offset: Int = 0): MessageId =
            MessageId(Bytes.bytesToLong(bytes, offset), Bytes.bytesToLong(bytes, offset + 8))

        fun random(random: RandomSource): MessageId = fromBytes(random.nextBytes(SIZE))
        fun fromHex(hex: String): MessageId? =
            if (hex.length == SIZE * 2) Bytes.fromHex(hex)?.let { fromBytes(it) } else null
    }
}

object Bytes {
    private const val HEX = "0123456789abcdef"

    fun longToBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    fun bytesToLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Unsigned lexicographic comparison. */
    fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
