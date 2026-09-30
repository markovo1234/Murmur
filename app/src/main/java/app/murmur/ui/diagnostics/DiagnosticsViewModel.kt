package app.murmur.ui.diagnostics

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.ble.BleStatus
import app.murmur.core.Murmur
import app.murmur.core.mesh.MeshStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LinkRow(val id: String, val role: String, val mtu: Int, val rssi: Int?, val peer: String, val ready: Boolean, val queued: Int)

data class DiagnosticsUiState(
    val myPeerId: String = "…",
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val protocolVersion: Int = Murmur.PROTOCOL_VERSION,
    val bleSupported: Boolean = true,
    val meshRunning: Boolean = false,
    val ble: BleStatus? = null,
    val stats: MeshStats? = null,
    val links: List<LinkRow> = emptyList(),
    val log: List<String> = emptyList(),
    val demoMode: Boolean = false,
)

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    private val meshPart = combine(c.mesh.runtime, c.mesh.bleStatus, c.mesh.stats, c.peers.peers) { rt, ble, stats, peers ->
        val names = peers.associate { it.id to it.nickname }
        val links = ble?.links.orEmpty().map { l ->
            LinkRow(
                id = l.id,
                role = l.role.name.lowercase(),
                mtu = l.mtu,
                rssi = l.rssi,
                peer = l.peerId?.let { id -> names[id]?.let { "$it (${id.toHex()})" } ?: id.toHex() } ?: "unidentified",
                ready = l.ready,
                queued = l.queued,
            )
        }
        Triple(rt != null, ble, stats) to links
    }

    val state: StateFlow<DiagnosticsUiState> = combine(
        c.identity,
        meshPart,
        c.log.entries,
        c.settingsState.map { it?.demoMode ?: false },
    ) { identity, (mesh, links), log, demo ->
        DiagnosticsUiState(
            myPeerId = identity?.peerId?.toHex() ?: "…",
            bleSupported = c.bleSupported,
            meshRunning = mesh.first,
            ble = mesh.second,
            stats = mesh.third,
            links = links,
            log = log,
            demoMode = demo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun setDemoMode(on: Boolean) {
        viewModelScope.launch { c.settings.setDemoMode(on) }
    }

    fun startMesh() {
        viewModelScope.launch { c.startMeshIfReady() }
    }

    fun stopMesh() = c.mesh.stop()

    fun logText(): String = c.log.text()
}
