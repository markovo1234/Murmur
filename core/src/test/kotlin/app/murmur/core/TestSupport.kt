package app.murmur.core

import app.murmur.core.protocol.DecodeResult
import app.murmur.core.protocol.Packet
import app.murmur.core.protocol.PacketCodec
import org.junit.Assert.fail

/** A fixed, realistic epoch so timestamp windows behave like production. */
const val BASE_TIME: Long = 1_750_000_000_000L

fun decodeOk(bytes: ByteArray): Packet = when (val r = PacketCodec.decode(bytes)) {
    is DecodeResult.Ok -> r.packet
    is DecodeResult.Error -> {
        fail("expected a valid packet, got: ${r.reason}")
        error("unreachable")
    }
}
