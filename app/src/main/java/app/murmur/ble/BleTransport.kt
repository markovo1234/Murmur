package app.murmur.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import app.murmur.core.Clock
import app.murmur.core.Murmur
import app.murmur.core.link.Fragmenter
import app.murmur.core.link.Reassembler
import app.murmur.core.mesh.Link
import app.murmur.core.mesh.LinkEvent
import app.murmur.core.protocol.PeerId
import app.murmur.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * BLE transport: one GATT server (the Murmur service), advertising, one long-running scan, and outbound
 * GATT client connections. Every GATT connection that becomes ready is one [Link].
 *
 * Threading: ALL state lives on one HandlerThread ([scope]). Bluetooth callbacks copy their arguments
 * and hop onto it. [Link.send] and [Link.onPeerIdentified] may be called from any thread.
 *
 * Permissions: every Bluetooth call below is preceded by a runtime permission check
 * ([BlePermissions]) and wrapped in try/catch for SecurityException, which is why MissingPermission
 * lint is suppressed for this class.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val myPeerId: PeerId,
    private val clock: Clock,
    private val log: DiagnosticsLog,
) {
    private val thread = HandlerThread("murmur-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(
        SupervisorJob() + handler.asCoroutineDispatcher("murmur-ble") +
            CoroutineExceptionHandler { _, e -> log("unexpected ${e.javaClass.simpleName}: ${e.message}") },
    )

    private val events = Channel<LinkEvent>(Channel.UNLIMITED)

    /** Consumed by exactly one MeshNode. */
    val linkEvents: Flow<LinkEvent> = events.receiveAsFlow()

    private val _status = MutableStateFlow(BleStatus())
    val status: StateFlow<BleStatus> = _status.asStateFlow()

    private val _rssiByPeer = MutableStateFlow<Map<PeerId, Int>>(emptyMap())

    /** Smoothed scan RSSI of peers seen advertising recently. */
    val rssiByPeer: StateFlow<Map<PeerId, Int>> = _rssiByPeer.asStateFlow()

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var running = false
    private var radioUp = false
    private var foreground = true
    private var receiverRegistered = false

    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var serviceAdded = false

    private var advertiseState = AdvertiseState.OFF
    private var advertiseError: String? = null
    private var advertiseRestartJob: Job? = null
    private var advertiseRetryJob: Job? = null

    private var scanState = ScanState.OFF
    private var scanError: String? = null
    private val scanStarts = ArrayDeque<Long>()
    private var scanRestartJob: Job? = null
    private var scanRetryJob: Job? = null

    private var loopJob: Job? = null

    private class Candidate(val address: String, var device: BluetoothDevice, var waitStart: Long) {
        var peerId: PeerId? = null
        var rssi: Double = -100.0
        var samples = 0
        var lastSeen = 0L
    }

    private val candidates = HashMap<String, Candidate>()
    private val clients = HashMap<String, ClientConnection>()
    private val servers = HashMap<String, ServerConnection>()
    private val serverMtu = HashMap<String, Int>()
    private val failures = HashMap<String, Int>()
    private val retryAt = HashMap<String, Long>()
    private val ignoredUntil = HashMap<String, Long>()
    private var linkCounter = 0

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        scope.launch {
            if (running) return@launch
            running = true
            log("transport start (me=$myPeerId, API ${Build.VERSION.SDK_INT})")
            registerReceiver()
            val a = adapter
            when {
                a == null || !BlePermissions.hasBleHardware(context) -> {
                    log("no Bluetooth LE hardware")
                    publish(adapterOverride = AdapterState.UNSUPPORTED)
                }
                isAdapterOn() -> startRadio()
                else -> {
                    log("Bluetooth is off; waiting")
                    publish()
                }
            }
            loopJob = scope.launch {
                var tick = 0
                while (isActive) {
                    delay(1_000)
                    tick++
                    housekeeping(tick)
                }
            }
        }
    }

    /** Stops everything and releases the thread. The transport can't be restarted. */
    fun stop() {
        scope.launch {
            running = false
            loopJob?.cancel()
            stopRadio("transport stop")
            unregisterReceiver()
            publish()
            events.close()
            log("transport stopped")
        }.invokeOnCompletion {
            scope.cancel()
            thread.quitSafely()
        }
    }

    /** Foreground: low-latency scan/advertise. Background: balanced. */
    fun setForeground(isForeground: Boolean) {
        scope.launch {
            if (foreground == isForeground) return@launch
            foreground = isForeground
            log("mode → ${if (isForeground) "foreground" else "background"}")
            if (radioUp) {
                restartScan()
                restartAdvertising()
            }
        }
    }

    // ------------------------------------------------------------------ radio up/down

    private fun startRadio() {
        if (radioUp || !running) return
        radioUp = true
        log("radio up")
        openServer()
        startScan()
        publish()
    }

    private fun stopRadio(reason: String) {
        if (!radioUp) return
        radioUp = false
        log("radio down: $reason")
        scanRestartJob?.cancel()
        scanRetryJob?.cancel()
        advertiseRestartJob?.cancel()
        advertiseRetryJob?.cancel()
        stopScan()
        stopAdvertising()
        clients.values.toList().forEach { closeClient(it, "radio down", retry = false) }
        servers.values.toList().forEach { closeServer(it, "radio down", cancel = false) }
        clients.clear()
        servers.clear()
        serverMtu.clear()
        candidates.clear()
        retryAt.clear()
        failures.clear()
        try {
            gattServer?.close()
        } catch (e: SecurityException) {
            log("server close: permission denied")
        } catch (e: RuntimeException) {
            log("server close: ${e.javaClass.simpleName}")
        }
        gattServer = null
        serverCharacteristic = null
        serviceAdded = false
        advertiseState = AdvertiseState.OFF
        scanState = ScanState.OFF
        _rssiByPeer.value = emptyMap()
        publish()
    }

    private fun isAdapterOn(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    scope.launch { onAdapterState(state) }
                }
                LocationManager.MODE_CHANGED_ACTION, LocationManager.PROVIDERS_CHANGED_ACTION -> scope.launch {
                    if (radioUp && scanState == ScanState.FAILED) {
                        scanState = ScanState.OFF
                        startScan()
                    }
                    publish()
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
    }

    private fun onAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                log("Bluetooth turning off")
                stopRadio("Bluetooth off")
            }
            BluetoothAdapter.STATE_ON -> {
                log("Bluetooth on")
                if (running) startRadio()
            }
        }
        publish()
    }

    // ------------------------------------------------------------------ GATT server + advertising

    private fun openServer() {
        if (!BlePermissions.canConnect(context)) {
            log("no BLUETOOTH_CONNECT permission: cannot open GATT server")
            return
        }
        val server = try {
            manager?.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            log("openGattServer: permission denied")
            null
        } catch (e: RuntimeException) {
            log("openGattServer failed: ${e.javaClass.simpleName}")
            null
        }
        if (server == null) {
            log("GATT server unavailable → scan-only")
            advertiseState = AdvertiseState.UNSUPPORTED
            advertiseError = "GATT server unavailable"
            return
        }
        gattServer = server
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        serverCharacteristic = characteristic
        val ok = try {
            server.addService(service)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            log("addService failed → scan-only")
            advertiseState = AdvertiseState.FAILED
            advertiseError = "addService failed"
        } else {
            log("GATT server opened; waiting for onServiceAdded")
        }
    }

    private fun onServiceAdded(status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            serviceAdded = true
            log("service added; advertising")
            startAdvertising()
        } else {
            log("onServiceAdded status=$status → scan-only")
            advertiseState = AdvertiseState.FAILED
            advertiseError = "service add status $status"
        }
        publish()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            scope.launch {
                advertiseState = AdvertiseState.ON
                advertiseError = null
                log("advertising (${if (foreground) "low latency" else "balanced"})")
                publish()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            scope.launch { onAdvertiseFailure(errorCode) }
        }
    }

    private fun startAdvertising() {
        if (!radioUp || !serviceAdded) return
        if (advertiseState == AdvertiseState.ON || advertiseState == AdvertiseState.STARTING) return
        val a = adapter ?: return
        val advertiser = try {
            if (a.isMultipleAdvertisementSupported) a.bluetoothLeAdvertiser else null
        } catch (e: SecurityException) {
            null
        }
        if (advertiser == null) {
            advertiseState = AdvertiseState.UNSUPPORTED
            advertiseError = "BLE advertising not supported"
            log("advertising not supported → scan-only")
            publish()
            return
        }
        if (!BlePermissions.canAdvertise(context)) {
            advertiseState = AdvertiseState.FAILED
            advertiseError = "no BLUETOOTH_ADVERTISE permission"
            log(advertiseError!!)
            publish()
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (foreground) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Advertising data: the 128-bit service UUID only (3 flags + 18 = 21 of 31 bytes).
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(SERVICE_PARCEL_UUID)
            .build()
        // Scan response: service data under the same UUID = my 8-byte peerId (2 + 16 + 8 = 26 of 31 bytes).
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE_PARCEL_UUID, myPeerId.toBytes())
            .build()
        try {
            advertiseState = AdvertiseState.STARTING
            advertiser.startAdvertising(settings, data, response, advertiseCallback)
        } catch (e: SecurityException) {
            advertiseState = AdvertiseState.FAILED
            advertiseError = "permission denied"
            log("startAdvertising: permission denied")
        } catch (e: RuntimeException) {
            advertiseState = AdvertiseState.FAILED
            advertiseError = e.javaClass.simpleName
            log("startAdvertising failed: ${e.javaClass.simpleName}")
        }
        publish()
    }

    private fun onAdvertiseFailure(code: Int) {
        val reason = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "unsupported"
            else -> "error $code"
        }
        if (code == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
            advertiseState = AdvertiseState.ON
            publish()
            return
        }
        log("advertising failed: $reason → scan-only")
        advertiseError = reason
        advertiseState = if (code == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) AdvertiseState.UNSUPPORTED else AdvertiseState.FAILED
        if (advertiseState == AdvertiseState.FAILED && code != AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
            advertiseRetryJob?.cancel()
            advertiseRetryJob = scope.launch {
                delay(15_000)
                if (advertiseState == AdvertiseState.FAILED) {
                    advertiseState = AdvertiseState.OFF
                    startAdvertising()
                }
            }
        }
        publish()
    }

    private fun stopAdvertising() {
        if (advertiseState != AdvertiseState.ON && advertiseState != AdvertiseState.STARTING) return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            log("stopAdvertising: permission denied")
        } catch (e: RuntimeException) {
            log("stopAdvertising: ${e.javaClass.simpleName}")
        }
        advertiseState = AdvertiseState.OFF
    }

    private fun restartAdvertising() {
        if (!serviceAdded) return
        if (advertiseState == AdvertiseState.UNSUPPORTED) return
        stopAdvertising()
        startAdvertising()
    }

    /** Some stacks silently stop advertising after a connection changes: restart, debounced 1 s. */
    private fun scheduleAdvertiseRestart() {
        advertiseRestartJob?.cancel()
        advertiseRestartJob = scope.launch {
            delay(1_000)
            if (radioUp) restartAdvertising()
        }
    }

    private val isScanOnly: Boolean get() = advertiseState != AdvertiseState.ON && advertiseState != AdvertiseState.STARTING

    // ------------------------------------------------------------------ scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch { onScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val copy = results.toList()
            scope.launch { copy.forEach { onScanResult(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch { onScanFailed(errorCode) }
        }
    }

    private fun startScan() {
        if (!radioUp || scanState == ScanState.ON) return
        if (!BlePermissions.canScan(context)) {
            scanState = ScanState.FAILED
            scanError = "no scan permission"
            publish()
            return
        }
        if (!BlePermissions.isLocationEnabled(context)) {
            scanState = ScanState.FAILED
            scanError = "Location is off"
            publish()
            return
        }
        // Android silently blocks apps that start more than 5 scans per 30 s: allow at most 4.
        val now = clock.now()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() >= 30_000) scanStarts.removeFirst()
        if (scanStarts.size >= 4) {
            val wait = 30_000 - (now - scanStarts.first()) + 250
            scanState = ScanState.THROTTLED
            log("scan start throttled for ${wait}ms")
            scanRetryJob?.cancel()
            scanRetryJob = scope.launch {
                delay(wait)
                startScan()
            }
            publish()
            return
        }
        val scanner = try {
            adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        } ?: run {
            scanState = ScanState.FAILED
            scanError = "scanner unavailable"
            publish()
            return
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build())
        val settings = ScanSettings.Builder()
            .setScanMode(if (foreground) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        scanStarts.addLast(now)
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanState = ScanState.ON
            scanError = null
            log("scan started (${if (foreground) "low latency" else "balanced"})")
        } catch (e: SecurityException) {
            scanState = ScanState.FAILED
            scanError = "permission denied"
            log("startScan: permission denied")
        } catch (e: RuntimeException) {
            scanState = ScanState.FAILED
            scanError = e.javaClass.simpleName
            log("startScan failed: ${e.javaClass.simpleName}")
        }
        scanRestartJob?.cancel()
        if (scanState == ScanState.ON) {
            scanRestartJob = scope.launch {
                delay(SCAN_RESTART_MILLIS)
                log("periodic scan restart")
                restartScan()
            }
        }
        publish()
    }

    private fun stopScan() {
        scanRestartJob?.cancel()
        if (scanState != ScanState.ON) {
            if (scanState == ScanState.THROTTLED) scanRetryJob?.cancel()
            scanState = ScanState.OFF
            return
        }
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            log("stopScan: permission denied")
        } catch (e: RuntimeException) {
            log("stopScan: ${e.javaClass.simpleName}")
        }
        scanState = ScanState.OFF
    }

    private fun restartScan() {
        stopScan()
        startScan()
    }

    private fun onScanFailed(code: Int) {
        if (code == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
            scanState = ScanState.ON
            return
        }
        scanState = ScanState.FAILED
        scanError = when (code) {
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed (too frequent?)"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "unsupported"
            else -> "error $code"
        }
        log("scan failed: $scanError; retrying in 30 s")
        scanRetryJob?.cancel()
        scanRetryJob = scope.launch {
            delay(30_000)
            if (scanState == ScanState.FAILED) {
                scanState = ScanState.OFF
                startScan()
            }
        }
        publish()
    }

    private fun onScanResult(result: ScanResult) {
        if (!radioUp) return
        val device = result.device ?: return
        val address = device.address ?: return
        val data = result.scanRecord?.getServiceData(SERVICE_PARCEL_UUID)
        val peerId = if (data != null && data.size >= PeerId.SIZE) PeerId.fromBytes(data, 0) else null
        if (peerId == myPeerId) return
        val now = clock.now()
        val c = candidates.getOrPut(address) {
            log("found ${address.takeLast(5)} peer=${peerId ?: "?"} rssi=${result.rssi}")
            Candidate(address, device, now)
        }
        c.device = device
        if (peerId != null) c.peerId = peerId
        c.rssi = if (c.samples == 0) result.rssi.toDouble() else RSSI_ALPHA * result.rssi + (1 - RSSI_ALPHA) * c.rssi
        c.samples++
        c.lastSeen = now
    }

    // ------------------------------------------------------------------ connection policy

    private fun housekeeping(tick: Int) {
        if (!running) return
        val now = clock.now()
        candidates.values.removeAll { now - it.lastSeen > CANDIDATE_TTL_MILLIS }
        ignoredUntil.values.removeAll { it <= now }
        if (radioUp) {
            // Recover from missing permissions / Location off once the user fixes them.
            if (scanState == ScanState.OFF) {
                startScan()
            } else if (scanState == ScanState.FAILED && scanRetryJob?.isActive != true && tick % 10 == 0) {
                scanState = ScanState.OFF
                startScan()
            }
            if (gattServer == null && advertiseState != AdvertiseState.UNSUPPORTED && tick % 10 == 0 &&
                BlePermissions.canConnect(context)
            ) {
                advertiseState = AdvertiseState.OFF
                openServer()
            }
            evaluateConnections(now)
        }
        _rssiByPeer.value = candidates.values
            .filter { it.peerId != null && now - it.lastSeen < 30_000 }
            .associate { it.peerId!! to it.rssi.toInt() }
        publish()
    }

    private fun evaluateConnections(now: Long) {
        if (!BlePermissions.canConnect(context)) return
        val outbound = clients.values.count { !it.closed }
        if (outbound >= MAX_OUTBOUND) return
        val linkedPeers = allConnections().filter { it.ready && !it.closed }.mapNotNull { it.peerId }.toSet()
        val eligible = candidates.values.filter { c ->
            now - c.lastSeen < 30_000 &&
                clients[c.address] == null &&
                (ignoredUntil[c.address] ?: 0L) <= now &&
                (retryAt[c.address] ?: 0L) <= now &&
                (c.peerId == null || c.peerId !in linkedPeers) &&
                servers[c.address]?.let { it.ready && !it.closed } != true &&
                shouldInitiate(c, now)
        }.sortedByDescending { it.rssi }
        for (c in eligible.take(MAX_OUTBOUND - outbound)) connect(c)
    }

    /**
     * Link dedupe: the lower peerId connects. The higher one waits 8 s for the inbound connection, then
     * connects anyway. Unknown peerId (no scan response yet) or scan-only mode → connect directly.
     */
    private fun shouldInitiate(c: Candidate, now: Long): Boolean {
        val theirs = c.peerId
        return when {
            isScanOnly -> true
            theirs == null -> now - c.waitStart >= PEER_ID_GRACE_MILLIS
            myPeerId < theirs -> true
            else -> now - c.waitStart >= WAIT_FOR_INBOUND_MILLIS
        }
    }

    private fun connect(c: Candidate) {
        val conn = ClientConnection(c.device)
        conn.peerId = c.peerId
        clients[c.address] = conn
        log("connecting to ${c.address.takeLast(5)} peer=${c.peerId ?: "?"} rssi=${c.rssi.toInt()}")
        val gatt = try {
            // Direct (autoConnect = false) LE connection; callbacks hop onto our thread.
            @Suppress("DEPRECATION")
            c.device.connectGatt(context, false, conn.callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("connectGatt: permission denied")
            null
        } catch (e: RuntimeException) {
            log("connectGatt failed: ${e.javaClass.simpleName}")
            null
        }
        if (gatt == null) {
            clients.remove(c.address)
            recordFailure(c.address)
            return
        }
        conn.gatt = gatt
        conn.setupTimeout = scope.launch {
            delay(SETUP_TIMEOUT_MILLIS)
            if (!conn.ready && !conn.closed) closeClient(conn, "setup timed out", retry = true)
        }
        publish()
    }

    private fun recordFailure(address: String) {
        val n = (failures[address] ?: 0) + 1
        if (n > MAX_RETRIES) {
            failures.remove(address)
            retryAt.remove(address)
            ignoredUntil[address] = clock.now() + IGNORE_MILLIS
            log("${address.takeLast(5)} failed ${MAX_RETRIES + 1} times; ignoring for 60 s")
        } else {
            failures[address] = n
            val backoff = 1_000L shl n // 2 s, 4 s, 8 s
            retryAt[address] = clock.now() + backoff
            log("retry ${address.takeLast(5)} in ${backoff / 1000} s (attempt $n/$MAX_RETRIES)")
        }
    }

    /** Called (via the mesh) once the first direct ANNOUNCE on a link names its peer. */
    private fun onIdentified(conn: Connection, peerId: PeerId) {
        conn.peerId = peerId
        if (conn.closed) return
        candidates[conn.address]?.peerId = peerId
        val duplicates = allConnections().filter { it !== conn && !it.closed && it.ready && it.peerId == peerId }
        for (other in duplicates) {
            val keep = preferred(conn, other, peerId)
            val drop = if (keep === conn) other else conn
            log("duplicate link to $peerId: keeping ${keep.id}, closing ${drop.id}")
            when (drop) {
                is ClientConnection -> closeClient(drop, "duplicate", retry = false, disconnect = true)
                is ServerConnection -> closeServer(drop, "duplicate", cancel = clients[drop.address] == null)
            }
            if (drop === conn) break
        }
        publish()
    }

    /** Keep the link whose GATT client is the lower peerId, so both phones make the same choice. */
    private fun preferred(a: Connection, b: Connection, peerId: PeerId): Connection {
        fun clientOf(c: Connection) = if (c.role == LinkRole.CLIENT) myPeerId else peerId
        val lower = if (myPeerId < peerId) myPeerId else peerId
        val aOk = clientOf(a) == lower
        val bOk = clientOf(b) == lower
        return when {
            aOk && !bOk -> a
            bOk && !aOk -> b
            else -> if (a.serial <= b.serial) a else b
        }
    }

    private fun allConnections(): List<Connection> = clients.values + servers.values

    // ------------------------------------------------------------------ connections

    private abstract inner class Connection(val device: BluetoothDevice, val role: LinkRole) : Link {
        val serial = ++linkCounter
        val address: String = device.address
        override val id: String = "${if (role == LinkRole.CLIENT) "c" else "s"}$serial-${address.takeLast(5).replace(":", "")}"
        var mtu = 23
        var ready = false

        @Volatile
        var closed = false
        var peerId: PeerId? = null
        val reassembler = Reassembler(clock, scope)
        private var streamCounter = Random.nextInt()
        var queuedPackets = 0
        val queue = GattQueue(scope, OP_TIMEOUT_MILLIS) { problem -> log("$id: $problem") }

        override fun send(packet: ByteArray): Boolean {
            if (closed) return false
            scope.launch { enqueuePacket(packet) }
            return true
        }

        override fun onPeerIdentified(peerId: PeerId) {
            scope.launch { onIdentified(this@Connection, peerId) }
        }

        /** All fragments of one packet are queued back to back, before the next packet. */
        private fun enqueuePacket(packet: ByteArray) {
            if (closed || !ready) return
            if (queuedPackets >= MAX_QUEUED_PACKETS) {
                log("$id: send queue full, dropping a packet")
                return
            }
            val fragments = Fragmenter.fragment(packet, Fragmenter.chunkSizeForMtu(mtu), streamCounter++)
            queuedPackets++
            fragments.forEachIndexed { i, fragment ->
                val last = i == fragments.lastIndex
                queue.enqueue(opName, onFinish = if (last) ({ queuedPackets-- }) else null) { writeFragment(fragment) }
            }
            if (queue.startFailures >= 3) onDead("repeated write failures")
        }

        fun onBytes(bytes: ByteArray) {
            if (closed || !ready) return
            reassembler.accept(bytes)?.let { events.trySend(LinkEvent.Received(id, it)) }
        }

        protected abstract val opName: String
        protected abstract fun writeFragment(bytes: ByteArray): Boolean
        protected abstract fun onDead(reason: String)

        fun info(): LinkInfo = LinkInfo(
            id = id,
            role = role,
            address = address,
            mtu = mtu,
            rssi = (candidates[address] ?: candidates.values.firstOrNull { it.peerId != null && it.peerId == peerId })?.rssi?.toInt(),
            peerId = peerId,
            ready = ready,
            queued = queue.size,
        )
    }

    private inner class ClientConnection(device: BluetoothDevice) : Connection(device, LinkRole.CLIENT) {
        var gatt: BluetoothGatt? = null
        var characteristic: BluetoothGattCharacteristic? = null
        var setupTimeout: Job? = null
        var connected = false
        override val opName = "write"

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                scope.launch { onClientConnectionState(this@ClientConnection, gatt, status, newState) }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) this@ClientConnection.mtu = mtu
                    log("$id: MTU $mtu (status $status)")
                    queue.completed("mtu")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                scope.launch { onClientServicesDiscovered(this@ClientConnection, gatt, status) }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                scope.launch { onClientDescriptorWrite(this@ClientConnection, status) }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                scope.launch {
                    if (status != BluetoothGatt.GATT_SUCCESS) log("$id: write status $status")
                    queue.completed("write")
                }
            }

            // Android 13+ calls only this variant (we never call super, so the legacy one isn't re-invoked).
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                val copy = value.copyOf()
                scope.launch { onBytes(copy) }
            }

            // Android 12 and below call only this variant. Copy the value before it can be overwritten.
            @Deprecated("Legacy variant for API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val copy = characteristic.value?.copyOf() ?: return
                scope.launch { onBytes(copy) }
            }
        }

        override fun writeFragment(bytes: ByteArray): Boolean {
            val g = gatt ?: return false
            val ch = characteristic ?: return false
            if (!BlePermissions.canConnect(context)) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }
        }

        override fun onDead(reason: String) = closeClient(this, reason, retry = true, disconnect = true)
    }

    private inner class ServerConnection(device: BluetoothDevice) : Connection(device, LinkRole.SERVER) {
        override val opName = "notify"

        init {
            serverMtu[address]?.let { mtu = it }
        }

        override fun writeFragment(bytes: ByteArray): Boolean {
            val server = gattServer ?: return false
            val ch = serverCharacteristic ?: return false
            if (!BlePermissions.canConnect(context)) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, ch, false, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.value = bytes
                    server.notifyCharacteristicChanged(device, ch, false)
                }
            }
        }

        override fun onDead(reason: String) = closeServer(this, reason, cancel = clients[address] == null)
    }

    // ------------------------------------------------------------------ client callbacks

    private fun onClientConnectionState(conn: ClientConnection, gatt: BluetoothGatt, status: Int, newState: Int) {
        if (clients[conn.address] !== conn || conn.closed) {
            safeClose(gatt)
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            conn.connected = true
            log("${conn.id}: connected; requesting MTU $REQUESTED_MTU")
            conn.queue.enqueue("mtu") { gatt.requestMtu(REQUESTED_MTU) }
            conn.queue.enqueue("discover") { gatt.discoverServices() }
        } else {
            val why = if (status == 133) "GATT error 133" else "state $newState status $status"
            closeClient(conn, why, retry = true)
        }
    }

    private fun onClientServicesDiscovered(conn: ClientConnection, gatt: BluetoothGatt, status: Int) {
        conn.queue.completed("discover")
        if (conn.closed) return
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
        if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
            closeClient(conn, "Murmur service not found (status $status)", retry = true, disconnect = true)
            return
        }
        conn.characteristic = characteristic
        val enabled = try {
            gatt.setCharacteristicNotification(characteristic, true)
        } catch (e: SecurityException) {
            false
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (!enabled || cccd == null) {
            closeClient(conn, "cannot enable notifications", retry = true, disconnect = true)
            return
        }
        conn.queue.enqueue("cccd") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }
    }

    private fun onClientDescriptorWrite(conn: ClientConnection, status: Int) {
        conn.queue.completed("cccd")
        if (conn.closed || conn.ready) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            closeClient(conn, "CCCD write status $status", retry = true, disconnect = true)
            return
        }
        conn.ready = true
        conn.setupTimeout?.cancel()
        failures.remove(conn.address)
        retryAt.remove(conn.address)
        log("${conn.id}: ready (client, MTU ${conn.mtu})")
        events.trySend(LinkEvent.Up(conn))
        publish()
    }

    private fun closeClient(conn: ClientConnection, reason: String, retry: Boolean, disconnect: Boolean = false) {
        if (conn.closed) return
        conn.closed = true
        conn.setupTimeout?.cancel()
        conn.queue.close()
        conn.reassembler.close()
        val gatt = conn.gatt
        if (gatt != null) {
            if (disconnect) {
                try {
                    gatt.disconnect()
                } catch (_: SecurityException) {
                } catch (_: RuntimeException) {
                }
            }
            safeClose(gatt)
        }
        if (clients[conn.address] === conn) clients.remove(conn.address)
        val wasReady = conn.ready
        conn.ready = false
        if (wasReady) events.trySend(LinkEvent.Down(conn.id))
        log("${conn.id}: closed ($reason)")
        candidates[conn.address]?.waitStart = clock.now()
        if (retry && running && radioUp) recordFailure(conn.address)
        publish()
    }

    private fun safeClose(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    // ------------------------------------------------------------------ server callbacks

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            scope.launch { onServiceAdded(status) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            scope.launch { onServerConnectionState(device, status, newState) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            scope.launch {
                // Remember it even if the connection entry doesn't exist yet (callback order varies).
                serverMtu[device.address] = mtu
                servers[device.address]?.let {
                    it.mtu = mtu
                    log("${it.id}: MTU $mtu")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val copy = value?.copyOf()
            val uuid = characteristic.uuid
            scope.launch { onServerWrite(device, requestId, uuid, preparedWrite, responseNeeded, offset, copy) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val copy = value?.copyOf()
            val uuid = descriptor.uuid
            scope.launch { onServerDescriptorWrite(device, requestId, uuid, responseNeeded, offset, copy) }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            scope.launch { respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0)) }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            scope.launch {
                val enabled = servers[device.address]?.let { it.ready && !it.closed } == true
                val value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            scope.launch { respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) log("notify to ${device.address.takeLast(5)} status $status")
                servers[device.address]?.queue?.completed("notify")
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            log("sendResponse: permission denied")
        } catch (e: RuntimeException) {
            log("sendResponse: ${e.javaClass.simpleName}")
        }
    }

    private fun onServerConnectionState(device: BluetoothDevice, status: Int, newState: Int) {
        val address = device.address
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (servers[address]?.closed != false) servers[address] = ServerConnection(device)
            log("inbound connection ${address.takeLast(5)}")
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            serverMtu.remove(address)
            servers.remove(address)?.let { closeServer(it, "disconnected (status $status)", cancel = false) }
        }
        scheduleAdvertiseRestart()
        publish()
    }

    private fun onServerWrite(
        device: BluetoothDevice,
        requestId: Int,
        uuid: UUID,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        if (uuid != CHARACTERISTIC_UUID || preparedWrite || offset != 0 || value == null) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            return
        }
        if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        servers[device.address]?.onBytes(value)
    }

    private fun onServerDescriptorWrite(
        device: BluetoothDevice,
        requestId: Int,
        uuid: UUID,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        if (uuid != CCCD_UUID || value == null) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }
        if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        val address = device.address
        val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
            value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        var conn = servers[address]
        if (enable) {
            if (conn == null || conn.closed) {
                if (conn?.closed == true) return // closed as a duplicate; ignore until it disconnects
                conn = ServerConnection(device).also { servers[address] = it }
            }
            if (!conn.ready) {
                conn.ready = true
                failures.remove(address)
                log("${conn.id}: ready (server, MTU ${conn.mtu})")
                events.trySend(LinkEvent.Up(conn))
                publish()
            }
        } else if (conn != null && conn.ready) {
            closeServer(conn, "notifications disabled", cancel = false)
        }
    }

    private fun closeServer(conn: ServerConnection, reason: String, cancel: Boolean) {
        if (conn.closed) return
        conn.closed = true
        conn.queue.close()
        conn.reassembler.close()
        val wasReady = conn.ready
        conn.ready = false
        if (wasReady) events.trySend(LinkEvent.Down(conn.id))
        if (cancel) {
            try {
                gattServer?.cancelConnection(conn.device)
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }
        candidates[conn.address]?.waitStart = clock.now()
        log("${conn.id}: closed ($reason)")
        publish()
    }

    // ------------------------------------------------------------------ status

    private fun publish(adapterOverride: AdapterState? = null) {
        val adapterState = adapterOverride ?: when {
            manager?.adapter == null -> AdapterState.UNSUPPORTED
            else -> try {
                when (adapter?.state) {
                    BluetoothAdapter.STATE_ON -> AdapterState.ON
                    BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
                    BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
                    else -> AdapterState.OFF
                }
            } catch (_: SecurityException) {
                AdapterState.OFF
            }
        }
        _status.value = BleStatus(
            running = running,
            adapter = adapterState,
            advertise = advertiseState,
            advertiseError = advertiseError,
            scan = scanState,
            scanError = scanError,
            scanOnly = radioUp && isScanOnly,
            missingPermissions = !BlePermissions.hasBluetooth(context),
            locationOff = !BlePermissions.isLocationEnabled(context),
            links = allConnections().filter { !it.closed && (it.ready || it.role == LinkRole.CLIENT) }.map { it.info() },
            candidates = candidates.size,
        )
    }

    private fun log(message: String) = log.log("BLE", message)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(Murmur.SERVICE_UUID)
        val CHARACTERISTIC_UUID: UUID = UUID.fromString(Murmur.CHARACTERISTIC_UUID)
        val CCCD_UUID: UUID = UUID.fromString(Murmur.CCCD_UUID)
        val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)

        const val REQUESTED_MTU = 517
        const val MAX_OUTBOUND = 6
        const val MAX_RETRIES = 3
        const val IGNORE_MILLIS = 60_000L
        const val OP_TIMEOUT_MILLIS = 5_000L
        const val SETUP_TIMEOUT_MILLIS = 25_000L
        const val WAIT_FOR_INBOUND_MILLIS = 8_000L
        const val PEER_ID_GRACE_MILLIS = 2_000L
        const val CANDIDATE_TTL_MILLIS = 60_000L
        const val SCAN_RESTART_MILLIS = 10 * 60_000L
        const val MAX_QUEUED_PACKETS = 64
        const val RSSI_ALPHA = 0.3
    }
}
