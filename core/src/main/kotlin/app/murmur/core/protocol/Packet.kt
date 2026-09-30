package app.murmur.core.protocol

import app.murmur.core.Murmur
import app.murmur.core.crypto.DmCrypto
import app.murmur.core.crypto.Ed25519
import app.murmur.core.crypto.SigningKeyPair
import app.murmur.core.crypto.X25519

enum class PacketType(val code: Int) {
    ANNOUNCE(1),
    PUBLIC(2),
    PRIVATE(3),
    LEAVE(4);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/** Typed packet payloads. Layouts are documented in PROTOCOL.md. */
sealed interface Payload {
    val type: PacketType

    /** ANNOUNCE: who I am and how to encrypt to me. */
    class Announce(
        val nickname: String,
        val emoji: String,
        val colorIndex: Int,
        val agreementKey: ByteArray,
    ) : Payload {
        override val type: PacketType get() = PacketType.ANNOUNCE

        override fun equals(other: Any?): Boolean = other is Announce && nickname == other.nickname &&
            emoji == other.emoji && colorIndex == other.colorIndex && agreementKey.contentEquals(other.agreementKey)

        override fun hashCode(): Int = nickname.hashCode() * 31 + agreementKey.contentHashCode()
        override fun toString(): String = "Announce($nickname, $emoji, $colorIndex)"
    }

    /** PUBLIC: a #nearby message. */
    data class Public(val nickname: String, val text: String) : Payload {
        override val type: PacketType get() = PacketType.PUBLIC
    }

    /** PRIVATE: an end-to-end encrypted [DmContent]. Relays cannot read it. */
    class Private(val ephemeralKey: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray) : Payload {
        override val type: PacketType get() = PacketType.PRIVATE

        override fun equals(other: Any?): Boolean = other is Private &&
            ephemeralKey.contentEquals(other.ephemeralKey) && nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)

        override fun hashCode(): Int = ciphertext.contentHashCode()
        override fun toString(): String = "Private(${ciphertext.size} bytes)"
    }

    /** LEAVE: graceful shutdown. Empty. */
    data object Leave : Payload {
        override val type: PacketType get() = PacketType.LEAVE
    }
}

/**
 * A decoded (or freshly built) packet. [raw] holds the exact wire bytes the signature covers, so relays
 * forward the original bytes with only the ttl byte changed.
 */
class Packet internal constructor(
    val version: Int,
    val ttl: Int,
    val packetId: PacketId,
    val senderId: PeerId,
    val recipientId: PeerId,
    val timestamp: Long,
    val senderKey: ByteArray,
    val payload: Payload,
    val signature: ByteArray,
    private val raw: ByteArray,
) {
    val type: PacketType get() = payload.type
    val isBroadcast: Boolean get() = recipientId.isBroadcast

    /** 1 for a packet heard directly from its sender. */
    val hops: Int get() = Murmur.INITIAL_TTL + 1 - ttl

    /** Wire bytes (a copy). */
    fun encode(): ByteArray = raw.copyOf()

    internal fun rawBytes(): ByteArray = raw

    /** Same packet with a new ttl. The signature stays valid because it excludes the ttl byte. */
    fun withTtl(newTtl: Int): Packet {
        require(newTtl in 1..Murmur.INITIAL_TTL)
        val bytes = raw.copyOf()
        bytes[PacketCodec.TTL_OFFSET] = newTtl.toByte()
        return Packet(version, newTtl, packetId, senderId, recipientId, timestamp, senderKey, payload, signature, bytes)
    }

    override fun toString(): String = "Packet($type ttl=$ttl id=${packetId.toHex().take(8)} from=$senderId to=$recipientId)"
}

sealed interface DecodeResult {
    class Ok(val packet: Packet) : DecodeResult
    class Error(val reason: String) : DecodeResult
}

enum class VerifyResult { OK, SENDER_KEY_MISMATCH, BAD_SIGNATURE }

object PacketCodec {
    const val TTL_OFFSET: Int = 2
    const val HEADER_SIZE: Int = 77
    const val SIGNATURE_SIZE: Int = Ed25519.SIGNATURE_SIZE
    const val OVERHEAD: Int = HEADER_SIZE + SIGNATURE_SIZE
    const val MAX_PAYLOAD: Int = 2048
    const val MAX_PACKET: Int = OVERHEAD + MAX_PAYLOAD
    const val MAX_NICKNAME_BYTES: Int = 80

    /** Smallest valid PRIVATE ciphertext: an empty-bodied [DmContent] plus the Poly1305 tag. */
    private const val MIN_PRIVATE_CIPHERTEXT = DmContent.MIN_SIZE + DmCrypto.TAG_SIZE

    /** Builds and signs a packet. */
    fun create(
        signing: SigningKeyPair,
        payload: Payload,
        recipientId: PeerId,
        ttl: Int,
        packetId: PacketId,
        timestamp: Long,
    ): Packet = assemble(PeerId.fromPublicKey(signing.publicKey), signing, payload, recipientId, ttl, packetId, timestamp)

    /** Like [create] but with an arbitrary senderId; only tests use a mismatching one. */
    internal fun assemble(
        senderId: PeerId,
        signing: SigningKeyPair,
        payload: Payload,
        recipientId: PeerId,
        ttl: Int,
        packetId: PacketId,
        timestamp: Long,
    ): Packet {
        require(ttl in 1..Murmur.INITIAL_TTL) { "ttl out of range: $ttl" }
        val payloadBytes = encodePayload(payload)
        require(payloadBytes.size <= MAX_PAYLOAD) { "payload too large: ${payloadBytes.size}" }
        val w = ByteWriter(OVERHEAD + payloadBytes.size)
        w.u8(Murmur.PROTOCOL_VERSION)
        w.u8(payload.type.code)
        w.u8(ttl)
        w.bytes(packetId.toBytes())
        w.bytes(senderId.toBytes())
        w.bytes(recipientId.toBytes())
        w.i64(timestamp)
        w.bytes(signing.publicKey)
        w.u16(payloadBytes.size)
        w.bytes(payloadBytes)
        val body = w.toByteArray()
        val signature = signing.sign(body to (0 until TTL_OFFSET), body to (TTL_OFFSET + 1 until body.size))
        return Packet(
            version = Murmur.PROTOCOL_VERSION,
            ttl = ttl,
            packetId = packetId,
            senderId = senderId,
            recipientId = recipientId,
            timestamp = timestamp,
            senderKey = signing.publicKey,
            payload = payload,
            signature = signature,
            raw = body + signature,
        )
    }

    /** Structural decode. Never throws; does not check the signature (see [verify]). */
    fun decode(bytes: ByteArray): DecodeResult = try {
        DecodeResult.Ok(decodeOrThrow(bytes))
    } catch (e: DecodeException) {
        DecodeResult.Error(e.message ?: "malformed")
    } catch (e: RuntimeException) {
        DecodeResult.Error("malformed: ${e.javaClass.simpleName}")
    }

    /** senderId must equal the hash of the included key, and the signature must cover everything but ttl. */
    fun verify(packet: Packet): VerifyResult {
        if (PeerId.fromPublicKey(packet.senderKey) != packet.senderId) return VerifyResult.SENDER_KEY_MISMATCH
        val raw = packet.rawBytes()
        val sigStart = raw.size - SIGNATURE_SIZE
        val ok = Ed25519.verify(
            packet.senderKey,
            packet.signature,
            raw to (0 until TTL_OFFSET),
            raw to (TTL_OFFSET + 1 until sigStart),
        )
        return if (ok) VerifyResult.OK else VerifyResult.BAD_SIGNATURE
    }

    /** Reads the type byte without a full decode (for diagnostics and tests). */
    fun peekType(bytes: ByteArray): PacketType? = if (bytes.size > 1) PacketType.fromCode(bytes[1].toInt() and 0xFF) else null

    private fun decodeOrThrow(bytes: ByteArray): Packet {
        if (bytes.size < OVERHEAD) throw DecodeException("too short: ${bytes.size}")
        if (bytes.size > MAX_PACKET) throw DecodeException("too long: ${bytes.size}")
        val r = ByteReader(bytes)
        val version = r.u8()
        if (version != Murmur.PROTOCOL_VERSION) throw DecodeException("unsupported version $version")
        val typeCode = r.u8()
        val type = PacketType.fromCode(typeCode) ?: throw DecodeException("unknown type $typeCode")
        val ttl = r.u8()
        if (ttl !in 1..Murmur.INITIAL_TTL) throw DecodeException("bad ttl $ttl")
        val packetId = PacketId.fromBytes(r.bytes(PacketId.SIZE))
        val senderId = PeerId.fromBytes(r.bytes(PeerId.SIZE))
        val recipientId = PeerId.fromBytes(r.bytes(PeerId.SIZE))
        val timestamp = r.i64()
        val senderKey = r.bytes(Ed25519.PUBLIC_KEY_SIZE)
        val length = r.u16()
        if (length > MAX_PAYLOAD || bytes.size != OVERHEAD + length) throw DecodeException("length mismatch")
        val payloadBytes = r.bytes(length)
        val signature = r.bytes(SIGNATURE_SIZE)
        r.expectEnd()

        if (senderId.isBroadcast) throw DecodeException("broadcast sender")
        when (type) {
            PacketType.PRIVATE -> if (recipientId.isBroadcast) throw DecodeException("private to broadcast")
            else -> if (!recipientId.isBroadcast) throw DecodeException("$type must be broadcast")
        }
        val payload = decodePayload(type, payloadBytes)
        return Packet(version, ttl, packetId, senderId, recipientId, timestamp, senderKey, payload, signature, bytes.copyOf())
    }

    fun encodePayload(payload: Payload): ByteArray {
        val w = ByteWriter()
        when (payload) {
            is Payload.Announce -> {
                require(Murmur.isValidNickname(payload.nickname)) { "invalid nickname" }
                require(payload.agreementKey.size == X25519.PUBLIC_KEY_SIZE)
                require(payload.colorIndex in 0 until Murmur.AVATAR_COLOR_COUNT)
                w.string8(payload.nickname)
                w.string8(payload.emoji)
                w.u8(payload.colorIndex)
                w.bytes(payload.agreementKey)
            }
            is Payload.Public -> {
                require(Murmur.isValidNickname(payload.nickname)) { "invalid nickname" }
                require(payload.text.isNotEmpty() && Murmur.utf8Size(payload.text) <= Murmur.MAX_TEXT_BYTES) { "bad text" }
                w.string8(payload.nickname)
                w.string16(payload.text)
            }
            is Payload.Private -> {
                w.bytes(payload.ephemeralKey)
                w.bytes(payload.nonce)
                w.bytes(payload.ciphertext)
            }
            Payload.Leave -> Unit
        }
        return w.toByteArray()
    }

    private fun decodePayload(type: PacketType, bytes: ByteArray): Payload {
        val r = ByteReader(bytes)
        val payload = when (type) {
            PacketType.ANNOUNCE -> {
                val nickname = readNickname(r)
                val emoji = r.string8(Murmur.MAX_EMOJI_BYTES)
                if (emoji.isEmpty() || emoji.any { it.isISOControl() }) throw DecodeException("bad emoji")
                val color = r.u8()
                if (color >= Murmur.AVATAR_COLOR_COUNT) throw DecodeException("bad color $color")
                Payload.Announce(nickname, emoji, color, r.bytes(X25519.PUBLIC_KEY_SIZE))
            }
            PacketType.PUBLIC -> {
                val nickname = readNickname(r)
                val text = r.string16(Murmur.MAX_TEXT_BYTES)
                if (text.isEmpty()) throw DecodeException("empty text")
                Payload.Public(nickname, text)
            }
            PacketType.PRIVATE -> {
                val eph = r.bytes(X25519.PUBLIC_KEY_SIZE)
                val nonce = r.bytes(DmCrypto.NONCE_SIZE)
                val ciphertext = r.rest()
                if (ciphertext.size < MIN_PRIVATE_CIPHERTEXT) throw DecodeException("ciphertext too short")
                Payload.Private(eph, nonce, ciphertext)
            }
            PacketType.LEAVE -> Payload.Leave
        }
        r.expectEnd()
        return payload
    }

    private fun readNickname(r: ByteReader): String {
        val nickname = r.string8(MAX_NICKNAME_BYTES)
        if (!Murmur.isValidNickname(nickname)) throw DecodeException("bad nickname")
        return nickname
    }
}
