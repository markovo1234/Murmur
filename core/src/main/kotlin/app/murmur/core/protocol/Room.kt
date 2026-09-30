package app.murmur.core.protocol

import app.murmur.core.Murmur

/** Channel names: 1–24 of a–z, 0–9, '-' and '_'. "nearby" is reserved for the default room. */
object Channels {
    const val MAX_LENGTH: Int = 24
    const val NEARBY: String = ""
    private val VALID = Regex("^[a-z0-9_-]{1,$MAX_LENGTH}$")

    fun isValid(name: String): Boolean = VALID.matches(name) && name != "nearby"

    /** "#Night Owls" → "night-owls"; null if nothing usable is left. */
    fun normalize(input: String): String? {
        val cleaned = input.trim().removePrefix("#").lowercase()
            .replace(Regex("\\s+"), "-")
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
            .take(MAX_LENGTH)
        return cleaned.takeIf { isValid(it) }
    }
}

/**
 * A channel invitation sent in an end-to-end encrypted DM (1.3): "1 <name> <64 hex key | ->". For a
 * password channel it carries the derived key (never the password), so the invitee can read it at once.
 */
object ChannelInvites {
    const val VERSION: Int = 1

    class Invite(val channel: String, val key: ByteArray?) {
        val locked: Boolean get() = key != null
    }

    fun body(channel: String, key: ByteArray?): String {
        require(Channels.isValid(channel)) { "bad channel" }
        require(key == null || key.size == KEY_SIZE) { "bad key" }
        return "$VERSION $channel ${key?.let(Bytes::toHex) ?: "-"}"
    }

    /** Null if malformed. Extra fields (later versions) are ignored. */
    fun parse(body: String): Invite? {
        val parts = body.trim().split(' ')
        if (parts.size < 3 || parts[0].toIntOrNull() != VERSION || !Channels.isValid(parts[1])) return null
        if (parts[2] == "-") return Invite(parts[1], null)
        val key = Bytes.fromHex(parts[2])?.takeIf { it.size == KEY_SIZE } ?: return null
        return Invite(parts[1], key)
    }

    private const val KEY_SIZE = 32
}

enum class RoomKind(val code: Int) {
    TEXT(1),

    /** [RoomContent.target] = the reacted-to message's packetId; body = emoji, or "" to remove. */
    REACTION(2),

    /** Delete for everyone: [RoomContent.target] = the sender's own message. */
    RETRACT(3),

    /** Emergency alert shown prominently to everyone in range. Body = optional short text. */
    SOS(4);

    companion object {
        fun fromCode(code: Int): RoomKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The (possibly encrypted) content of a ROOM packet.
 *
 * ```
 * u8        kind
 * u8 + n    sender nickname
 * 16        target packetId (zeros when unused)
 * u16 + n   body (UTF-8)
 * …         ignored (fields added by later versions)
 * ```
 */
class RoomContent(
    val kind: RoomKind,
    val nickname: String,
    val target: PacketId?,
    val body: String,
) {
    fun encode(): ByteArray {
        require(Murmur.isValidNickname(nickname)) { "invalid nickname" }
        require(Murmur.utf8Size(body) <= Murmur.MAX_TEXT_BYTES) { "body too long" }
        if (kind == RoomKind.TEXT) require(body.isNotEmpty()) { "empty text" }
        val w = ByteWriter(MIN_SIZE + nickname.length * 3 + body.length * 3)
        w.u8(kind.code)
        w.string8(nickname)
        w.bytes(target?.toBytes() ?: ByteArray(PacketId.SIZE))
        w.string16(body)
        return w.toByteArray()
    }

    override fun toString(): String = "RoomContent($kind from $nickname)"

    companion object {
        /** kind + empty nickname length + target + empty body length. */
        const val MIN_SIZE: Int = 1 + 1 + PacketId.SIZE + 2
        private val ZERO_ID = PacketId(0L, 0L)

        /** Null if malformed or of a kind this version doesn't know. Never throws. */
        fun decode(bytes: ByteArray): RoomContent? = try {
            val r = ByteReader(bytes)
            val kind = RoomKind.fromCode(r.u8())
            val nickname = r.string8(PacketCodec.MAX_NICKNAME_BYTES)
            val target = PacketId.fromBytes(r.bytes(PacketId.SIZE)).takeIf { it != ZERO_ID }
            val body = r.string16(Murmur.MAX_TEXT_BYTES)
            r.skipRest()
            when {
                kind == null -> null
                !Murmur.isValidNickname(nickname) -> null
                kind == RoomKind.TEXT && body.isEmpty() -> null
                (kind == RoomKind.REACTION || kind == RoomKind.RETRACT) && target == null -> null
                kind == RoomKind.REACTION && Murmur.utf8Size(body) > Murmur.MAX_EMOJI_BYTES -> null
                else -> RoomContent(kind, nickname, target, body)
            }
        } catch (_: DecodeException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
