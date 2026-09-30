package app.murmur.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Radio prerequisites, tracked for the whole process (the mesh may not be running). */
class SystemStatus(private val context: Context) {
    data class Radio(
        val bleSupported: Boolean,
        val bluetoothOn: Boolean,
        val locationOn: Boolean,
        val bluetoothPermissions: Boolean,
        val notificationPermission: Boolean,
    )

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Radio> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = refresh()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Call on resume: permissions can change in system settings without a broadcast. */
    fun refresh() {
        _state.value = read()
    }

    private fun read(): Radio {
        val adapter = manager?.adapter
        val on = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        return Radio(
            bleSupported = adapter != null && BlePermissions.hasBleHardware(context),
            bluetoothOn = on,
            locationOn = BlePermissions.isLocationEnabled(context),
            bluetoothPermissions = BlePermissions.hasBluetooth(context),
            notificationPermission = BlePermissions.hasNotifications(context),
        )
    }
}
