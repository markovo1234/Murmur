package app.murmur.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.AppContainer
import app.murmur.ble.BlePermissions
import app.murmur.data.Settings
import app.murmur.ui.diagnostics.DiagnosticsRoute
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Phase-2 shell: Diagnostics plus a permission request. Replaced by full navigation in Phase 3. */
@Composable
fun MurmurRoot(container: AppContainer, pendingConversation: StateFlow<String?>) {
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val s = settings ?: Settings()
    MurmurTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor, reduceMotion = rememberReduceMotion()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                scope.launch { container.startMeshIfReady() }
            }
            if (!BlePermissions.hasBluetooth(context)) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch((BlePermissions.bluetooth + listOfNotNull(BlePermissions.notifications)).toTypedArray()) }) {
                        Text("Grant Bluetooth permissions")
                    }
                }
            } else {
                DiagnosticsRoute(onBack = {})
            }
        }
    }
}
