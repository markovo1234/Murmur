package app.murmur.core.mesh

import app.murmur.core.protocol.PeerId

/**
 * One transport connection to a neighbour (for BLE: one GATT connection). The mesh only ever sees
 * complete packets; fragmentation is the transport's business.
 */
interface Link {
    /** Unique for the lifetime of the process. */
    val id: String

    /** Queues one complete encoded packet. Returns false if the link can no longer send. */
    fun send(packet: ByteArray): Boolean

    /** Called once the mesh learns who is on the other end (first direct ANNOUNCE, ttl 7). */
    fun onPeerIdentified(peerId: PeerId) {}
}

/** What a transport reports to the mesh. Delivered in order through a single flow. */
sealed interface LinkEvent {
    /** The link is ready: both directions work. */
    class Up(val link: Link) : LinkEvent

    /** One complete packet arrived on [linkId]. */
    class Received(val linkId: String, val bytes: ByteArray) : LinkEvent

    class Down(val linkId: String) : LinkEvent
}
