package app.murmur.data

import app.murmur.core.mesh.PeerInfo
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.Bytes
import app.murmur.core.protocol.PeerId
import app.murmur.data.db.MurmurDatabase
import app.murmur.data.db.PeerEntity
import app.murmur.demo.DemoMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything the UI needs to know about one peer. */
data class Peer(
    val id: PeerId,
    val nickname: String,
    val emoji: String,
    val colorIndex: Int,
    val status: PeerStatus,
    val hops: Int,
    /** Smoothed scan RSSI; only for peers we can hear directly. */
    val rssi: Int?,
    val lastSeen: Long,
    val verified: Boolean,
    val blocked: Boolean,
    val signingKey: ByteArray?,
    val isDemo: Boolean = false,
) {
    val isOnline: Boolean get() = status != PeerStatus.OFFLINE

    override fun equals(other: Any?): Boolean = other is Peer && id == other.id && nickname == other.nickname &&
        emoji == other.emoji && colorIndex == other.colorIndex && status == other.status && hops == other.hops &&
        rssi == other.rssi && lastSeen == other.lastSeen && verified == other.verified && blocked == other.blocked &&
        isDemo == other.isDemo && (signingKey?.contentEquals(other.signingKey) ?: (other.signingKey == null))

    override fun hashCode(): Int = id.hashCode() * 31 + status.hashCode()
}

class PeerRepository(
    private val db: MurmurDatabase,
    meshPeers: Flow<Map<PeerId, PeerInfo>>,
    rssi: Flow<Map<PeerId, Int>>,
    demoPeers: Flow<List<Peer>>,
    scope: CoroutineScope,
) {
    /** All known peers: live mesh state merged with what's stored, plus demo peers. Strongest first. */
    val peers: StateFlow<List<Peer>> = combine(db.peers().observeAll(), meshPeers, rssi, demoPeers) { stored, live, rssiMap, demo ->
        merge(stored, live, rssiMap) + demo
    }.map { list -> list.sortedWith(PEER_ORDER) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Wired after construction; demo peers keep their flags in memory so they vanish with demo mode. */
    var demo: DemoMode? = null

    fun peer(id: PeerId): Flow<Peer?> = peers.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    suspend fun setBlocked(id: PeerId, blocked: Boolean) {
        demo?.takeIf { it.isDemoPeer(id) }?.let { return it.setBlocked(id, blocked) }
        ensureRow(id)
        db.peers().setBlocked(id.toHex(), blocked)
    }

    suspend fun setVerified(id: PeerId, verified: Boolean) {
        demo?.takeIf { it.isDemoPeer(id) }?.let { return it.setVerified(id, verified) }
        ensureRow(id)
        db.peers().setVerified(id.toHex(), verified)
    }

    /** Persists what the mesh learned (nickname, avatar, key, last seen). Keeps verified/blocked flags. */
    suspend fun remember(infos: Collection<PeerInfo>) {
        for (info in infos) {
            val existing = db.peers().get(info.id.toHex())
            val updated = PeerEntity(
                peerId = info.id.toHex(),
                nickname = info.nickname ?: existing?.nickname,
                emoji = info.emoji ?: existing?.emoji,
                colorIndex = if (info.emoji != null) info.colorIndex else existing?.colorIndex ?: 0,
                signingKey = info.signingKey?.let(Bytes::toHex) ?: existing?.signingKey,
                lastSeen = maxOf(info.lastHeard, existing?.lastSeen ?: 0L),
                verified = existing?.verified ?: false,
                blocked = existing?.blocked ?: false,
            )
            if (updated != existing) db.peers().upsert(updated)
        }
    }

    private suspend fun ensureRow(id: PeerId) {
        if (db.peers().get(id.toHex()) != null) return
        val live = peers.value.firstOrNull { it.id == id }
        db.peers().upsert(
            PeerEntity(
                peerId = id.toHex(),
                nickname = live?.nickname,
                emoji = live?.emoji,
                colorIndex = live?.colorIndex ?: 0,
                signingKey = live?.signingKey?.let(Bytes::toHex),
                lastSeen = live?.lastSeen ?: 0L,
            ),
        )
    }

    private fun merge(stored: List<PeerEntity>, live: Map<PeerId, PeerInfo>, rssi: Map<PeerId, Int>): List<Peer> {
        val byId = stored.associateBy { it.peerId }
        val result = ArrayList<Peer>(live.size + stored.size)
        for (info in live.values) {
            val s = byId[info.id.toHex()]
            result += Peer(
                id = info.id,
                nickname = info.nickname ?: s?.nickname ?: fallbackName(info.id),
                emoji = info.emoji ?: s?.emoji ?: DEFAULT_EMOJI,
                colorIndex = if (info.emoji != null) info.colorIndex else s?.colorIndex ?: 0,
                status = info.status,
                hops = if (info.status == PeerStatus.NEARBY) 1 else info.hops,
                rssi = if (info.status == PeerStatus.NEARBY) rssi[info.id] else null,
                lastSeen = info.lastHeard,
                verified = s?.verified ?: false,
                blocked = s?.blocked ?: false,
                signingKey = info.signingKey ?: s?.signingKey?.let(Bytes::fromHex),
            )
        }
        for (s in stored) {
            val id = PeerId.fromHex(s.peerId) ?: continue
            if (live.containsKey(id)) continue
            result += Peer(
                id = id,
                nickname = s.nickname ?: fallbackName(id),
                emoji = s.emoji ?: DEFAULT_EMOJI,
                colorIndex = s.colorIndex,
                status = PeerStatus.OFFLINE,
                hops = 0,
                rssi = null,
                lastSeen = s.lastSeen,
                verified = s.verified,
                blocked = s.blocked,
                signingKey = s.signingKey?.let(Bytes::fromHex),
            )
        }
        return result
    }

    companion object {
        const val DEFAULT_EMOJI = "🙂"

        fun fallbackName(id: PeerId): String = "Peer #${id.shortTag}"

        /** Nearby (strongest signal first), then via mesh (fewest hops), then offline (most recent). */
        val PEER_ORDER: Comparator<Peer> = compareBy<Peer> { it.status.ordinal }
            .thenByDescending { it.rssi ?: Int.MIN_VALUE }
            .thenBy { it.hops }
            .thenByDescending { it.lastSeen }
    }
}
