package app.murmur.core.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Thrown internally by [ByteReader]; codecs catch it and turn it into a decode error. */
internal class DecodeException(message: String) : Exception(message)

internal class ByteWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(v: Int) {
        require(v in 0..0xFF) { "u8 out of range: $v" }
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        ensure(2)
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun u32(v: Int) {
        ensure(4)
        for (i in 3 downTo 0) buf[size++] = (v ushr (8 * i)).toByte()
    }

    fun i64(v: Long) {
        ensure(8)
        for (i in 7 downTo 0) buf[size++] = (v ushr (8 * i)).toByte()
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    /** u8 length prefix + UTF-8 bytes. */
    fun string8(s: String) {
        val b = s.encodeToByteArray()
        u8(b.size)
        bytes(b)
    }

    /** u16 length prefix + UTF-8 bytes. */
    fun string16(s: String) {
        val b = s.encodeToByteArray()
        u16(b.size)
        bytes(b)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

internal class ByteReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    val remaining: Int get() = end - pos
    val position: Int get() = pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw DecodeException("truncated: need $n, have $remaining")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u32(): Int {
        need(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xFF) }
        return v
    }

    fun i64(): Long {
        need(8)
        val v = Bytes.bytesToLong(data, pos)
        pos += 8
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun rest(): ByteArray = bytes(remaining)

    fun string8(maxBytes: Int): String {
        val n = u8()
        if (n > maxBytes) throw DecodeException("string too long: $n > $maxBytes")
        return strictUtf8(bytes(n))
    }

    fun string16(maxBytes: Int): String {
        val n = u16()
        if (n > maxBytes) throw DecodeException("string too long: $n > $maxBytes")
        return strictUtf8(bytes(n))
    }

    fun expectEnd() {
        if (remaining != 0) throw DecodeException("$remaining trailing bytes")
    }

    companion object {
        /** Rejects malformed UTF-8 so every accepted string re-encodes to the identical bytes. */
        fun strictUtf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw DecodeException("invalid UTF-8")
        }
    }
}
