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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.murmur.ui.radar.RadarRoute
import app.murmur.ui.settings.SettingsRoute
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainTab { RADAR, CHATS, SETTINGS }

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
    val totalUnread: Int = 0,
    val demoMode: Boolean = false,
    val bleSupported: Boolean = true,
)

class MainViewModel(private val c: AppContainer) : ViewModel() {
    private val meshBits = combine(c.mesh.runtime, c.peers.peers, c.chats.totalUnread) { rt, peers, unread ->
        Triple(rt != null, peers.count { it.status == PeerStatus.NEARBY && !it.blocked }, unread)
    }

    val state: StateFlow<MainUiState> = combine(c.system.state, meshBits, c.settingsState) { radio, (running, nearby, unread), s ->
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
        MainUiState(kind, nearby, unread, demo, radio.bleSupported)
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
    var tab by rememberSaveable { mutableStateOf(MainTab.RADAR) }
    var sheetPeer by rememberSaveable { mutableStateOf<String?>(null) }

    if (!state.bleSupported && !state.demoMode) {
        UnsupportedScreen(onTryDemo = vm::enableDemo)
        return
    }
    BackHandler(enabled = tab != MainTab.RADAR) { tab = MainTab.RADAR }

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
            MainTab.RADAR -> RadarRoute(padding, onPeerClick = { sheetPeer = it.toHex() }, onMessage = { onOpenChat(it.toHex()) }, onAllPeople = onPeople)
            MainTab.CHATS -> ChatsRoute(padding, onOpen = onOpenChat)
            MainTab.SETTINGS -> SettingsRoute(padding, onEditProfile, onBlocked, onDiagnostics)
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
    content: @Composable (MainTab, PaddingValues) -> Unit,
) {
    val reduce = MurmurTheme.reduceMotion
    Scaffold(
        topBar = {
            Column {
                StatusHeader(state, onFix)
                SosBanner(sos, onOpenSos, onDismissSos)
            }
        },
        bottomBar = {
            NavigationBar {
                TabItem(MainTab.RADAR, tab, "Radar", MurmurIcons.Radar, 0, onTabChange)
                TabItem(MainTab.CHATS, tab, "Chats", MurmurIcons.Chat, state.totalUnread, onTabChange)
                TabItem(MainTab.SETTINGS, tab, "Settings", Icons.Filled.Settings, 0, onTabChange)
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
    icon: ImageVector,
    badge: Int,
    onSelect: (MainTab) -> Unit,
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
        label = { Text(label) },
        icon = {
            BadgedBox(badge = {
                if (badge > 0) Badge { Text(if (badge > 99) "99+" else "$badge") }
            }) {
                Icon(
                    icon,
                    contentDescription = if (badge > 0) "$label, $badge unread" else label,
                    modifier = Modifier.graphicsLayer {
                        scaleX = bounce.value
                        scaleY = bounce.value
                    },
                )
            }
        },
    )
}

private fun statusText(state: MainUiState): String = when (state.status) {
    StatusKind.NEARBY -> "${state.nearby} nearby"
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
private fun StatusHeader(state: MainUiState, onFix: () -> Unit) {
    val dot = when {
        state.status == StatusKind.NEARBY -> MurmurTheme.colors.online
        state.status == StatusKind.SCANNING -> MaterialTheme.colorScheme.primary
        state.status == StatusKind.STOPPED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "Murmur",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Status: ${statusText(state)}${if (state.demoMode) ", demo mode" else ""}"
                    },
            ) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(dot)
                    AnimatedContent(statusText(state), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pill") {
                        Text(it, style = MaterialTheme.typography.labelLarge)
                    }
                    if (state.demoMode) {
                        Text(" · demo", style = MaterialTheme.typography.labelLarge, color = MurmurTheme.colors.hop)
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
private fun PreviewTabContent(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("Radar", color = Color.Gray)
    }
}

@Preview(name = "Main · light · 3 nearby", showBackground = true)
@Composable
private fun MainLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    MainScreen(MainUiState(StatusKind.NEARBY, nearby = 3, totalUnread = 4), MainTab.RADAR, {}, {}) { _, p -> PreviewTabContent(p) }
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
    MainScreen(MainUiState(StatusKind.NEARBY, nearby = 3), MainTab.RADAR, {}, {}, sos = listOf(alert)) { _, p -> PreviewTabContent(p) }
}

@Preview(name = "Unsupported · light", showBackground = true)
@Composable
private fun UnsupportedLightPreview() = MurmurTheme(ThemeMode.LIGHT) { UnsupportedScreen {} }

@Preview(name = "Unsupported · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnsupportedDarkPreview() = MurmurTheme(ThemeMode.DARK) { UnsupportedScreen {} }
