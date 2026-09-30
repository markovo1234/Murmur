package app.murmur.ui.onboarding

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.ble.BlePermissions
import app.murmur.ui.container

enum class PermState { GRANTED, NOT_REQUESTED, DENIED, PERMANENTLY_DENIED }

/** Everything the permission UI needs to render. */
data class PermissionSnapshot(
    val bleSupported: Boolean = true,
    val bluetooth: PermState = PermState.NOT_REQUESTED,
    /** Null below Android 13. */
    val notifications: PermState? = null,
    val bluetoothOn: Boolean = true,
    /** Only relevant up to Android 11. */
    val locationNeeded: Boolean = false,
    val locationOn: Boolean = true,
    /** Android 11 and below ask for location to scan. */
    val bluetoothIsLocation: Boolean = false,
)

@Stable
class PermissionActions(
    val snapshot: PermissionSnapshot,
    val requestBluetooth: () -> Unit,
    val requestNotifications: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openLocationSettings: () -> Unit,
    val openAppSettings: () -> Unit,
)

/** Launchers + live permission state (refreshed on every resume). */
@Composable
fun rememberPermissionActions(): PermissionActions {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val container = context.container
    var requested by rememberSaveable { mutableStateOf(setOf<String>()) }
    var tick by remember { mutableIntStateOf(0) }

    fun stateOf(perms: List<String>): PermState = when {
        perms.all { BlePermissions.has(context, it) } -> PermState.GRANTED
        perms.none { it in requested } -> PermState.NOT_REQUESTED
        activity != null && perms.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } -> PermState.PERMANENTLY_DENIED
        else -> PermState.DENIED
    }

    val refresh = {
        tick++
        container.system.refresh()
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    val system by container.system.state.collectAsStateWithLifecycle()
    val notifPerm = BlePermissions.notifications
    // Recomputed after every permission result / resume (tick) and radio change (system).
    val snapshot = remember(tick, system, requested) {
        PermissionSnapshot(
            bleSupported = container.bleSupported,
            bluetooth = stateOf(BlePermissions.bluetooth),
            notifications = notifPerm?.let { stateOf(listOf(it)) },
            bluetoothOn = system.bluetoothOn,
            locationNeeded = BlePermissions.needsLocationServices,
            locationOn = system.locationOn,
            bluetoothIsLocation = BlePermissions.needsLocationServices,
        )
    }

    return PermissionActions(
        snapshot = snapshot,
        requestBluetooth = {
            requested = requested + BlePermissions.bluetooth
            permissionLauncher.launch(BlePermissions.bluetooth.toTypedArray())
        },
        requestNotifications = {
            if (notifPerm != null) {
                requested = requested + notifPerm
                permissionLauncher.launch(arrayOf(notifPerm))
            }
        },
        enableBluetooth = { requestEnableBluetooth(context, activityLauncher::launch) },
        openLocationSettings = { activityLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        openAppSettings = {
            activityLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
    )
}

/** The permission to ask for enabling Bluetooth is checked here (lint can't see it through the launcher). */
@SuppressLint("MissingPermission")
private fun requestEnableBluetooth(context: android.content.Context, launch: (Intent) -> Unit) {
    if (!BlePermissions.canConnect(context)) return
    try {
        launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    } catch (_: Exception) {
        launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}
