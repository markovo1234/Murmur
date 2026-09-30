package app.murmur.core.protocol

import app.murmur.core.Murmur
import app.murmur.core.RandomSource
import app.murmur.core.crypto.DmCrypto
import app.murmur.core.crypto.Identity
import app.murmur.core.crypto.X25519

enum class DmKind(val code: Int) {
    TEXT(1),
    DELIVERED(2),
    READ(3),
    TYPING(4);

    companion object {
        fun fromCode(code: Int): DmKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The inner plaintext of a PRIVATE packet. [senderAgreementKey] travels with every message so the
 * recipient can always reply, even if it never heard the sender's ANNOUNCE.
 */
class DmContent(
    val kind: DmKind,
    val messageId: MessageId,
    val senderAgreementKey: ByteArray,
    val body: String,
) {
    fun encode(): ByteArray {
        require(senderAgreementKey.size == X25519.PUBLIC_KEY_SIZE)
        require(Murmur.utf8Size(body) <= Murmur.MAX_TEXT_BYTES)
        val w = ByteWriter(MIN_SIZE + body.length * 3)
        w.u8(kind.code)
        w.bytes(messageId.toBytes())
        w.bytes(senderAgreementKey)
        w.string16(body)
        return w.toByteArray()
    }

    companion object {
        /** kind + messageId + key + empty body length. */
        const val MIN_SIZE: Int = 1 + MessageId.SIZE + X25519.PUBLIC_KEY_SIZE + 2

        fun decode(bytes: ByteArray): DmContent? = try {
            val r = ByteReader(bytes)
            val kind = DmKind.fromCode(r.u8()) ?: throw DecodeException("bad kind")
            val id = MessageId.fromBytes(r.bytes(MessageId.SIZE))
            val key = r.bytes(X25519.PUBLIC_KEY_SIZE)
            val body = r.string16(Murmur.MAX_TEXT_BYTES)
            r.expectEnd()
            if (kind == DmKind.TEXT && body.isEmpty()) throw DecodeException("empty text")
            DmContent(kind, id, key, body)
        } catch (_: DecodeException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Builds and opens PRIVATE packets. */
object PrivateMessages {
    /** AAD = packetId || senderId || recipientId || timestamp (big-endian ms). */
    fun aad(packetId: PacketId, senderId: PeerId, recipientId: PeerId, timestamp: Long): ByteArray {
        val w = ByteWriter(40)
        w.bytes(packetId.toBytes())
        w.bytes(senderId.toBytes())
        w.bytes(recipientId.toBytes())
        w.i64(timestamp)
        return w.toByteArray()
    }

    /** Encrypts [content] for [recipientId] and signs the packet. Null if the recipient key is unusable. */
    fun create(
        sender: Identity,
        recipientId: PeerId,
        recipientAgreementKey: ByteArray,
        content: DmContent,
        ttl: Int,
        packetId: PacketId,
        timestamp: Long,
        random: RandomSource,
    ): Packet? {
        require(!recipientId.isBroadcast)
        val aad = aad(packetId, sender.peerId, recipientId, timestamp)
        val sealed = DmCrypto.seal(content.encode(), recipientAgreementKey, aad, random) ?: return null
        val payload = Payload.Private(sealed.ephemeralKey, sealed.nonce, sealed.ciphertext)
        return PacketCodec.create(sender.signing, payload, recipientId, ttl, packetId, timestamp)
    }

    /** Decrypts a PRIVATE packet addressed to [me]. Null if it isn't for me or fails authentication. */
    fun open(packet: Packet, me: Identity): DmContent? {
        val payload = packet.payload as? Payload.Private ?: return null
        if (packet.recipientId != me.peerId) return null
        val aad = aad(packet.packetId, packet.senderId, packet.recipientId, packet.timestamp)
        val plain = DmCrypto.open(DmCrypto.Sealed(payload.ephemeralKey, payload.nonce, payload.ciphertext), me.agreement, aad)
            ?: return null
        return DmContent.decode(plain)
    }
}
