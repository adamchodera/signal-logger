package pl.com.chodera.signallogger.ui

import android.content.Intent
import pl.com.chodera.signallogger.recorder.RecorderState
import pl.com.chodera.signallogger.recorder.SessionFile

data class UiState(
    val permissionsGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val gpsEnabled: Boolean = true,
    val detectedSims: List<String> = emptyList(),   // "Slot 1: Orange", "Slot 2: Play"
    val intervalMs: Long = 5000,
    val probeEnabled: Boolean = true,
    val recorder: RecorderState = RecorderState(),
    val sessions: List<SessionFile> = emptyList(),
    val pendingDelete: String? = null,
)

sealed interface UiIntent {
    data object Start : UiIntent
    data object Stop : UiIntent
    data object Mark : UiIntent
    data class SetInterval(val ms: Long) : UiIntent
    data class SetProbe(val on: Boolean) : UiIntent
    data class Share(val name: String) : UiIntent
    data class AskDelete(val name: String) : UiIntent
    data object ConfirmDelete : UiIntent
    data object CancelDelete : UiIntent
    data object Refresh : UiIntent
    data object PermissionsChanged : UiIntent
}

sealed interface UiEffect {
    data class LaunchIntent(val intent: Intent) : UiEffect
}
