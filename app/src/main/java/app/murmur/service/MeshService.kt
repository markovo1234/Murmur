package app.murmur.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.murmur.MurmurApp
import app.murmur.ble.BlePermissions
import app.murmur.core.mesh.PeerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type connectedDevice) that owns the BLE transport and the MeshNode.
 * Notification: "Murmur is active · N nearby" with a Stop action.
 */
class MeshService : LifecycleService() {
    private var runtime: MeshRuntime? = null
    private var inCall = false
    private var lastNearby = 0
    private val container get() = (application as MurmurApp).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            container.log.log("SERVICE", "stop requested from notification")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CALL_STARTED || intent?.action == ACTION_CALL_ENDED) {
            inCall = intent.action == ACTION_CALL_STARTED
            if (runtime != null) goForeground() else stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runtime == null) startMesh()
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        if (!BlePermissions.hasBluetooth(this)) {
            container.log.log("SERVICE", "Bluetooth permissions missing; not starting")
            return false
        }
        return try {
            var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            if (inCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && container.calls.hasMicPermission()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, Notifier.SERVICE_ID, container.notifier.serviceNotification(lastNearby), type)
            true
        } catch (e: Exception) {
            container.log.log("SERVICE", "startForeground failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun startMesh() {
        val c = container
        lifecycleScope.launch {
            val identity = withContext(Dispatchers.IO) { c.identityStore.loadOrCreate() }
            val profile = c.settings.current().profile
            if (profile == null || !profile.isValid) {
                c.log.log("SERVICE", "no profile yet; stopping")
                stopSelf()
                return@launch
            }
            if (runtime != null) return@launch
            val rt = MeshRuntime(c, identity, profile)
            runtime = rt
            rt.start(foreground = c.isAppInForeground)
            c.mesh.attach(rt)
            c.log.log("SERVICE", "mesh running as ${identity.peerId}")
            combine(rt.node.peers, c.peers.peers) { live, all ->
                val blocked = all.filter { it.blocked }.map { it.id }.toSet()
                live.values.count { it.status == PeerStatus.NEARBY && it.id !in blocked }
            }.distinctUntilChanged().collect {
                lastNearby = it
                c.notifier.updateService(it)
            }
        }
    }

    override fun onDestroy() {
        val rt = runtime
        runtime = null
        if (rt != null) {
            container.calls.onMeshStopped()
            container.mesh.detach(rt)
            container.appScope.launch { rt.stop() }
        }
        container.log.log("SERVICE", "service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.murmur.action.START"
        const val ACTION_STOP = "app.murmur.action.STOP"
        const val ACTION_CALL_STARTED = "app.murmur.action.CALL_STARTED"
        const val ACTION_CALL_ENDED = "app.murmur.action.CALL_ENDED"
    }
}
