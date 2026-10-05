package app.murmur.core

import app.murmur.core.call.CallSignal
import app.murmur.core.crypto.CallCrypto
import app.murmur.core.crypto.ChannelCrypto
import app.murmur.core.crypto.DmCrypto
import app.murmur.core.crypto.Identity
import app.murmur.core.crypto.PinHasher
import app.murmur.core.crypto.SafetyNumber
import app.murmur.core.crypto.Sha256
import app.murmur.core.link.Fragmenter
import app.murmur.core.protocol.Bytes
import app.murmur.core.protocol.ChannelInvites
import app.murmur.core.protocol.Channels
import app.murmur.core.protocol.DmContent
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.Packet
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.Payload
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.PrivateMessages
import app.murmur.core.protocol.RoomContent
import app.murmur.core.protocol.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Golden vectors shared with the iPhone app (ios/MurmurCore): exact bytes produced by this protocol code,
 * which the Swift tests must reproduce or open. Fails when the committed file is out of date; regenerate
 * with `MURMUR_WRITE_VECTORS=1 ./gradlew :core:test --tests '*CrossPlatformVectorsTest*'`.
 */
class CrossPlatformVectorsTest {
    private val file = File("../ios/MurmurCore/Tests/MurmurCoreTests/Resources/vectors.json")

    @Test
    fun vectorsAreUpToDate() {
        val json = Json.write(vectors()) + "\n"
        if (System.getenv("MURMUR_WRITE_VECTORS") == "1") file.writeText(json)
        assertEquals(
            "ios/MurmurCore vectors.json is out of date: run MURMUR_WRITE_VECTORS=1 ./gradlew :core:test",
            json,
            if (file.exists()) file.readText() else "",
        )
    }

    /** Hands out seeded bytes and remembers them, so Swift can replay exactly the same "random" input. */
    private class Recording(private val inner: RandomSource) : RandomSource {
        val taken = ByteArrayOutputStream()
        override fun nextBytes(size: Int): ByteArray = inner.nextBytes(size).also { taken.write(it) }
    }

    private fun hex(b: ByteArray) = Bytes.toHex(b)

    private fun vectors(): Map<String, Any?> {
        val random = SeededRandomSource(2026)
        val alice = Identity.generate(random)
        val bob = Identity.generate(random)
        val time = 1_790_000_000_000L
        val out = LinkedHashMap<String, Any?>()

        out["identities"] = listOf(alice, bob).map {
            linkedMapOf(
                "signingSeed" to hex(it.signing.privateKey),
                "agreementPrivate" to hex(it.agreement.privateKey),
                "signingPublic" to hex(it.signing.publicKey),
                "agreementPublic" to hex(it.agreement.publicKey),
                "peerId" to it.peerId.toHex(),
            )
        }
        out["sha256"] = listOf("", "abc", "murmur 🌙").map { linkedMapOf("input" to hex(it.encodeToByteArray()), "output" to hex(Sha256.hash(it.encodeToByteArray()))) }
        out["x25519Shared"] = hex(alice.agreement.agree(bob.agreement.publicKey)!!)

        // DM encryption with replayable randomness (32 bytes ephemeral key, then the 12-byte nonce).
        val dmRandom = Recording(random)
        val dmAad = ByteArray(40) { it.toByte() }
        val dmPlain = DmContent(DmKind.TEXT, MessageId.random(random), alice.agreement.publicKey, "psst 🤫").encode()
        val sealed = DmCrypto.seal(dmPlain, bob.agreement.publicKey, dmAad, dmRandom)!!
        out["dmSeal"] = linkedMapOf(
            "random" to hex(dmRandom.taken.toByteArray()),
            "plaintext" to hex(dmPlain),
            "recipientAgreementPublic" to hex(bob.agreement.publicKey),
            "aad" to hex(dmAad),
            "ephemeralKey" to hex(sealed.ephemeralKey),
            "nonce" to hex(sealed.nonce),
            "ciphertext" to hex(sealed.ciphertext),
        )

        val channelKey = ChannelCrypto.deriveKey("night-owls", "hunter2")
        out["channelKey"] = linkedMapOf("channel" to "night-owls", "password" to "hunter2", "iterations" to ChannelCrypto.DEFAULT_ITERATIONS, "key" to hex(channelKey))

        val chRandom = Recording(random)
        val chAad = ChannelCrypto.aad(PacketId.random(random).toBytes(), alice.peerId.toBytes(), time, "night-owls")
        val chPlain = RoomContent(RoomKind.TEXT, "Alice", null, "owls only").encode()
        val chSealed = ChannelCrypto.seal(channelKey, chPlain, chAad, chRandom)
        out["channelSeal"] = linkedMapOf(
            "key" to hex(channelKey),
            "random" to hex(chRandom.taken.toByteArray()),
            "aad" to hex(chAad),
            "plaintext" to hex(chPlain),
            "sealed" to hex(chSealed),
        )

        val callKey = CallCrypto.newKey(random)
        val callId = MessageId.random(random)
        out["callSeal"] = listOf(true, false).map { fromCaller ->
            val sender = if (fromCaller) alice.peerId else bob.peerId
            val recipient = if (fromCaller) bob.peerId else alice.peerId
            val aad = CallCrypto.aad(callId.toBytes(), sender, recipient, 7)
            val plain = "audio".encodeToByteArray()
            linkedMapOf(
                "key" to hex(callKey),
                "fromCaller" to fromCaller,
                "seq" to 7,
                "aad" to hex(aad),
                "plaintext" to hex(plain),
                "sealed" to hex(CallCrypto.seal(callKey, fromCaller, 7, aad, plain)),
            )
        }
        out["callOffer"] = linkedMapOf("key" to hex(callKey), "body" to CallSignal.offerBody(callKey))

        val packets = ArrayList<Map<String, Any?>>()
        fun add(name: String, p: Packet, extra: Map<String, Any?> = emptyMap()) {
            val raw = p.encode()
            packets += linkedMapOf<String, Any?>(
                "name" to name,
                "hex" to hex(raw),
                "unsigned" to hex(raw.copyOf(raw.size - PacketCodec.SIGNATURE_SIZE)),
                "type" to p.payload.typeCode,
                "ttl" to p.ttl,
                "packetId" to p.packetId.toHex(),
                "senderId" to p.senderId.toHex(),
                "recipientId" to p.recipientId.toHex(),
                "timestamp" to p.timestamp,
            ) + extra
        }
        add(
            "announce",
            PacketCodec.create(alice.signing, Payload.Announce("Alice", "🌙", 3, alice.agreement.publicKey), PeerId.BROADCAST, 7, PacketId.random(random), time),
            mapOf("nickname" to "Alice", "emoji" to "🌙", "colorIndex" to 3),
        )
        add(
            "public",
            PacketCodec.create(alice.signing, Payload.Public("Alice", "hello @Bob 👋"), PeerId.BROADCAST, 5, PacketId.random(random), time + 1),
            mapOf("nickname" to "Alice", "text" to "hello @Bob 👋"),
        )
        add("leave", PacketCodec.create(alice.signing, Payload.Leave, PeerId.BROADCAST, 7, PacketId.random(random), time + 2))
        val target = PacketId.random(random)
        add(
            "roomReaction",
            PacketCodec.create(
                alice.signing,
                Payload.Room("", false, RoomContent(RoomKind.REACTION, "Alice", target, "❤️").encode()),
                PeerId.BROADCAST, 7, PacketId.random(random), time + 3,
            ),
            mapOf("channel" to "", "kind" to RoomKind.REACTION.code, "target" to target.toHex(), "body" to "❤️"),
        )
        val roomId = PacketId.random(random)
        val roomPlain = RoomContent(RoomKind.TEXT, "Alice", null, "owls only").encode()
        val roomSealed = ChannelCrypto.seal(channelKey, roomPlain, ChannelCrypto.aad(roomId.toBytes(), alice.peerId.toBytes(), time + 4, "night-owls"), random)
        add(
            "roomEncrypted",
            PacketCodec.create(alice.signing, Payload.Room("night-owls", true, roomSealed), PeerId.BROADCAST, 7, roomId, time + 4),
            mapOf("channel" to "night-owls", "kind" to RoomKind.TEXT.code, "body" to "owls only"),
        )
        val dmId = MessageId.random(random)
        add(
            "private",
            PrivateMessages.create(
                alice, bob.peerId, bob.agreement.publicKey,
                DmContent(DmKind.TEXT, dmId, alice.agreement.publicKey, "psst 🤫"),
                7, PacketId.random(random), time + 5, random,
            )!!,
            mapOf("messageId" to dmId.toHex(), "dmKind" to DmKind.TEXT.code, "body" to "psst 🤫"),
        )
        add(
            "call",
            PacketCodec.create(alice.signing, Payload.Call(callId, 42, ByteArray(40) { (it * 3).toByte() }), bob.peerId, 3, PacketId.random(random), time + 6),
            mapOf("callId" to callId.toHex(), "seq" to 42),
        )
        add("unknownType", PacketCodec.create(alice.signing, Payload.Unknown(9, byteArrayOf(1, 2, 3)), PeerId.BROADCAST, 7, PacketId.random(random), time + 7))
        out["packets"] = packets

        out["dmContent"] = listOf(
            DmContent(DmKind.TEXT, MessageId.random(random), alice.agreement.publicKey, "hi"),
            DmContent(DmKind.CHANNEL_INVITE, MessageId.random(random), alice.agreement.publicKey, ChannelInvites.body("night-owls", channelKey)),
            DmContent(DmKind.READ, MessageId.random(random), bob.agreement.publicKey, ""),
        ).map { linkedMapOf("kind" to it.kind.code, "messageId" to it.messageId.toHex(), "key" to hex(it.senderAgreementKey), "body" to it.body, "hex" to hex(it.encode())) }

        out["roomContent"] = listOf(
            RoomContent(RoomKind.TEXT, "Bob", null, "> Alice: hello\nhi!"),
            RoomContent(RoomKind.REACTION, "Bob", target, "👍"),
            RoomContent(RoomKind.SOS, "Bob", null, ""),
        ).map { linkedMapOf("kind" to it.kind.code, "nickname" to it.nickname, "target" to it.target?.toHex(), "body" to it.body, "hex" to hex(it.encode())) }

        val data = random.nextBytes(600)
        out["fragments"] = linkedMapOf(
            "data" to hex(data),
            "chunkSize" to 185,
            "streamId" to 0x7FFFFFF0,
            "fragments" to Fragmenter.fragment(data, 185, 0x7FFFFFF0).map(::hex),
        )

        out["safetyNumber"] = linkedMapOf(
            "keyA" to hex(alice.signing.publicKey),
            "keyB" to hex(bob.signing.publicKey),
            "digits" to SafetyNumber.digits(alice.signing.publicKey, bob.signing.publicKey),
        )
        out["invite"] = linkedMapOf("channel" to "night-owls", "key" to hex(channelKey), "body" to ChannelInvites.body("night-owls", channelKey))

        val salt = random.nextBytes(PinHasher.SALT_SIZE)
        out["pin"] = linkedMapOf("pin" to "2468", "salt" to hex(salt), "iterations" to PinHasher.ITERATIONS, "hash" to hex(PinHasher.hash("2468", salt)))

        out["nicknames"] = listOf("Alice", "🌙 Luna", " Alice", "Alice ", "", "a".repeat(20), "a".repeat(21), "Al\u0007ice", "Ünïcødé", " x")
            .map { linkedMapOf("nickname" to it, "valid" to Murmur.isValidNickname(it)) }
        out["channelNames"] = listOf("#Night Owls", "nearby", "  ##x", "Über Café", "a\tb  c", "UPPER_case-1", "x".repeat(30))
            .map { linkedMapOf("input" to it, "normalized" to Channels.normalize(it)) }
        return out
    }

    /** Just enough JSON for maps, lists, strings, numbers, booleans and nulls (keys keep their order). */
    private object Json {
        fun write(value: Any?, indent: String = ""): String = when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean, is Int, is Long -> value.toString()
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
                "$indent  ${quote(k as String)}: ${write(v, "$indent  ")}"
            }
            is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${write(it, "$indent  ")}" }
            else -> error("unsupported ${value.javaClass}")
        }

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\t' -> append("\\t")
                    c < ' ' || c in '\u007F'..' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
