package app.murmur.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.murmur.ble.BlePermissions
import app.murmur.ble.BleStatus
import app.murmur.core.mesh.MeshStats
import app.murmur.core.mesh.PeerInfo
import app.murmur.core.protocol.PeerId
import app.murmur.diagnostics.DiagnosticsLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The app-wide handle on the (optional) running mesh. The service attaches/detaches its runtime here. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshController(private val context: Context, private val log: DiagnosticsLog) {
    private val _runtime = MutableStateFlow<MeshRuntime?>(null)
    val runtime: StateFlow<MeshRuntime?> = _runtime.asStateFlow()

    val bleStatus: Flow<BleStatus?> = _runtime.flatMapLatest { it?.transport?.status ?: flowOf(null) }
    val peers: Flow<Map<PeerId, PeerInfo>> = _runtime.flatMapLatest { it?.node?.peers ?: flowOf(emptyMap()) }
    val rssi: Flow<Map<PeerId, Int>> = _runtime.flatMapLatest { it?.transport?.rssiByPeer ?: flowOf(emptyMap()) }
    val stats: Flow<MeshStats?> = _runtime.flatMapLatest { it?.node?.stats ?: flowOf(null) }

    /** Starts the foreground service. Only call while the app is visible. */
    fun start() {
        if (!BlePermissions.hasBluetooth(context)) {
            log.log("SERVICE", "not starting: Bluetooth permissions missing")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java).setAction(MeshService.ACTION_START))
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException if we raced into the background.
            log.log("SERVICE", "start failed: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        context.stopService(Intent(context, MeshService::class.java))
    }

    fun setForeground(foreground: Boolean) {
        _runtime.value?.setForeground(foreground)
    }

    internal fun attach(runtime: MeshRuntime) {
        _runtime.value = runtime
    }

    internal fun detach(runtime: MeshRuntime) {
        _runtime.compareAndSet(runtime, null)
    }
}
