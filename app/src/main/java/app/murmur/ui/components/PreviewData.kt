package app.murmur.ui.components

import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer

/** Fake data for @Previews. */
object PreviewData {
    const val NOW: Long = 1_750_000_000_000L

    private fun peer(id: Long, name: String, emoji: String, color: Int, status: PeerStatus, rssi: Int?, hops: Int, verified: Boolean = false) = Peer(
        id = PeerId(id),
        nickname = name,
        emoji = emoji,
        colorIndex = color,
        status = status,
        hops = hops,
        rssi = rssi,
        lastSeen = NOW - 20_000,
        verified = verified,
        blocked = false,
        signingKey = ByteArray(32) { (it * 7 + id).toByte() },
    )

    val luna = peer(0x1111_2222_3333_4444, "Luna", "🌙", 0, PeerStatus.NEARBY, -52, 1, verified = true)
    val kai = peer(0x2222_3333_4444_5555, "Kai", "🦊", 7, PeerStatus.NEARBY, -71, 1)
    val mira = peer(0x3333_4444_5555_6666, "Mira", "🌸", 6, PeerStatus.NEARBY, -86, 1)
    val theo = peer(0x4444_5555_6666_7777, "Theo", "🐙", 4, PeerStatus.VIA_MESH, null, 3)
    val ines = peer(0x5555_6666_7777_0888, "Inès", "🛰️", 8, PeerStatus.OFFLINE, null, 0).copy(lastSeen = NOW - 3_600_000)

    val peers = listOf(luna, kai, mira, theo, ines)
    val me = peer(0x0F0F_0F0F_0F0F_0F0F, "Sam", "🐧", 1, PeerStatus.NEARBY, null, 0)
}
