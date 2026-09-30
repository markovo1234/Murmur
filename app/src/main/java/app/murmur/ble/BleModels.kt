package app.murmur.ble

import app.murmur.core.protocol.PeerId

enum class LinkRole { CLIENT, SERVER }

enum class AdapterState { UNSUPPORTED, OFF, TURNING_ON, ON, TURNING_OFF }

enum class AdvertiseState { OFF, STARTING, ON, UNSUPPORTED, FAILED }

enum class ScanState { OFF, ON, THROTTLED, FAILED }

data class LinkInfo(
    val id: String,
    val role: LinkRole,
    val address: String,
    val mtu: Int,
    val rssi: Int?,
    val peerId: PeerId?,
    val ready: Boolean,
    val queued: Int,
)

data class BleStatus(
    val running: Boolean = false,
    val adapter: AdapterState = AdapterState.OFF,
    val advertise: AdvertiseState = AdvertiseState.OFF,
    val advertiseError: String? = null,
    val scan: ScanState = ScanState.OFF,
    val scanError: String? = null,
    val scanOnly: Boolean = false,
    val missingPermissions: Boolean = false,
    val locationOff: Boolean = false,
    val links: List<LinkInfo> = emptyList(),
    val candidates: Int = 0,
)
