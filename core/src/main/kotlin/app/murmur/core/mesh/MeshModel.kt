package app.murmur.core.mesh

import app.murmur.core.Murmur
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.Packet
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.RoomKind

data class Profile(val nickname: String, val emoji: String, val colorIndex: Int) {
    val isValid: Boolean
        get() = Murmur.isValidNickname(nickname) && emoji.isNotEmpty() &&
            Murmur.utf8Size(emoji) <= Murmur.MAX_EMOJI_BYTES && colorIndex in 0 until Murmur.AVATAR_COLOR_COUNT
}

enum class PeerStatus { NEARBY, VIA_MESH, OFFLINE }

/** Snapshot of what the mesh knows about one peer. */
data class PeerInfo(
    val id: PeerId,
    val nickname: String?,
    val emoji: String?,
    val colorIndex: Int,
    /** Ed25519 public key, once any signed packet from this peer arrived. */
    val signingKey: ByteArray?,
    val agreementKey: ByteArray?,
    val lastHeard: Long,
    val hops: Int,
    val status: PeerStatus,
    val linkIds: Set<String>,
)

/** DM delivery state. Order matters: a receipt never moves a message backwards. */
enum class DeliveryStatus { PENDING, SENDING, SENT, FAILED, DELIVERED, READ }

sealed interface MeshEvent {
    data class PublicMessage(
        val packetId: PacketId,
        val senderId: PeerId,
        val nickname: String,
        val text: String,
        val timestamp: Long,
        val hops: Int,
    ) : MeshEvent

    /** A DM shown to the user. Emitted once per messageId, however many copies arrive. */
    data class DirectMessage(
        val messageId: MessageId,
        val senderId: PeerId,
        val body: String,
        val timestamp: Long,
        val hops: Int,
    ) : MeshEvent

    data class Delivery(val messageId: MessageId, val peerId: PeerId, val status: DeliveryStatus) : MeshEvent

    data class Typing(val peerId: PeerId) : MeshEvent

    data class LinkIdentified(val linkId: String, val peerId: PeerId) : MeshEvent

    /** A ROOM packet: #nearby extras ([channel] = "") or a channel message. */
    data class RoomMessage(
        val packetId: PacketId,
        val senderId: PeerId,
        val channel: String,
        val encrypted: Boolean,
        val kind: RoomKind,
        val nickname: String,
        val target: PacketId?,
        val body: String,
        val timestamp: Long,
        val hops: Int,
    ) : MeshEvent

    /** A DM reaction, retraction, wave or disappearing-messages timer. */
    data class DirectControl(
        val senderId: PeerId,
        val kind: DmKind,
        val messageId: MessageId,
        val body: String,
        val timestamp: Long,
    ) : MeshEvent
}

data class MeshStats(
    val sent: Long = 0,
    val received: Long = 0,
    val relayed: Long = 0,
    val droppedDuplicate: Long = 0,
    val droppedInvalid: Long = 0,
)

data class MeshConfig(
    val announceIntervalForeground: Long = 30_000,
    val announceIntervalBackground: Long = 60_000,
    val announceIntervalPowerSave: Long = 120_000,
    val offlineAfter: Long = 90_000,
    val ackTimeout: Long = 30_000,
    val maxResends: Int = 3,
    val pendingExpiry: Long = 24 * 60 * 60 * 1000L,
    val dedupeCapacity: Int = 10_000,
    val maxFutureSkew: Long = 60 * 60 * 1000L,
    val maxAge: Long = 12 * 60 * 60 * 1000L,
    val typingInterval: Long = 3_000,
    val statusTick: Long = 5_000,
)

enum class DropReason { DUPLICATE, MALFORMED, SENDER_KEY_MISMATCH, BAD_SIGNATURE, STALE_TIMESTAMP, UNDECRYPTABLE }

/** Hooks for diagnostics and tests. Called on the mesh thread. */
interface MeshTracer {
    fun onDelivered(packet: Packet) {}
    fun onRelayed(packet: Packet, linkCount: Int) {}
    fun onDropped(reason: DropReason, linkId: String, detail: String) {}

    object None : MeshTracer
}

/** Insertion-ordered set that forgets its oldest entries beyond [capacity]. */
internal class BoundedSet<T>(private val capacity: Int) {
    private val map = object : LinkedHashMap<T, Unit>(capacity + 16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Unit>?): Boolean = size > capacity
    }

    operator fun contains(value: T): Boolean = map.containsKey(value)

    /** Returns true if [value] was not present. */
    fun add(value: T): Boolean {
        if (map.containsKey(value)) return false
        map[value] = Unit
        return true
    }

    val size: Int get() = map.size
}
