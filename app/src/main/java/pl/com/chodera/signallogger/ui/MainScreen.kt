package pl.com.chodera.signallogger.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pl.com.chodera.signallogger.model.SimSample
import pl.com.chodera.signallogger.model.serviceStateLabelPl
import pl.com.chodera.signallogger.model.techLabel
import pl.com.chodera.signallogger.recorder.RecorderState
import pl.com.chodera.signallogger.recorder.SessionFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val INTERVAL_OPTIONS_MS = listOf(2_000L, 5_000L, 10_000L)

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MainContent(state = state, onIntent = viewModel::onIntent)
}

@Composable
private fun MainContent(state: UiState, onIntent: (UiIntent) -> Unit) {
    val context = LocalContext.current
    val recorder = state.recorder
    val recording = recorder.isRecording

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onIntent(UiIntent.Refresh)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { onIntent(UiIntent.PermissionsChanged) },
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Logger zasięgu", style = MaterialTheme.typography.headlineMedium)

            if (!state.permissionsGranted || !state.notificationsGranted) {
                PermissionsCard(
                    state = state,
                    onGrant = { permissionLauncher.launch(requiredPermissions()) },
                    onOpenSettings = {
                        launchSafely(
                            context,
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            }

            WarningsSection(
                state = state,
                onOpenLocationSettings = {
                    launchSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                onOpenBatterySettings = {
                    launchSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
            )

            SettingsCard(state = state, onIntent = onIntent)

            RecordControls(state = state, onIntent = onIntent)

            recorder.lastError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Ostatni błąd: $error", modifier = Modifier.padding(16.dp))
                }
            }

            if (recording) {
                LiveCard(recorder = recorder)
            }

            SessionsSection(state = state, onIntent = onIntent)
        }
    }

    state.pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { onIntent(UiIntent.CancelDelete) },
            title = { Text("Usunąć log?") },
            text = { Text("Plik $name zostanie trwale usunięty.") },
            confirmButton = {
                TextButton(onClick = { onIntent(UiIntent.ConfirmDelete) }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(UiIntent.CancelDelete) }) { Text("Anuluj") }
            },
        )
    }
}

// ---- permissions & warnings ---------------------------------------------------------------------

@Composable
private fun PermissionsCard(state: UiState, onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Potrzebne uprawnienia", style = MaterialTheme.typography.titleMedium)
            if (!state.permissionsGranted) {
                Text(
                    text = "Lokalizacja (dokładna) — wymagana do odczytu komórek sieci i pozycji GPS.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Stan telefonu — potrzebny do odczytu listy kart SIM.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!state.notificationsGranted) {
                Text(
                    text = "Powiadomienia — bez nich nagrywanie działa, ale nie zobaczysz powiadomienia ze znacznikiem i przyciskiem Stop.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Nadaj uprawnienia") }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Otwórz ustawienia aplikacji")
            }
        }
    }
}

@Composable
private fun WarningsSection(
    state: UiState,
    onOpenLocationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val recording = state.recorder.isRecording
    // While recording the service reports the live GPS provider state; otherwise the last refresh.
    val gpsOff = if (recording) !state.recorder.gpsProviderEnabled else !state.gpsEnabled

    if (gpsOff) {
        WarningCard(text = "Lokalizacja jest wyłączona — bez niej nie będzie pozycji GPS w logu.") {
            Button(onClick = onOpenLocationSettings) { Text("Włącz lokalizację") }
        }
    }

    if (state.permissionsGranted && state.detectedSims.size < 2) {
        val text = if (state.detectedSims.isEmpty()) {
            "Nie wykryto aktywnych kart SIM."
        } else {
            "Aktywna jest tylko jedna karta SIM — włącz drugą, żeby porównać sieci."
        }
        WarningCard(text = text) {}
    }

    if (!recording) {
        OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth()) {
            Text("Wyłącz optymalizację baterii")
        }
    }
}

@Composable
private fun WarningCard(text: String, actions: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

// ---- settings & controls ------------------------------------------------------------------------

@Composable
private fun SettingsCard(state: UiState, onIntent: (UiIntent) -> Unit) {
    val recording = state.recorder.isRecording
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Ustawienia", style = MaterialTheme.typography.titleMedium)
            Text(text = "Odstęp między próbkami", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (intervalMs in INTERVAL_OPTIONS_MS) {
                    FilterChip(
                        selected = state.intervalMs == intervalMs,
                        onClick = { onIntent(UiIntent.SetInterval(intervalMs)) },
                        label = { Text("${intervalMs / 1000} s") },
                        enabled = !recording,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Test internetu na karcie danych",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.probeEnabled,
                    onCheckedChange = { onIntent(UiIntent.SetProbe(it)) },
                    enabled = !recording,
                )
            }
        }
    }
}

@Composable
private fun RecordControls(state: UiState, onIntent: (UiIntent) -> Unit) {
    if (!state.recorder.isRecording) {
        Button(
            onClick = { onIntent(UiIntent.Start) },
            enabled = state.permissionsGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text(text = "Start", style = MaterialTheme.typography.titleLarge)
        }
    } else {
        Button(
            onClick = { onIntent(UiIntent.Stop) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text(text = "Stop", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedButton(
            onClick = { onIntent(UiIntent.Mark) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(text = "Znacznik: nie działa")
        }
    }
}

// ---- live card ----------------------------------------------------------------------------------

@Composable
private fun LiveCard(recorder: RecorderState) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val elapsedMs = (nowMs - (recorder.startedAtMs ?: nowMs)).coerceAtLeast(0L)
    val last = recorder.last

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "Nagrywanie: ${formatElapsed(elapsedMs)}", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Próbki: ${recorder.sampleCount} · znaczniki: ${recorder.markerCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            recorder.fileName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text(text = gpsLine(recorder), style = MaterialTheme.typography.bodyMedium)

            if (last == null) {
                Text(text = "Czekam na pierwszą próbkę…", style = MaterialTheme.typography.bodyMedium)
            } else if (last.sims.isEmpty()) {
                Text(text = "Brak aktywnych kart SIM.", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (sim in last.sims) {
                    HorizontalDivider()
                    SimRow(sim)
                }
            }
        }
    }
}

@Composable
private fun SimRow(sim: SimSample) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${sim.carrier} (slot ${sim.slot + 1})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (sim.isDataSim) DataSimBadge()
        }
        Text(
            text = "${serviceStateLabelPl(sim.serviceState)} · ${sim.techLabel}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = signalLine(sim), style = MaterialTheme.typography.bodyMedium)
        Text(text = cellLine(sim), style = MaterialTheme.typography.bodySmall)
        val probe = sim.probe
        if (probe != null) {
            Text(
                text = if (probe.ok) "internet OK ${probe.ms ?: 0} ms" else "internet: brak",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (probe.ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DataSimBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "SIM danych",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ---- saved sessions -----------------------------------------------------------------------------

@Composable
private fun SessionsSection(state: UiState, onIntent: (UiIntent) -> Unit) {
    val activeFile = if (state.recorder.isRecording) state.recorder.fileName else null
    val sessions = state.sessions.filter { it.name != activeFile }

    Text(text = "Zapisane sesje", style = MaterialTheme.typography.titleLarge)
    if (sessions.isEmpty()) {
        Text(text = "Brak zapisanych sesji.", style = MaterialTheme.typography.bodyMedium)
    }
    for (session in sessions) {
        SessionCard(session = session, onIntent = onIntent)
    }
}

@Composable
private fun SessionCard(session: SessionFile, onIntent: (UiIntent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = session.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${formatSizeKb(session.sizeBytes)} · ${formatDate(session.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onIntent(UiIntent.Share(session.name)) }) { Text("Udostępnij") }
                TextButton(onClick = { onIntent(UiIntent.AskDelete(session.name)) }) { Text("Usuń") }
            }
        }
    }
}

// ---- formatting helpers -------------------------------------------------------------------------

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

private fun formatDate(epochMs: Long): String =
    DATE_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatSizeKb(bytes: Long): String = String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
}

private fun gpsLine(recorder: RecorderState): String {
    if (!recorder.gpsProviderEnabled) return "GPS: wyłączony w ustawieniach telefonu"
    val fix = recorder.last?.location ?: return "GPS: brak fixa"
    val parts = ArrayList<String>()
    fix.accuracyM?.let { parts.add(String.format(Locale.ROOT, "±%.0f m", it)) }
    parts.add(String.format(Locale.ROOT, "wiek %.0f s", fix.ageS))
    fix.speedKmh?.let { parts.add(String.format(Locale.ROOT, "%.0f km/h", it)) }
    return "GPS: " + parts.joinToString(" · ")
}

private fun signalLine(sim: SimSample): String {
    val s = sim.signal
    val parts = ArrayList<String>()
    val rsrp = s.rsrp
    val sinr = s.sinr
    val nrRsrp = s.nrRsrp
    val nrSinr = s.nrSinr
    val dbm = s.dbm
    if (rsrp != null) parts.add("RSRP $rsrp dBm")
    if (sinr != null) parts.add("SINR $sinr dB")
    if (nrRsrp != null) parts.add("NR RSRP $nrRsrp dBm")
    if (nrSinr != null) parts.add("NR SINR $nrSinr dB")
    if (parts.isEmpty() && dbm != null) parts.add("$dbm dBm")
    if (parts.isEmpty()) return "Sygnał: brak odczytu"
    return parts.joinToString(" · ")
}

private fun cellLine(sim: SimSample): String {
    val cell = sim.serving ?: return "Komórka: brak danych"
    val parts = ArrayList<String>()
    parts.add("cell ${cell.cellId ?: "?"}")
    parts.add("PCI ${cell.pci ?: "?"}")
    if (cell.bands.isNotEmpty()) parts.add("B" + cell.bands.joinToString("/B"))
    return parts.joinToString(" · ")
}

// ---- platform helpers ---------------------------------------------------------------------------

private fun requiredPermissions(): Array<String> {
    val permissions = ArrayList<String>()
    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    permissions.add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return permissions.toTypedArray()
}

private fun launchSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // no activity handles this settings screen on this device
    }
}
