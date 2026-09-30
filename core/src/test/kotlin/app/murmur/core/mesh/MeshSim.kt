package app.murmur.core.mesh

import app.murmur.core.BASE_TIME
import app.murmur.core.Clock
import app.murmur.core.SeededRandomSource
import app.murmur.core.crypto.Identity
import app.murmur.core.protocol.DecodeResult
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.Packet
import app.murmur.core.protocol.PacketCodec
import app.murmur.core.protocol.PacketId
import app.murmur.core.protocol.PacketType
import app.murmur.core.protocol.PeerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Wire bytes as they left a node (before any interceptor). */
class WireRecord(val from: String, val to: String, val bytes: ByteArray) {
    val packet: Packet? get() = (PacketCodec.decode(bytes) as? DecodeResult.Ok)?.packet
}

/** An in-memory network of point-to-point [Link]s. Delivery is asynchronous (through each node's channel). */
class InMemoryNetwork {
    /** Transforms or drops (returns null) bytes on the wire. */
    var interceptor: (from: String, to: String, bytes: ByteArray) -> ByteArray? = { _, _, b -> b }
    val wire = mutableListOf<WireRecord>()

    inner class Endpoint(val name: String) {
        internal val channel = Channel<LinkEvent>(Channel.UNLIMITED)
        val events: Flow<LinkEvent> get() = channel.receiveAsFlow()
    }

    private inner class MemLink(
        override val id: String,
        val from: Endpoint,
        val to: Endpoint,
        val remoteId: String,
    ) : Link {
        var open = true

        override fun send(packet: ByteArray): Boolean {
            if (!open) return false
            val copy = packet.copyOf()
            wire += WireRecord(from.name, to.name, copy)
            val out = interceptor(from.name, to.name, copy) ?: return true
            to.channel.trySend(LinkEvent.Received(remoteId, out))
            return true
        }
    }

    private val active = HashMap<Set<String>, Pair<MemLink, MemLink>>()
    private var counter = 0

    fun connect(a: Endpoint, b: Endpoint) {
        val n = counter++
        val idA = "${a.name}>${b.name}#$n"
        val idB = "${b.name}>${a.name}#$n"
        val la = MemLink(idA, a, b, idB)
        val lb = MemLink(idB, b, a, idA)
        active[setOf(a.name, b.name)] = la to lb
        a.channel.trySend(LinkEvent.Up(la))
        b.channel.trySend(LinkEvent.Up(lb))
    }

    /** Sends raw [bytes] over the a→b link, as if [a]'s transport produced them. */
    fun inject(a: Endpoint, b: Endpoint, bytes: ByteArray) {
        val (la, lb) = active[setOf(a.name, b.name)] ?: return
        val link = if (la.from === a) la else lb
        link.send(bytes)
    }

    fun disconnect(a: Endpoint, b: Endpoint) {
        val (la, lb) = active.remove(setOf(a.name, b.name)) ?: return
        la.open = false
        lb.open = false
        a.channel.trySend(LinkEvent.Down(la.id))
        b.channel.trySend(LinkEvent.Down(lb.id))
    }
}

class SimNode(val name: String, val identity: Identity, val endpoint: InMemoryNetwork.Endpoint) {
    lateinit var mesh: MeshNode
    val id: PeerId get() = identity.peerId
    val events = mutableListOf<MeshEvent>()
    val relayed = HashMap<PacketId, Int>()
    val dropped = mutableListOf<DropReason>()

    fun publicMessages(packetId: PacketId) = events.filterIsInstance<MeshEvent.PublicMessage>().filter { it.packetId == packetId }
    fun directMessages() = events.filterIsInstance<MeshEvent.DirectMessage>()
    fun deliveries(messageId: MessageId) =
        events.filterIsInstance<MeshEvent.Delivery>().filter { it.messageId == messageId }.map { it.status }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MeshSim(private val test: TestScope) {
    val clock = Clock { BASE_TIME + test.testScheduler.currentTime }
    val net = InMemoryNetwork()
    val nodes = LinkedHashMap<String, SimNode>()
    private var seed = 1000L

    operator fun get(name: String): SimNode = nodes.getValue(name)

    fun add(vararg names: String) = names.forEach { add(it) }

    fun add(name: String): SimNode {
        val random = SeededRandomSource(seed++)
        val sim = SimNode(name, Identity.generate(random), net.Endpoint(name))
        val tracer = object : MeshTracer {
            override fun onRelayed(packet: Packet, linkCount: Int) {
                sim.relayed.merge(packet.packetId, 1, Int::plus)
            }

            override fun onDropped(reason: DropReason, linkId: String, detail: String) {
                sim.dropped += reason
            }
        }
        sim.mesh = MeshNode(
            identity = sim.identity,
            profile = Profile(name, "🙂", 0),
            scope = test.backgroundScope,
            clock = clock,
            random = random,
            tracer = tracer,
        )
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            sim.mesh.events.collect { sim.events += it }
        }
        sim.mesh.start(sim.endpoint.events)
        nodes[name] = sim
        return sim
    }

    fun connect(a: String, b: String) = net.connect(this[a].endpoint, this[b].endpoint)
    fun disconnect(a: String, b: String) = net.disconnect(this[a].endpoint, this[b].endpoint)
    fun inject(from: String, to: String, bytes: ByteArray) = net.inject(this[from].endpoint, this[to].endpoint, bytes)

    /** PRIVATE packets on the wire from [sender] to [recipient], one per packetId. */
    fun privatePackets(sender: PeerId, recipient: PeerId): List<Packet> = net.wire
        .mapNotNull { it.packet }
        .filter { it.type == PacketType.PRIVATE && it.senderId == sender && it.recipientId == recipient }
        .distinctBy { it.packetId }
}
