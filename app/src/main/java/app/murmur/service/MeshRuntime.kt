package app.murmur.service

import app.murmur.AppContainer
import app.murmur.ble.BleTransport
import app.murmur.core.SecureRandomSource
import app.murmur.core.crypto.Identity
import app.murmur.core.mesh.MeshNode
import app.murmur.core.mesh.Profile
import app.murmur.core.protocol.PeerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One running mesh: BLE transport + MeshNode, wired to the repositories. Owned by [MeshService]. */
class MeshRuntime(private val c: AppContainer, identity: Identity, profile: Profile) {
    private val errors = CoroutineExceptionHandler { _, e -> c.log.log("MESH", "error: ${e.javaClass.simpleName} ${e.message}") }

    // MeshNode requires a single-threaded dispatcher.
    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + errors)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    /** Completes once [stop] has fully torn everything down. */
    val stopped = CompletableDeferred<Unit>()

    val transport = BleTransport(c.app, identity.peerId, c.clock, c.log)
    val node = MeshNode(
        identity = identity,
        profile = profile,
        scope = meshScope,
        clock = c.clock,
        random = SecureRandomSource(),
        log = { c.log.log("MESH", it) },
    )

    @OptIn(FlowPreview::class)
    fun start(foreground: Boolean) {
        // Subscribe before the node starts so no event is missed.
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            node.events.collect { c.chats.onMeshEvent(it) }
        }
        node.start(transport.linkEvents)
        transport.setForeground(foreground)
        transport.start()

        workScope.launch {
            node.setBackground(!foreground)
            c.chats.requeuePending(node)
        }
        workScope.launch {
            c.settings.settings.map { it.relay }.distinctUntilChanged().collect { node.setRelayEnabled(it) }
        }
        workScope.launch {
            c.settings.settings.mapNotNull { it.profile }.distinctUntilChanged().drop(1).collect {
                if (it.isValid) node.updateProfile(it)
            }
        }
        workScope.launch {
            c.db.peers().observeBlocked().collect { ids -> node.setBlocked(ids.mapNotNull(PeerId::fromHex).toSet()) }
        }
        workScope.launch {
            node.peers.sample(PERSIST_PEERS_MILLIS).collect { c.peers.remember(it.values) }
        }
    }

    fun setForeground(foreground: Boolean) {
        transport.setForeground(foreground)
        workScope.launch { node.setBackground(!foreground) }
    }

    /** Graceful shutdown: flood LEAVE, give it a moment to go out, then tear down. */
    suspend fun stop() {
        withTimeoutOrNull(1_000) { node.sendLeave() }
        delay(400)
        node.stop()
        transport.stop()
        meshScope.cancel()
        workScope.cancel()
        stopped.complete(Unit)
    }

    private companion object {
        const val PERSIST_PEERS_MILLIS = 5_000L
    }
}
