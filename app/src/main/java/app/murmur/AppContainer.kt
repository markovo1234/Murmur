package app.murmur

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.murmur.ble.BlePermissions
import app.murmur.call.CallManager
import app.murmur.ble.SystemStatus
import app.murmur.core.Clock
import app.murmur.core.crypto.Identity
import app.murmur.data.AppLock
import app.murmur.data.ChatRepository
import app.murmur.data.PeerRepository
import app.murmur.data.Settings
import app.murmur.data.SettingsRepository
import app.murmur.data.IdentityStore
import app.murmur.data.db.MurmurDatabase
import app.murmur.demo.DemoMode
import app.murmur.diagnostics.DiagnosticsLog
import app.murmur.service.MeshController
import app.murmur.service.Notifier
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Manual dependency injection: one instance per process, created in [MurmurApp.onCreate]. */
class AppContainer(val app: Application) {
    val clock: Clock = Clock.System
    val log = DiagnosticsLog()
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> log.log("APP", "error: ${e.javaClass.simpleName} ${e.message}") },
    )

    val db: MurmurDatabase = MurmurDatabase.create(app)
    val settings = SettingsRepository(app)

    /** Null until DataStore has been read once (the splash screen waits for it). */
    val settingsState: StateFlow<Settings?> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, null)
    private val loadedSettings: StateFlow<Settings> = settingsState.filterNotNull().stateIn(appScope, SharingStarted.Eagerly, Settings())

    val identityStore = IdentityStore(app, log)
    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    val notifier = Notifier(app)
    val system = SystemStatus(app)
    val mesh = MeshController(app, log)
    val demo = DemoMode(db, clock, appScope, log)
    val peers = PeerRepository(db, mesh.peers, mesh.rssi, demo.peers, appScope)
    val chats = ChatRepository(
        db = db,
        settings = loadedSettings,
        node = { mesh.runtime.value?.node },
        myId = { _identity.value?.peerId },
        peers = { peers.peers.value },
        notifier = notifier,
        clock = clock,
        log = log,
        scope = appScope,
    )

    val appLock = AppLock(settingsState, settings, clock)

    val calls = CallManager(this)

    val bleSupported: Boolean =
        BlePermissions.hasBleHardware(app) && app.getSystemService(BluetoothManager::class.java)?.adapter != null

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    init {
        demo.chats = chats
        chats.demo = demo
        peers.demo = demo
        log.log("APP", "Murmur ${BuildConfig.VERSION_NAME} starting; BLE ${if (bleSupported) "supported" else "NOT supported"}")
        appScope.launch(Dispatchers.IO) { _identity.value = identityStore.loadOrCreate() }
        appScope.launch {
            while (true) {
                chats.purgeOldNearby()
                delay(PURGE_INTERVAL_MILLIS)
            }
        }
        appScope.launch {
            settings.settings.map { it.demoMode }.distinctUntilChanged().collect { on -> if (on) demo.start() else demo.stop() }
        }
        appScope.launch {
            while (true) {
                chats.purgeExpired()
                delay(EXPIRY_SWEEP_MILLIS)
            }
        }
        appScope.launch { watchFavorites() }
        appScope.launch {
            // Onboarding just finished → start the mesh.
            settingsState.filterNotNull().map { it.onboardingDone }.distinctUntilChanged().collect { if (it) startMeshIfReady() }
        }
        appScope.launch {
            // Permissions granted / Bluetooth turned on while visible → bring the mesh up.
            system.state.collect { if (it.bluetoothPermissions && isAppInForeground) startMeshIfReady() }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onAppForeground()
            override fun onStop(owner: LifecycleOwner) = onAppBackground()
        })
    }

    private fun onAppForeground() {
        isAppInForeground = true
        appLock.onForeground()
        system.refresh()
        mesh.setForeground(true)
        appScope.launch { startMeshIfReady() }
    }

    private fun onAppBackground() {
        isAppInForeground = false
        appLock.onBackground()
        val keepRunning = settingsState.value?.keepRunningInBackground ?: true
        if (keepRunning) {
            mesh.setForeground(false)
        } else {
            log.log("APP", "app in background and 'Keep running' is off: stopping mesh")
            mesh.stop()
        }
    }

    /** Starts the mesh if onboarding is done, BLE exists and permissions are granted. Call while visible. */
    suspend fun startMeshIfReady() {
        val s = settingsState.filterNotNull().first()
        if (!s.onboardingDone || !bleSupported || !isAppInForeground) return
        if (mesh.runtime.value == null) mesh.start()
    }

    /** Erases messages, settings and identity keys. The UI then returns to onboarding. */
    suspend fun panicWipe() {
        log.log("PANIC", "panic wipe")
        calls.hangup()
        demo.stop()
        val running = mesh.runtime.value
        mesh.stop()
        if (running != null) withTimeoutOrNull(3_000) { running.stopped.await() }
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            identityStore.wipe()
        }
        settings.clear()
        notifier.cancelAll()
        log.clear()
        _identity.value = null
        appScope.launch(Dispatchers.IO) { _identity.value = identityStore.loadOrCreate() }
    }

    /** "⭐ Luna is nearby" when a favorite comes into range (at most every 30 minutes per person). */
    private suspend fun watchFavorites() {
        var previous: Map<app.murmur.core.protocol.PeerId, Boolean>? = null
        val lastAlert = HashMap<app.murmur.core.protocol.PeerId, Long>()
        peers.peers.collect { list ->
            val online = list.filter { it.favorite && !it.blocked }.associate { it.id to it.isOnline }
            val before = previous
            if (before != null && settingsState.value?.favoriteAlerts == true) {
                val now = clock.now()
                for (peer in list) {
                    val cameOnline = online[peer.id] == true && before[peer.id] != true
                    if (cameOnline && now - (lastAlert[peer.id] ?: 0L) > FAVORITE_ALERT_GAP_MILLIS) {
                        lastAlert[peer.id] = now
                        notifier.showFavoriteNearby(peer.id, peer.name, peer.emoji)
                    }
                }
            }
            previous = online
        }
    }

    private companion object {
        const val PURGE_INTERVAL_MILLIS = 60 * 60 * 1000L
        const val EXPIRY_SWEEP_MILLIS = 30_000L
        const val FAVORITE_ALERT_GAP_MILLIS = 30 * 60 * 1000L
    }
}
