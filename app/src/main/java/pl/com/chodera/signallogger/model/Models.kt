package pl.com.chodera.signallogger.model

// NOTE: this package must stay pure Kotlin/JVM (no Android framework imports) so it can be unit-tested on the JVM.

/** Same value as CellInfo.UNAVAILABLE in the telephony API. */
const val UNAVAILABLE_INT: Int = Int.MAX_VALUE

/** Same value as CellInfo.UNAVAILABLE_LONG in the telephony API. */
const val UNAVAILABLE_LONG: Long = Long.MAX_VALUE

fun Int.validOrNull(): Int? = takeIf { it != UNAVAILABLE_INT }

fun Long.validOrNull(): Long? = takeIf { it != UNAVAILABLE_LONG }

enum class Rat { GSM, WCDMA, LTE, NR, OTHER }

/** Names used in the `service_state` CSV column. */
object ServiceStateName {
    const val IN_SERVICE = "IN_SERVICE"
    const val OUT_OF_SERVICE = "OUT_OF_SERVICE"
    const val EMERGENCY_ONLY = "EMERGENCY_ONLY"
    const val POWER_OFF = "POWER_OFF"
    const val UNKNOWN = "UNKNOWN"
}

data class CellSnapshot(
    val rat: Rat,
    val isRegistered: Boolean,
    val isPrimaryServing: Boolean,   // cellConnectionStatus == CELL_CONNECTION_PRIMARY_SERVING
    val mcc: String?,
    val mnc: String?,
    val cellId: Long?,               // LTE ci, NR nci, WCDMA/GSM cid
    val tac: Int?,                   // LTE/NR tac, WCDMA/GSM lac
    val pci: Int?,                   // LTE/NR pci, WCDMA psc, GSM bsic
    val arfcn: Int?,                 // earfcn / nrarfcn / uarfcn / arfcn
    val bands: List<Int>,
    val ageS: Double?,               // from CellInfo.timestampMillis vs SystemClock.elapsedRealtime()
) {
    val plmn: String? get() = if (mcc != null && mnc != null) mcc + mnc else null
}

data class SignalSnapshot(
    val level: Int?,                 // SignalStrength.level 0..4
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,                  // LTE rssnr
    val rssi: Int?,                  // LTE
    val dbm: Int?,                   // dbm of the primary measured RAT (LTE/WCDMA/GSM/NR)
    val nrRsrp: Int?,
    val nrRsrq: Int?,
    val nrSinr: Int?,                // NR ss* values (SA or NSA leg)
) {
    companion object {
        val EMPTY = SignalSnapshot(null, null, null, null, null, null, null, null, null)
    }
}

/** [subId] = subscription id of the cellular network the probe actually used, when known. */
data class ProbeResult(val ok: Boolean, val ms: Long?, val subId: Int? = null)

data class SimSample(
    val slot: Int,
    val subId: Int,
    val carrier: String,
    val simPlmn: String?,
    val isDataSim: Boolean,
    val isActiveData: Boolean,
    val serviceState: String,        // IN_SERVICE / OUT_OF_SERVICE / EMERGENCY_ONLY / POWER_OFF / UNKNOWN
    val psRegistered: Boolean?,      // packet-switched (data) registration on WWAN
    val registeredPlmn: String?,     // TelephonyManager.networkOperator for that sub, "" -> null
    val networkType: String,         // dataNetworkType name: LTE, NR, HSPAP, UMTS, EDGE, GPRS, UNKNOWN...
    val serving: CellSnapshot?,
    val signal: SignalSnapshot,
    val probe: ProbeResult?,         // only on the SIM whose network carried the probe (falls back to the active data SIM), null otherwise
)

data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val ageS: Double,
    val speedKmh: Float?,
)

data class SampleRecord(val epochMs: Long, val location: LocationFix?, val sims: List<SimSample>)

// ---------------------------------------------------------------------------------------------
// Small display helpers shared by the notification and the UI (kept here so they stay testable).
// ---------------------------------------------------------------------------------------------

/** Best single dBm figure for display: LTE RSRP, else NR SS-RSRP, else the generic dBm. */
val SignalSnapshot.bestDbm: Int? get() = rsrp ?: nrRsrp ?: dbm

/** Short technology label: data network type if known, otherwise the serving cell RAT. */
val SimSample.techLabel: String
    get() {
        if (networkType != "UNKNOWN") return networkType
        val rat = serving?.rat
        return if (rat != null && rat != Rat.OTHER) rat.name else "?"
    }

/** Polish label of the `service_state` value. */
fun serviceStateLabelPl(serviceState: String): String = when (serviceState) {
    ServiceStateName.IN_SERVICE -> "W zasięgu"
    ServiceStateName.OUT_OF_SERVICE -> "Brak usługi"
    ServiceStateName.EMERGENCY_ONLY -> "Tylko alarmowe"
    ServiceStateName.POWER_OFF -> "Wyłączona"
    else -> "Brak danych"
}

/** One-line description for the notification, e.g. "Orange LTE -97 dBm" or "Play brak usługi". */
fun SimSample.summaryLine(): String {
    if (serviceState != ServiceStateName.IN_SERVICE) {
        return "$carrier ${serviceStateLabelPl(serviceState).lowercase()}"
    }
    val strength = signal.bestDbm?.let { " $it dBm" }.orEmpty()
    return "$carrier $techLabel$strength"
}

/** All SIMs joined into one line; a hint when no SIM is active. */
fun SampleRecord.summaryLine(): String =
    if (sims.isEmpty()) "Brak aktywnych kart SIM" else sims.joinToString(" · ") { it.summaryLine() }
