package app.murmur.ui.diagnostics

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.ble.AdapterState
import app.murmur.ble.AdvertiseState
import app.murmur.ble.BleStatus
import app.murmur.ble.ScanState
import app.murmur.core.mesh.MeshStats
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.containerViewModel
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsRoute(onBack: () -> Unit) {
    val vm = containerViewModel { DiagnosticsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        state = state,
        onBack = onBack,
        logText = vm::logText,
        onDemoModeChange = vm::setDemoMode,
        onStartMesh = vm::startMesh,
        onStopMesh = vm::stopMesh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    logText: () -> String,
    onDemoModeChange: (Boolean) -> Unit,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(label: String, text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Identity") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label("My peerId")
                            Text(state.myPeerId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        }
                        IconButton(onClick = { copy("peerId", state.myPeerId) }) {
                            Icon(MurmurIcons.Copy, contentDescription = "Copy peer id")
                        }
                    }
                    KeyValue("Android API", state.apiLevel.toString())
                    KeyValue("Protocol version", state.protocolVersion.toString())
                }
            }
            item { RadioSection(state) }
            item {
                Section("Mesh") {
                    KeyValue("Mesh", if (state.meshRunning) "running" else "stopped", good = state.meshRunning)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onStartMesh, enabled = !state.meshRunning && state.bleSupported) { Text("Start") }
                        OutlinedButton(onClick = onStopMesh, enabled = state.meshRunning) { Text("Stop") }
                    }
                }
            }
            item {
                Section("Links (${state.links.count { it.ready }})") {
                    if (state.links.isEmpty()) {
                        Text("No links", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.links.forEachIndexed { i, link ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(link.peer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${link.role} · ${if (link.ready) "ready" else "connecting"} · MTU ${link.mtu} · " +
                                "RSSI ${link.rssi?.let { "$it dBm" } ?: "–"} · queue ${link.queued}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(link.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                val s = state.stats ?: MeshStats()
                Section("Counters") {
                    KeyValue("Sent", s.sent.toString())
                    KeyValue("Received", s.received.toString())
                    KeyValue("Relayed", s.relayed.toString())
                    KeyValue("Dropped (duplicate)", s.droppedDuplicate.toString())
                    KeyValue("Dropped (invalid)", s.droppedInvalid.toString())
                }
            }
            item {
                Section("Demo mode") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = Dimens.MinTouch)) {
                        Column(Modifier.weight(1f)) {
                            Text("Demo mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "5 fake peers to preview the whole app on one phone. Never touches Bluetooth.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.demoMode, onCheckedChange = onDemoModeChange, modifier = Modifier.semantics { contentDescription = "Demo mode" })
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log (last ${state.log.size} lines)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy("Murmur log", logText()) }) {
                        Icon(MurmurIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                }
            }
            items(state.log.asReversed()) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RadioSection(state: DiagnosticsUiState) {
    val ble = state.ble
    Section("Bluetooth") {
        if (!state.bleSupported) {
            KeyValue("Hardware", "no Bluetooth LE", good = false)
            return@Section
        }
        if (ble == null) {
            KeyValue("Transport", "not running")
            return@Section
        }
        KeyValue("Adapter", ble.adapter.name.lowercase().replace('_', ' '), good = ble.adapter == AdapterState.ON)
        KeyValue(
            "Advertising",
            ble.advertise.name.lowercase() + (ble.advertiseError?.let { " ($it)" } ?: ""),
            good = ble.advertise == AdvertiseState.ON,
        )
        KeyValue("Scanning", ble.scan.name.lowercase() + (ble.scanError?.let { " ($it)" } ?: ""), good = ble.scan == ScanState.ON)
        if (ble.scanOnly) KeyValue("Mode", "scan-only (others can't find me; I connect to them)", good = false)
        KeyValue("Permissions", if (ble.missingPermissions) "missing" else "granted", good = !ble.missingPermissions)
        if (ble.locationOff) KeyValue("Location", "off (needed to scan on this Android version)", good = false)
        KeyValue("Devices in range", ble.candidates.toString())
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KeyValue(key: String, value: String, good: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (good != null) {
            val dot = if (good) MurmurTheme.colors.online else MaterialTheme.colorScheme.error
            Surface(Modifier.size(8.dp), shape = CircleShape, color = dot) {}
            Spacer(Modifier.width(6.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val previewState = DiagnosticsUiState(
    myPeerId = "3f9a0c12e4b7d655",
    meshRunning = true,
    ble = BleStatus(
        running = true,
        adapter = AdapterState.ON,
        advertise = AdvertiseState.ON,
        scan = ScanState.ON,
        candidates = 3,
    ),
    stats = MeshStats(sent = 42, received = 118, relayed = 37, droppedDuplicate = 64, droppedInvalid = 1),
    links = listOf(
        LinkRow("c1-a3f01", "client", 517, -58, "Luna (a1b2c3d4e5f60718)", true, 0),
        LinkRow("s2-77c02", "server", 247, -79, "unidentified", true, 2),
    ),
    log = listOf("12:00:01.123 BLE  scan started (low latency)", "12:00:02.456 BLE  c1-a3f01: ready (client, MTU 517)"),
)

@Preview(name = "Diagnostics · light", showBackground = true)
@Composable
private fun DiagnosticsLightPreview() {
    MurmurTheme(themeMode = app.murmur.data.ThemeMode.LIGHT) {
        DiagnosticsScreen(previewState, {}, { "" }, {}, {}, {})
    }
}

@Preview(name = "Diagnostics · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, backgroundColor = 0xFF0A0F1E)
@Composable
private fun DiagnosticsDarkPreview() {
    MurmurTheme(themeMode = app.murmur.data.ThemeMode.DARK) {
        Surface(Modifier.background(Color(0xFF0A0F1E))) {
            DiagnosticsScreen(previewState, {}, { "" }, {}, {}, {})
        }
    }
}
