package app.murmur.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** The runtime permissions Murmur needs, per API level. */
object BlePermissions {
    /** Bluetooth permissions to request at runtime on this device. */
    val bluetooth: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // BLUETOOTH and BLUETOOTH_ADMIN are install-time permissions up to API 30.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** POST_NOTIFICATIONS on API 33+, otherwise null. */
    val notifications: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    /** Location services must be on for BLE scanning up to API 30. */
    val needsLocationServices: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasBluetooth(context: Context): Boolean = bluetooth.all { has(context, it) }

    fun hasNotifications(context: Context): Boolean = notifications?.let { has(context, it) } ?: true

    fun canScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) has(context, Manifest.permission.BLUETOOTH_SCAN)
        else has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun canAdvertise(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(context, Manifest.permission.BLUETOOTH_ADVERTISE)

    fun isLocationEnabled(context: Context): Boolean {
        if (!needsLocationServices) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            true
        }
    }

    fun hasBleHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}
