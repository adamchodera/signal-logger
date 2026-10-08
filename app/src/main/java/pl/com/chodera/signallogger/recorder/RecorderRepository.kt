package pl.com.chodera.signallogger.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.com.chodera.signallogger.model.SampleRecord

data class RecorderState(
    val isRecording: Boolean = false,
    val fileName: String? = null,
    val startedAtMs: Long? = null,
    val sampleCount: Int = 0,
    val markerCount: Int = 0,
    val last: SampleRecord? = null,
    val gpsProviderEnabled: Boolean = true,
    val lastError: String? = null,
)

/** In-process state shared between [RecorderService] (writer) and the UI (reader). */
object RecorderRepository {
    private val mutableState = MutableStateFlow(RecorderState())

    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    internal fun update(transform: RecorderState.() -> RecorderState) {
        mutableState.update { current -> current.transform() }
    }
}
