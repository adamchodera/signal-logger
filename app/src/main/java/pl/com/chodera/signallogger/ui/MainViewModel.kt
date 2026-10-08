package pl.com.chodera.signallogger.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.com.chodera.signallogger.recorder.RecorderRepository
import pl.com.chodera.signallogger.recorder.RecorderService
import pl.com.chodera.signallogger.recorder.SessionFile
import pl.com.chodera.signallogger.recorder.SessionFiles
import pl.com.chodera.signallogger.recorder.carrierName
import pl.com.chodera.signallogger.recorder.readActiveSubscriptions

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val sessionFiles = SessionFiles(app)

    // Everything the UI owns; the recorder part is merged in from RecorderRepository.
    private val local = MutableStateFlow(
        UiState(
            permissionsGranted = hasCorePermissions(),
            notificationsGranted = hasNotificationPermission(),
            gpsEnabled = isGpsEnabled(),
        ),
    )

    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<UiState> = combine(local, RecorderRepository.state) { ui, recorder ->
        ui.copy(recorder = recorder)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = local.value.copy(recorder = RecorderRepository.state.value),
    )

    init {
        // When recording stops the new file must show up in the list.
        viewModelScope.launch {
            var wasRecording = RecorderRepository.state.value.isRecording
            RecorderRepository.state.collect { recorder ->
                if (wasRecording && !recorder.isRecording) refresh()
                wasRecording = recorder.isRecording
            }
        }
        refresh()
    }

    fun onIntent(intent: UiIntent) {
        when (intent) {
            UiIntent.Start -> start()
            UiIntent.Stop -> stop()
            UiIntent.Mark -> mark()
            is UiIntent.SetInterval -> if (!isRecording()) local.update { it.copy(intervalMs = intent.ms) }
            is UiIntent.SetProbe -> if (!isRecording()) local.update { it.copy(probeEnabled = intent.on) }
            is UiIntent.Share -> share(intent.name)
            is UiIntent.AskDelete -> local.update { it.copy(pendingDelete = intent.name) }
            UiIntent.ConfirmDelete -> confirmDelete()
            UiIntent.CancelDelete -> local.update { it.copy(pendingDelete = null) }
            UiIntent.Refresh -> refresh()
            UiIntent.PermissionsChanged -> refresh()
        }
    }

    // ---- intents -------------------------------------------------------------------------------

    private fun start() {
        if (isRecording()) return
        if (!hasCorePermissions()) {
            refresh()
            return
        }
        val current = local.value
        try {
            RecorderService.start(getApplication(), current.intervalMs, current.probeEnabled)
        } catch (e: Exception) {
            reportError("Nie udało się uruchomić nagrywania: ${e.message}")
        }
    }

    private fun stop() {
        try {
            RecorderService.stop(getApplication())
        } catch (e: Exception) {
            reportError("Nie udało się zatrzymać nagrywania: ${e.message}")
        }
    }

    private fun mark() {
        if (!isRecording()) return
        try {
            RecorderService.mark(getApplication(), RecorderService.DEFAULT_NOTE)
        } catch (e: Exception) {
            reportError("Nie udało się dodać znacznika: ${e.message}")
        }
    }

    private fun share(name: String) {
        try {
            effectChannel.trySend(UiEffect.LaunchIntent(sessionFiles.shareIntent(name)))
        } catch (_: IllegalArgumentException) {
            refresh() // the file is gone or not shareable
        }
    }

    private fun confirmDelete() {
        val name = local.value.pendingDelete ?: return
        local.update { it.copy(pendingDelete = null) }
        val recorder = RecorderRepository.state.value
        if (recorder.isRecording && recorder.fileName == name) return // never delete the file being written
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sessionFiles.delete(name) }
            refresh()
        }
    }

    // ---- state refresh -------------------------------------------------------------------------

    private fun refresh() {
        val permissions = hasCorePermissions()
        local.update {
            it.copy(
                permissionsGranted = permissions,
                notificationsGranted = hasNotificationPermission(),
                gpsEnabled = isGpsEnabled(),
            )
        }
        viewModelScope.launch {
            val (sims, sessions) = withContext(Dispatchers.IO) { readSimsAndSessions(permissions) }
            local.update { it.copy(detectedSims = sims, sessions = sessions) }
        }
    }

    private fun readSimsAndSessions(permissionsGranted: Boolean): Pair<List<String>, List<SessionFile>> {
        val sims: List<String> = if (permissionsGranted) {
            try {
                readActiveSubscriptions(getApplication())
                    .sortedBy { it.simSlotIndex }
                    .map { "Slot ${it.simSlotIndex + 1}: ${carrierName(it)}" }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return sims to sessionFiles.list()
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun isRecording(): Boolean = RecorderRepository.state.value.isRecording

    private fun reportError(message: String) {
        RecorderRepository.update { copy(lastError = message) }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication<Application>(), permission) == PackageManager.PERMISSION_GRANTED

    private fun hasCorePermissions(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) && isGranted(Manifest.permission.READ_PHONE_STATE)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(Manifest.permission.POST_NOTIFICATIONS)

    private fun isGpsEnabled(): Boolean {
        val manager: LocationManager = getApplication<Application>().getSystemService(LocationManager::class.java)
            ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            false
        }
    }
}
