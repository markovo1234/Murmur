package app.murmur.ui.main

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp
import app.murmur.data.SosResult
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.radar.AroundActions
import app.murmur.ui.radar.AroundRoute
import app.murmur.ui.theme.MurmurType
import kotlinx.coroutines.delay
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import app.murmur.data.SosAlert
import app.murmur.data.db.NEARBY_CONVERSATION
import app.murmur.ui.components.Format
import app.murmur.ui.components.PreviewData
import kotlinx.coroutines.flow.map
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.ble.BlePermissions
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.ThemeMode
import app.murmur.ui.chats.ChatsRoute
import app.murmur.ui.components.Motion
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PulsingDot
import app.murmur.ui.containerViewModel
import app.murmur.ui.onboarding.PermState
import app.murmur.ui.onboarding.rememberPermissionActions
import app.murmur.ui.peer.PeerSheet
import app.murmur.ui.radar.RadarField
import app.murmur.ui.settings.SettingsRoute
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainTab { AROUND, CHATS, YOU }

enum class StatusKind(val isProblem: Boolean) {
    NEARBY(false),
    SCANNING(false),
    PERMISSIONS(true),
    BLUETOOTH_OFF(true),
    LOCATION_OFF(true),
    STOPPED(true),
    UNSUPPORTED(true),
}

data class MainUiState(
    val status: StatusKind = StatusKind.SCANNING,
    val nearby: Int = 0,
    /** Reachable only through other phones. */
    val viaMesh: Int = 0,
    /** Names of everyone online, for the "SOS sent" banner. */
    val inRange: List<String> = emptyList(),
    val myEmoji: String = "🙂",
    val myColor: Int = 0,
    val totalUnread: Int = 0,
    val demoMode: Boolean = false,
    val bleSupported: Boolean = true,
)

class MainViewModel(private val c: AppContainer) : ViewModel() {
    private data class MeshBits(val running: Boolean, val nearby: Int, val viaMesh: Int, val inRange: List<String>, val unread: Int)

    private val meshBits = combine(c.mesh.runtime, c.peers.peers, c.chats.totalUnread) { rt, peers, unread ->
        val visible = peers.filter { !it.blocked }
        MeshBits(
            running = rt != null,
            nearby = visible.count { it.status == PeerStatus.NEARBY },
            viaMesh = visible.count { it.status == PeerStatus.VIA_MESH },
            inRange = visible.filter { it.isOnline }.map { it.name },
            unread = unread,
        )
    }

    val state: StateFlow<MainUiState> = combine(c.system.state, meshBits, c.settingsState) { radio, bits, s ->
        val (running, nearby, viaMesh, inRange, unread) = bits
        val demo = s?.demoMode == true
        val kind = when {
            !radio.bleSupported -> if (!demo) StatusKind.UNSUPPORTED else if (nearby > 0) StatusKind.NEARBY else StatusKind.SCANNING
            !radio.bluetoothPermissions -> StatusKind.PERMISSIONS
            !radio.bluetoothOn -> StatusKind.BLUETOOTH_OFF
            BlePermissions.needsLocationServices && !radio.locationOn -> StatusKind.LOCATION_OFF
            !running -> if (demo && nearby > 0) StatusKind.NEARBY else StatusKind.STOPPED
            nearby > 0 -> StatusKind.NEARBY
            else -> StatusKind.SCANNING
        }
        MainUiState(
            status = kind,
            nearby = nearby,
            viaMesh = viaMesh,
            inRange = inRange,
            myEmoji = s?.profile?.emoji ?: "🙂",
            myColor = s?.profile?.colorIndex ?: 0,
            totalUnread = unread,
            demoMode = demo,
            bleSupported = radio.bleSupported,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState(bleSupported = c.bleSupported))

    fun startMesh() {
        viewModelScope.launch { c.startMeshIfReady() }
    }

    fun enableDemo() {
        viewModelScope.launch { c.settings.setDemoMode(true) }
    }

    /** Other people's emergency alerts (newest first); they clear themselves after a while. */
    val sos: StateFlow<List<SosAlert>> = c.chats.sosAlerts.map { list -> list.filter { !it.mine } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismissSos(id: String) = c.chats.dismissSos(id)

    /** Returns false when throttled (one wave per person every few seconds). */
    suspend fun wave(peer: PeerId): Boolean = c.chats.wave(peer)

    /** Emergency alert to everyone in range (an empty message: "Needs help nearby"). */
    suspend fun sendSos(): SosResult = c.chats.sendSos("")
}

@Composable
fun MainRoute(
    onOpenChat: (String) -> Unit,
    onEditProfile: () -> Unit,
    onBlocked: () -> Unit,
    onDiagnostics: () -> Unit,
    onPeople: () -> Unit,
) {
    val vm = containerViewModel { MainViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sos by vm.sos.collectAsStateWithLifecycle()
    val permissions = rememberPermissionActions()
    var tab by rememberSaveable { mutableStateOf(MainTab.AROUND) }
    var sheetPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var sosSentAt by remember { mutableLongStateOf(0L) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(sosSentAt) {
        if (sosSentAt > 0) {
            delay(SENT_BANNER_MILLIS)
            sosSentAt = 0L
        }
    }

    if (!state.bleSupported && !state.demoMode) {
        UnsupportedScreen(onTryDemo = vm::enableDemo)
        return
    }
    BackHandler(enabled = tab != MainTab.AROUND) { tab = MainTab.AROUND }

    MainScreen(
        state = state,
        tab = tab,
        onTabChange = { tab = it },
        sos = sos,
        onOpenSos = { alert ->
            vm.dismissSos(alert.id)
            onOpenChat(NEARBY_CONVERSATION)
        },
        onDismissSos = { vm.dismissSos(it.id) },
        sosSent = sosSentAt > 0,
        onOpenSent = {
            sosSentAt = 0L
            onOpenChat(NEARBY_CONVERSATION)
        },
        onDismissSent = { sosSentAt = 0L },
        snackbar = snackbar,
        onFix = {
            when (state.status) {
                StatusKind.PERMISSIONS ->
                    if (permissions.snapshot.bluetooth == PermState.PERMANENTLY_DENIED) permissions.openAppSettings() else permissions.requestBluetooth()
                StatusKind.BLUETOOTH_OFF -> permissions.enableBluetooth()
                StatusKind.LOCATION_OFF -> permissions.openLocationSettings()
                StatusKind.STOPPED -> vm.startMesh()
                StatusKind.UNSUPPORTED -> vm.enableDemo()
                else -> Unit
            }
        },
    ) { current, padding ->
        when (current) {
            MainTab.AROUND -> AroundRoute(
                padding,
                AroundActions(
                    onPeerClick = { sheetPeer = it.toHex() },
                    onMessage = { onOpenChat(it.toHex()) },
                    onWave = { peer ->
                        scope.launch {
                            snackbar.showSnackbar(if (vm.wave(peer.id)) "Waved at ${peer.name} 👋" else "You just waved. Give it a moment.")
                        }
                    },
                    onOpenNearby = { onOpenChat(NEARBY_CONVERSATION) },
                    onAllPeople = onPeople,
                    onSos = {
                        scope.launch {
                            when (vm.sendSos()) {
                                SosResult.SENT -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    sosSentAt = System.currentTimeMillis()
                                }
                                SosResult.TOO_SOON -> snackbar.showSnackbar("You sent an SOS less than 2 minutes ago")
                                SosResult.NO_MESH -> snackbar.showSnackbar("The mesh is off, so the SOS can't be sent")
                            }
                        }
                    },
                ),
            )
            MainTab.CHATS -> ChatsRoute(padding, onOpen = onOpenChat)
            MainTab.YOU -> SettingsRoute(padding, onEditProfile, onBlocked, onDiagnostics)
        }
    }

    sheetPeer?.let { hex ->
        PeerId.fromHex(hex)?.let { id ->
            PeerSheet(id, onDismiss = { sheetPeer = null }, onMessage = {
                sheetPeer = null
                onOpenChat(hex)
            })
        }
    }
}

@Composable
fun MainScreen(
    state: MainUiState,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    onFix: () -> Unit,
    sos: List<SosAlert> = emptyList(),
    onOpenSos: (SosAlert) -> Unit = {},
    onDismissSos: (SosAlert) -> Unit = {},
    sosSent: Boolean = false,
    onOpenSent: () -> Unit = {},
    onDismissSent: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (MainTab, PaddingValues) -> Unit,
) {
    val reduce = MurmurTheme.reduceMotion
    val barEdge = MaterialTheme.colorScheme.outlineVariant
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                StatusHeader(state, showTitle = tab == MainTab.AROUND, onFix = onFix)
                SentSosBanner(sosSent, state.inRange, onOpenSent, onDismissSent)
                SosBanner(sos, onOpenSos, onDismissSos)
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                modifier = Modifier.drawBehind { drawLine(barEdge, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
            ) {
                TabItem(MainTab.AROUND, tab, "Around", 0, onTabChange) { Icon(MurmurIcons.Radar, contentDescription = null) }
                TabItem(MainTab.CHATS, tab, "Chats", state.totalUnread, onTabChange) { Icon(MurmurIcons.Chat, contentDescription = null) }
                TabItem(MainTab.YOU, tab, "You", 0, onTabChange) { EmojiAvatar(state.myEmoji, state.myColor, 24.dp) }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (reduce) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    fadeIn(tween(220, easing = Motion.Emphasized)) togetherWith fadeOut(tween(120))
                }
            },
            label = "tabs",
            modifier = Modifier.fillMaxSize(),
        ) { current -> content(current, padding) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    tab: MainTab,
    selectedTab: MainTab,
    label: String,
    badge: Int,
    onSelect: (MainTab) -> Unit,
    icon: @Composable () -> Unit,
) {
    val selected = tab == selectedTab
    val bounce = remember { Animatable(1f) }
    val reduce = MurmurTheme.reduceMotion
    LaunchedEffect(selected) {
        if (selected && !reduce) {
            bounce.animateTo(1.22f, tween(90))
            bounce.animateTo(1f, Motion.pop())
        }
    }
    NavigationBarItem(
        selected = selected,
        onClick = { onSelect(tab) },
        label = { Text(label, fontWeight = FontWeight.SemiBold) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        icon = {
            BadgedBox(badge = {
                if (badge > 0) Badge { Text(if (badge > 99) "99+" else "$badge") }
            }) {
                Box(
                    Modifier
                        .semantics { contentDescription = if (badge > 0) "$label, $badge unread" else label }
                        .graphicsLayer {
                            scaleX = bounce.value
                            scaleY = bounce.value
                        },
                ) { icon() }
            }
        },
    )
}

private fun statusText(state: MainUiState): String = when (state.status) {
    StatusKind.NEARBY -> "${state.nearby} nearby" + if (state.viaMesh > 0) " · ${state.viaMesh} via mesh" else ""
    StatusKind.SCANNING -> "Scanning"
    StatusKind.PERMISSIONS -> "Permission needed"
    StatusKind.BLUETOOTH_OFF -> "Bluetooth off"
    StatusKind.LOCATION_OFF -> "Location off"
    StatusKind.STOPPED -> "Mesh stopped"
    StatusKind.UNSUPPORTED -> "No Bluetooth LE"
}

private fun bannerText(kind: StatusKind): Pair<String, String>? = when (kind) {
    StatusKind.PERMISSIONS -> "Murmur needs the Nearby devices permission to find people around you." to "Grant"
    StatusKind.BLUETOOTH_OFF -> "Bluetooth is off, so nobody can reach you." to "Turn on"
    StatusKind.LOCATION_OFF -> "This Android version needs Location on to scan for Bluetooth." to "Open settings"
    StatusKind.STOPPED -> "The mesh is stopped. Start it to chat and relay." to "Start"
    StatusKind.UNSUPPORTED -> "This phone has no Bluetooth LE." to "Try demo"
    else -> null
}

@Composable
private fun StatusHeader(state: MainUiState, showTitle: Boolean, onFix: () -> Unit) {
    val dot = when {
        state.status == StatusKind.NEARBY -> MurmurTheme.colors.online
        state.status == StatusKind.SCANNING -> MaterialTheme.colorScheme.primary
        state.status == StatusKind.STOPPED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = if (showTitle) 6.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Chats and You draw their own big titles; Around (home) carries the app name and mesh chip.
        if (showTitle) Box(Modifier.fillMaxWidth()) {
            Text(
                "Murmur",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Status: ${statusText(state)}${if (state.demoMode) ", demo mode" else ""}"
                    },
            ) {
                Row(Modifier.height(30.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(dot)
                    AnimatedContent(statusText(state), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pill") {
                        Text(it, style = MurmurType.Mono, fontSize = 11.5.sp)
                    }
                    if (state.demoMode) {
                        Text(" · demo", style = MurmurType.Mono, fontSize = 11.5.sp, color = MurmurTheme.colors.hop)
                    }
                }
            }
        }
        val banner = bannerText(state.status)
        AnimatedVisibility(
            visible = banner != null,
            enter = expandVertically(Motion.moveOrSnap(MurmurTheme.reduceMotion)) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val (message, action) = banner ?: ("" to "")
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = onFix) { Text(action) }
                }
            }
        }
    }
}

/** Confirmation after you hold your avatar for SOS: who it went to and where it's pinned. */
@Composable
private fun SentSosBanner(visible: Boolean, inRange: List<String>, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(Motion.moveOrSnap(MurmurTheme.reduceMotion)) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            shape = MaterialTheme.shapes.large,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text("🆘 SOS sent to everyone in range", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        inRange.isEmpty() -> "Nobody is in range right now. It's pinned in #nearby and relayed as people arrive."
                        else -> "${Format.names(inRange)} get a loud alert. It's pinned in #nearby."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)) {
                        Text("OK")
                    }
                    TextButton(onClick = onOpen, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)) {
                        Text("Open #nearby", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Red banner for the newest SOS alert from someone in range. */
@Composable
private fun SosBanner(alerts: List<SosAlert>, onOpen: (SosAlert) -> Unit, onDismiss: (SosAlert) -> Unit) {
    val latest = alerts.firstOrNull()
    // Keep the last alert while the exit animation runs (plain holder: no state write during composition).
    val last = remember { arrayOfNulls<SosAlert>(1) }
    if (latest != null) last[0] = latest
    AnimatedVisibility(
        visible = latest != null,
        enter = expandVertically(Motion.moveOrSnap(MurmurTheme.reduceMotion)) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val alert = latest ?: last[0] ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text(
                    "🆘 ${alert.nickname} needs help",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val where = if (alert.hops <= 1) "right next to you" else "${alert.hops} hops away"
                Text(
                    listOfNotNull(alert.text.takeIf { it.isNotBlank() }?.let { "“$it”" }, "$where · ${Format.clock(alert.time)}")
                        .joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (alerts.size > 1) {
                        Text(
                            "+${alerts.size - 1} more",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.align(Alignment.CenterVertically).weight(1f),
                        )
                    }
                    TextButton(onClick = { onDismiss(alert) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)) {
                        Text("Dismiss")
                    }
                    TextButton(onClick = { onOpen(alert) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)) {
                        Text("Open #nearby", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun UnsupportedScreen(onTryDemo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RadarField("📵", 3, emptyList(), {}, showPeers = false, modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(Modifier.heightIn(min = 24.dp))
            Text("This phone can't join the mesh", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                "Murmur needs Bluetooth Low Energy, which this device doesn't have. You can still explore the whole app with five pretend people.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Button(onClick = onTryDemo, modifier = Modifier.heightIn(min = 52.dp)) { Text("Try Demo mode") }
        }
    }
}

@Composable
private const val SENT_BANNER_MILLIS = 8_000L

@Composable
private fun PreviewTabContent(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("Radar", color = Color.Gray)
    }
}

@Preview(name = "Main · light · 3 nearby", showBackground = true)
@Composable
private fun MainLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    MainScreen(MainUiState(StatusKind.NEARBY, nearby = 3, viaMesh = 1, totalUnread = 4, myEmoji = "🐧", myColor = 1), MainTab.AROUND, {}, {}) { _, p -> PreviewTabContent(p) }
}

@Preview(name = "Main · dark · Bluetooth off", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    MainScreen(MainUiState(StatusKind.BLUETOOTH_OFF), MainTab.CHATS, {}, {}) { _, p -> PreviewTabContent(p) }
}

@Preview(name = "Main · light · SOS", showBackground = true)
@Composable
private fun MainSosPreview() = MurmurTheme(ThemeMode.LIGHT) {
    val alert = SosAlert("a", PreviewData.kai.id, "Kai", "Twisted ankle by the north gate", PreviewData.NOW, 2, mine = false)
    MainScreen(MainUiState(StatusKind.NEARBY, nearby = 3), MainTab.AROUND, {}, {}, sos = listOf(alert)) { _, p -> PreviewTabContent(p) }
}

@Preview(name = "Unsupported · light", showBackground = true)
@Composable
private fun UnsupportedLightPreview() = MurmurTheme(ThemeMode.LIGHT) { UnsupportedScreen {} }

@Preview(name = "Unsupported · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnsupportedDarkPreview() = MurmurTheme(ThemeMode.DARK) { UnsupportedScreen {} }
