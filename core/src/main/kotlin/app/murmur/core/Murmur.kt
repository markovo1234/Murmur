package app.murmur.core

import java.security.SecureRandom

/** Protocol-wide constants shared by the mesh and the BLE transport. */
object Murmur {
    /** Wire protocol version (first byte of every packet). */
    const val PROTOCOL_VERSION: Int = 1

    /** Every packet type starts at this ttl. hops = [INITIAL_TTL] + 1 - received ttl. */
    const val INITIAL_TTL: Int = 7

    /** The single Murmur GATT service. */
    const val SERVICE_UUID: String = "8a51d968-575d-4be3-871d-5400a225aa2d"

    /** The one characteristic (WRITE + NOTIFY) inside [SERVICE_UUID]. */
    const val CHARACTERISTIC_UUID: String = "4a05bee7-c4c9-45a1-b06d-72d674c025b1"

    /** Client Characteristic Configuration Descriptor. */
    const val CCCD_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"

    const val NICKNAME_MIN_CHARS: Int = 1
    const val NICKNAME_MAX_CHARS: Int = 20
    const val MAX_TEXT_BYTES: Int = 1000
    const val MAX_EMOJI_BYTES: Int = 32
    const val AVATAR_COLOR_COUNT: Int = 10

    /** Number of Unicode code points in [nickname] (what the user perceives as characters, roughly). */
    fun nicknameLength(nickname: String): Int = nickname.codePointCount(0, nickname.length)

    fun isValidNickname(nickname: String): Boolean {
        val trimmed = nickname.trim()
        if (trimmed != nickname) return false
        val n = nicknameLength(nickname)
        return n in NICKNAME_MIN_CHARS..NICKNAME_MAX_CHARS && nickname.none { it.isISOControl() }
    }

    fun utf8Size(text: String): Int = text.encodeToByteArray().size
}

/** Wall clock in epoch milliseconds. Injected everywhere so tests can run on virtual time. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Source of random bytes. Production uses [SecureRandomSource]; tests use a fixed seed. */
fun interface RandomSource {
    fun nextBytes(size: Int): ByteArray
}

class SecureRandomSource(private val random: SecureRandom = SecureRandom()) : RandomSource {
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

/** Deterministic, NOT cryptographically secure. For tests and demo mode only. */
class SeededRandomSource(seed: Long) : RandomSource {
    private val random = java.util.Random(seed)

    @Synchronized
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
