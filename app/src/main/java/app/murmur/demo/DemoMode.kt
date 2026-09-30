package app.murmur.demo

import app.murmur.core.text.Replies
import app.murmur.core.Clock
import app.murmur.core.SecureRandomSource
import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.Identity
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.PeerId
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.RoomKind
import app.murmur.data.ChatRepository
import app.murmur.data.DemoGateway
import app.murmur.data.Peer
import app.murmur.data.db.MurmurDatabase
import app.murmur.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Five fake peers that drift on the radar, chat in #nearby and answer DMs (typing, ticks, scripted
 * replies). Feeds the repositories directly; never touches MeshNode or BLE. Switching it off removes
 * the peers and everything they said.
 */
class DemoMode(
    private val db: MurmurDatabase,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
) : DemoGateway {
    lateinit var chats: ChatRepository

    private class Script(
        val nickname: String,
        val emoji: String,
        val color: Int,
        val seed: Long,
        val via: Int?, // null = direct; otherwise hops
        val baseRssi: Int,
        val replies: List<String>,
        val nearbyLines: List<String>,
    )

    private val scripts = listOf(
        Script(
            "Luna", "🌙", 0, 11, null, -52,
            listOf("Hey! Got your message 👋", "Ha, same here.", "Want to meet by the fountain?", "Signal's great over here.", "🙌"),
            listOf("Anyone else at the station?", "The sunset tonight 🌅", "Battery at 12%, running on hope"),
        ),
        Script(
            "Kai", "🦊", 7, 22, null, -68,
            listOf("Yo!", "Loud and clear.", "Brb, grabbing coffee ☕", "No internet needed, wild right?"),
            listOf("Testing, testing… is this thing on?", "Free wifi? Who needs it.", "Found a great bench by the river"),
        ),
        Script(
            "Mira", "🌸", 6, 33, null, -84,
            listOf("Hi there 🌸", "I'm near the back, signal is a bit weak.", "Can you hear me now?", "Talk soon!"),
            listOf("Hello from the far side of the room", "Is the concert starting?"),
        ),
        Script(
            "Theo", "🐙", 4, 44, 2, -90,
            listOf("Relayed through two phones, how cool is that", "Message received via the mesh 🐙", "I'm on the other floor"),
            listOf("Hi from two hops away!", "Whoever is relaying: thank you 🙏"),
        ),
        Script(
            "Inès", "🛰️", 8, 55, 3, -95,
            listOf("Three hops and still here 🛰️", "The mesh is holding up!", "Speak soon, going out of range"),
            listOf("Checking in from the parking lot", "Mesh status: surprisingly solid"),
        ),
    )

    private class DemoPeer(val script: Script, val identity: Identity) {
        var rssi = script.baseRssi.toDouble()
        var hops = script.via ?: 1
        var blocked = false
        var verified = false
        var replyIndex = 0
        var nearbyIndex = 0
        var favorite = false
        var alias: String? = null
    }

    private var demoPeers: List<DemoPeer> = emptyList()
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private var jobs = mutableListOf<Job>()
    private val random = Random(7)

    @Volatile
    override var isEnabled: Boolean = false
        private set

    override fun isDemoPeer(peer: PeerId): Boolean = demoPeers.any { it.identity.peerId == peer }

    private var startedAt = 0L

    fun start() {
        if (isEnabled) return
        isEnabled = true
        startedAt = clock.now()
        demoPeers = scripts.map { DemoPeer(it, Identity.generate(SeededRandomSource(it.seed))) }
        log.log("DEMO", "demo mode on: ${demoPeers.size} fake peers")
        publish()
        jobs += scope.launch {
            while (isActive) {
                delay(1_500)
                drift()
            }
        }
        jobs += scope.launch {
            delay(4_000)
            while (isActive) {
                postNearby(demoPeers.filter { !it.blocked }.randomOrNull(random) ?: continue)
                delay(random.nextLong(20_000, 40_000))
            }
        }
        jobs += scope.launch {
            // A demo channel so channels can be tried on one phone too.
            chats.joinChannel(DEMO_CHANNEL, null)
            delay(9_000)
            var i = 0
            while (isActive) {
                val p = demoPeers.filter { !it.blocked }.randomOrNull(random)
                if (p != null) {
                    chats.receiveRoom(
                        PacketId.random(SecureRandomSource()).toHex(), p.identity.peerId, DEMO_CHANNEL, false,
                        RoomKind.TEXT, p.script.nickname, null, CHANNEL_LINES[i++ % CHANNEL_LINES.size], clock.now(), p.hops,
                    )
                }
                delay(random.nextLong(30_000, 60_000))
            }
        }
    }

    fun stop() {
        if (!isEnabled && demoPeers.isEmpty()) return
        isEnabled = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        val ids = demoPeers.map { it.identity.peerId.toHex() }
        demoPeers = emptyList()
        _peers.value = emptyList()
        scope.launch {
            // Fully remove everything the demo created.
            for (id in ids) chats.deleteConversation(id)
            db.messages().deleteFromSenders(ids)
            chats.leaveChannel(DEMO_CHANNEL)
            log.log("DEMO", "demo mode off: removed demo peers and their messages")
        }
    }

    fun setBlocked(peer: PeerId, blocked: Boolean) {
        demoPeers.firstOrNull { it.identity.peerId == peer }?.blocked = blocked
        publish()
    }

    fun setVerified(peer: PeerId, verified: Boolean) {
        demoPeers.firstOrNull { it.identity.peerId == peer }?.verified = verified
        publish()
    }

    fun setFavorite(peer: PeerId, favorite: Boolean) {
        demoPeers.firstOrNull { it.identity.peerId == peer }?.favorite = favorite
        publish()
    }

    fun setAlias(peer: PeerId, alias: String?) {
        demoPeers.firstOrNull { it.identity.peerId == peer }?.alias = alias
        publish()
    }

    override fun onOutgoingChannel(channel: String, body: String) {
        if (!isEnabled || channel != DEMO_CHANNEL || random.nextInt(100) >= 70) return
        val p = demoPeers.filter { !it.blocked }.randomOrNull(random) ?: return
        scope.launch {
            delay(random.nextLong(2_000, 5_000))
            if (isEnabled) {
                chats.receiveRoom(
                    PacketId.random(SecureRandomSource()).toHex(), p.identity.peerId, DEMO_CHANNEL, false,
                    RoomKind.TEXT, p.script.nickname, null, listOf("Welcome!", "👋", "Same here", "Nice one").random(random), clock.now(), p.hops,
                )
            }
        }
    }

    override fun onWave(peer: PeerId) {
        val p = demoPeers.firstOrNull { it.identity.peerId == peer } ?: return
        scope.launch {
            delay(2_000)
            if (isEnabled && !p.blocked) chats.receiveDirectControl(peer, DmKind.WAVE, MessageId.random(SecureRandomSource()).toHex(), "")
        }
    }

    override fun onOutgoingDirect(peer: PeerId, messageIdHex: String, body: String) {
        val p = demoPeers.firstOrNull { it.identity.peerId == peer } ?: return
        scope.launch {
            delay(350)
            chats.updateDelivery(messageIdHex, peer, DeliveryStatus.SENDING)
            delay(450)
            chats.updateDelivery(messageIdHex, peer, DeliveryStatus.SENT)
            delay(700L + 300L * p.hops)
            chats.updateDelivery(messageIdHex, peer, DeliveryStatus.DELIVERED)
            if (p.blocked) return@launch
            delay(1_200)
            chats.updateDelivery(messageIdHex, peer, DeliveryStatus.READ)
            delay(400)
            repeat(2) {
                chats.receiveTyping(peer)
                delay(1_400)
            }
            if (random.nextInt(100) < 40) {
                chats.receiveDirectControl(peer, DmKind.REACTION, messageIdHex, listOf("❤️", "👍", "😂").random(random))
            }
            val line = p.script.replies[p.replyIndex % p.script.replies.size]
            // Every third reply quotes your message, like a real reply.
            val reply = if (p.replyIndex % 3 == 2) Replies.compose("You", body, line) else line
            p.replyIndex++
            if (isEnabled) {
                chats.receiveDirect(MessageId.random(SecureRandomSource()).toHex(), peer, p.script.nickname, reply, clock.now(), p.hops)
            }
        }
    }

    override fun onOutgoingNearby(body: String) {
        if (!isEnabled || random.nextInt(100) >= 60) return
        val p = demoPeers.filter { !it.blocked }.randomOrNull(random) ?: return
        scope.launch {
            delay(random.nextLong(2_000, 5_000))
            if (isEnabled) {
                val answers = listOf("👋", "Hey!", "Same!", "Ha, nice", "Heard you loud and clear", "+1")
                chats.receivePublic(PacketId.random(SecureRandomSource()).toHex(), p.identity.peerId, p.script.nickname, answers.random(random), clock.now(), p.hops)
            }
        }
    }

    private suspend fun postNearby(p: DemoPeer) {
        val line = p.script.nearbyLines[p.nearbyIndex % p.script.nearbyLines.size]
        p.nearbyIndex++
        chats.receivePublic(PacketId.random(SecureRandomSource()).toHex(), p.identity.peerId, p.script.nickname, line, clock.now(), p.hops)
    }

    private fun drift() {
        for (p in demoPeers) {
            val base = p.script.baseRssi
            val next = p.rssi + random.nextDouble(-4.0, 4.0) + (base - p.rssi) * 0.15
            p.rssi = next.coerceIn(base - 12.0, base + 12.0).coerceIn(-100.0, -35.0)
            if (p.script.via != null && random.nextInt(30) == 0) {
                p.hops = (p.script.via + random.nextInt(-1, 2)).coerceIn(2, 4)
            }
        }
        publish()
    }

    private fun publish() {
        val now = clock.now()
        _peers.value = demoPeers.map { p ->
            Peer(
                id = p.identity.peerId,
                nickname = p.script.nickname,
                emoji = p.script.emoji,
                colorIndex = p.script.color,
                status = if (p.script.via == null) PeerStatus.NEARBY else PeerStatus.VIA_MESH,
                hops = p.hops,
                rssi = if (p.script.via == null) p.rssi.toInt() else null,
                lastSeen = now,
                verified = p.verified,
                blocked = p.blocked,
                signingKey = p.identity.signing.publicKey,
                isDemo = true,
                favorite = p.favorite,
                alias = p.alias,
                firstSeen = startedAt,
            )
        }
    }

    private companion object {
        const val DEMO_CHANNEL = "demo-lounge"
        val CHANNEL_LINES = listOf(
            "Welcome to #demo-lounge, a pretend channel 🎪",
            "Channels are like #nearby, but only for people who joined",
            "Give a channel a password and only people who know it can read it",
            "Long-press any message to react or reply",
        )
    }
}
