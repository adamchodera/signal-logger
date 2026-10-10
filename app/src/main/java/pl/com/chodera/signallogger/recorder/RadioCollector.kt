package pl.com.chodera.signallogger.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import pl.com.chodera.signallogger.model.CellSnapshot
import pl.com.chodera.signallogger.model.LocationFix
import pl.com.chodera.signallogger.model.ProbeResult
import pl.com.chodera.signallogger.model.Rat
import pl.com.chodera.signallogger.model.SampleRecord
import pl.com.chodera.signallogger.model.ServiceStateName
import pl.com.chodera.signallogger.model.ServingCellSelector
import pl.com.chodera.signallogger.model.SignalSnapshot
import pl.com.chodera.signallogger.model.SimSample
import pl.com.chodera.signallogger.model.validOrNull
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume

/**
 * Reads, for every active SIM separately, the registration state, serving cell and signal strength.
 * Android telephony calls can throw (revoked permission, vendor quirks): every read degrades to null fields.
 */
class RadioCollector(context: Context) {

    private val appContext: Context = context.applicationContext

    private val telephonyManager: TelephonyManager? = appContext.getSystemService(TelephonyManager::class.java)

    private val threadPool: ExecutorService = Executors.newSingleThreadExecutor()

    // TelephonyManager runs the callback on this executor from a binder thread; an executor that rejects
    // work after close() would crash the process, so late callbacks are silently dropped instead.
    private val callbackExecutor = Executor { task ->
        try {
            threadPool.execute(task)
        } catch (_: RejectedExecutionException) {
            // collector already closed
        }
    }

    fun close() {
        threadPool.shutdown()
    }

    suspend fun collect(location: LocationFix?, probe: CellularProbe?): SampleRecord {
        val epochMs = System.currentTimeMillis()
        val manager = telephonyManager
        val hasPhoneState = isGranted(Manifest.permission.READ_PHONE_STATE)
        if (manager == null || !hasPhoneState) {
            RecorderRepository.update { copy(lastError = "Brak uprawnienia do stanu telefonu - nie widzę kart SIM") }
            return SampleRecord(epochMs, location, emptyList())
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            RecorderRepository.update { copy(lastError = "Brak uprawnienia do dokładnej lokalizacji - brak danych o komórkach") }
        }

        val subscriptions: List<SubscriptionInfo> = readActiveSubscriptions(appContext)
        // The default data SIM is the user's choice; with automatic data switching the SIM that really
        // carries data right now (active data) can be the other one.
        val dataSubId: Int = safe { SubscriptionManager.getDefaultDataSubscriptionId() }
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val activeDataSubId: Int = safe { SubscriptionManager.getActiveDataSubscriptionId() }
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID

        val (sims: List<SimSample>, probeResult: ProbeResult?) = coroutineScope {
            // The probe is the slowest part, so it runs once per sample, concurrently with the radio reads.
            val probeJob: Deferred<ProbeResult>? = probe?.let { p -> async { p.run(p.defaultTimeoutMs) } }
            val simJobs: List<Deferred<SimSample>> = subscriptions.map { sub ->
                async { readSim(manager, sub, dataSubId, activeDataSubId) }
            }
            val readSims: List<SimSample> = simJobs.awaitAll()
            readSims to probeJob?.await()
        }

        val sorted: List<SimSample> = sims.sortedBy { it.slot }
        return SampleRecord(epochMs, location, attachProbe(sorted, probeResult, activeDataSubId, dataSubId))
    }

    /**
     * The probe result goes on the SIM whose network actually carried it; when the platform does not say
     * which, on the active data SIM, and as a last resort on the default data SIM.
     */
    private fun attachProbe(
        sims: List<SimSample>,
        probeResult: ProbeResult?,
        activeDataSubId: Int,
        dataSubId: Int,
    ): List<SimSample> {
        if (probeResult == null) return sims
        val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        // first candidate that is a valid id of a SIM present in this sample, so the result is never dropped
        val targetSubId: Int = listOfNotNull(probeResult.subId, activeDataSubId, dataSubId)
            .firstOrNull { id -> id != invalid && sims.any { it.subId == id } }
            ?: return sims
        return sims.map { sim -> if (sim.subId == targetSubId) sim.copy(probe = probeResult) else sim }
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun readSim(
        base: TelephonyManager,
        sub: SubscriptionInfo,
        dataSubId: Int,
        activeDataSubId: Int,
    ): SimSample {
        val subId = sub.subscriptionId
        val tm: TelephonyManager = safe { base.createForSubscriptionId(subId) } ?: base

        val serviceState: ServiceState? = safe { tm.serviceState }
        val registeredPlmn: String? = safe { tm.networkOperator }?.takeIf { it.isNotBlank() }
        val networkType: String = networkTypeName(safe { tm.dataNetworkType } ?: TelephonyManager.NETWORK_TYPE_UNKNOWN)

        val cells: List<CellInfo> = fetchCells(tm)
        val elapsedNow = SystemClock.elapsedRealtime()
        val snapshots: List<CellSnapshot> = cells.mapNotNull { cell -> safe { toSnapshot(cell, elapsedNow) } }
        val serving: CellSnapshot? = ServingCellSelector.select(snapshots, registeredPlmn)

        return SimSample(
            slot = sub.simSlotIndex,
            subId = subId,
            carrier = carrierName(sub),
            simPlmn = simPlmn(sub),
            isDataSim = subId == dataSubId,
            isActiveData = subId == activeDataSubId,
            serviceState = serviceStateName(serviceState),
            psRegistered = serviceState?.let { packetSwitchedRegistered(it) },
            registeredPlmn = registeredPlmn,
            networkType = networkType,
            serving = serving,
            signal = safe { readSignal(tm) } ?: SignalSnapshot.EMPTY,
            probe = null, // attached afterwards by attachProbe, once we know which SIM carried it
        )
    }

    /** Fresh cell info via requestCellInfoUpdate; falls back to the (possibly cached) allCellInfo. */
    @SuppressLint("MissingPermission")
    private suspend fun fetchCells(tm: TelephonyManager): List<CellInfo> {
        val fresh: List<CellInfo>? = withTimeoutOrNull(CELL_INFO_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<CellInfo>?> { continuation ->
                val callback = object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (continuation.isActive) continuation.resume(cellInfo)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    tm.requestCellInfoUpdate(callbackExecutor, callback)
                } catch (e: Exception) {
                    // SecurityException (no location permission) or vendor failure
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (fresh != null) return fresh
        return safe { tm.allCellInfo } ?: emptyList()
    }

    private fun toSnapshot(info: CellInfo, elapsedNowMs: Long): CellSnapshot {
        val registered = info.isRegistered
        val primary = info.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING
        val timestampMs = info.timestampMillis // elapsedRealtime base
        val ageS: Double? =
            if (timestampMs > 0L) ((elapsedNowMs - timestampMs) / 1000.0).coerceAtLeast(0.0) else null

        return when (info) {
            is CellInfoLte -> {
                val id: CellIdentityLte = info.cellIdentity
                CellSnapshot(
                    rat = Rat.LTE,
                    isRegistered = registered,
                    isPrimaryServing = primary,
                    mcc = id.mccString,
                    mnc = id.mncString,
                    cellId = id.ci.validOrNull()?.toLong(),
                    tac = id.tac.validOrNull(),
                    pci = id.pci.validOrNull(),
                    arfcn = id.earfcn.validOrNull(),
                    bands = id.bands.toList(),
                    ageS = ageS,
                )
            }

            is CellInfoNr -> {
                val id: CellIdentityNr? = info.cellIdentity as? CellIdentityNr
                CellSnapshot(
                    rat = Rat.NR,
                    isRegistered = registered,
                    isPrimaryServing = primary,
                    mcc = id?.mccString,
                    mnc = id?.mncString,
                    cellId = id?.nci?.validOrNull(),
                    tac = id?.tac?.validOrNull(),
                    pci = id?.pci?.validOrNull(),
                    arfcn = id?.nrarfcn?.validOrNull(),
                    bands = id?.bands?.toList() ?: emptyList(),
                    ageS = ageS,
                )
            }

            is CellInfoWcdma -> {
                val id: CellIdentityWcdma = info.cellIdentity
                CellSnapshot(
                    rat = Rat.WCDMA,
                    isRegistered = registered,
                    isPrimaryServing = primary,
                    mcc = id.mccString,
                    mnc = id.mncString,
                    cellId = id.cid.validOrNull()?.toLong(),
                    tac = id.lac.validOrNull(),
                    pci = id.psc.validOrNull(),
                    arfcn = id.uarfcn.validOrNull(),
                    bands = emptyList(),
                    ageS = ageS,
                )
            }

            is CellInfoGsm -> {
                val id: CellIdentityGsm = info.cellIdentity
                CellSnapshot(
                    rat = Rat.GSM,
                    isRegistered = registered,
                    isPrimaryServing = primary,
                    mcc = id.mccString,
                    mnc = id.mncString,
                    cellId = id.cid.validOrNull()?.toLong(),
                    tac = id.lac.validOrNull(),
                    pci = id.bsic.validOrNull(),
                    arfcn = id.arfcn.validOrNull(),
                    bands = emptyList(),
                    ageS = ageS,
                )
            }

            else -> CellSnapshot(
                rat = Rat.OTHER,
                isRegistered = registered,
                isPrimaryServing = primary,
                mcc = null,
                mnc = null,
                cellId = null,
                tac = null,
                pci = null,
                arfcn = null,
                bands = emptyList(),
                ageS = ageS,
            )
        }
    }

    private fun readSignal(tm: TelephonyManager): SignalSnapshot? {
        val strength: SignalStrength = tm.signalStrength ?: return null

        var rsrp: Int? = null
        var rsrq: Int? = null
        var sinr: Int? = null
        var rssi: Int? = null
        var lteDbm: Int? = null
        var nrRsrp: Int? = null
        var nrRsrq: Int? = null
        var nrSinr: Int? = null
        var nrDbm: Int? = null
        var wcdmaDbm: Int? = null
        var gsmDbm: Int? = null

        for (css in strength.cellSignalStrengths) {
            when (css) {
                is CellSignalStrengthLte -> {
                    rsrp = css.rsrp.validOrNull()
                    rsrq = css.rsrq.validOrNull()
                    sinr = css.rssnr.validOrNull()
                    rssi = css.rssi.validOrNull()
                    lteDbm = css.dbm.validOrNull()
                }

                is CellSignalStrengthNr -> {
                    nrRsrp = css.ssRsrp.validOrNull()
                    nrRsrq = css.ssRsrq.validOrNull()
                    nrSinr = css.ssSinr.validOrNull()
                    nrDbm = css.dbm.validOrNull()
                }

                is CellSignalStrengthWcdma -> wcdmaDbm = css.dbm.validOrNull()
                is CellSignalStrengthGsm -> gsmDbm = css.dbm.validOrNull()
                else -> Unit
            }
        }

        return SignalSnapshot(
            level = strength.level,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            rssi = rssi,
            // NR dbm only counts when there is no LTE leg (SA); in NSA the LTE anchor is the primary RAT
            dbm = lteDbm ?: nrDbm ?: wcdmaDbm ?: gsmDbm,
            nrRsrp = nrRsrp,
            nrRsrq = nrRsrq,
            nrSinr = nrSinr,
        )
    }

    // isRegistered() is deprecated only at API 35 (replacement isNetworkRegistered() needs API 35); minSdk is 30.
    @Suppress("DEPRECATION")
    private fun packetSwitchedRegistered(serviceState: ServiceState): Boolean? = safe {
        serviceState.networkRegistrationInfoList.any { info ->
            (info.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0 &&
                info.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                info.isRegistered
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CELL_INFO_TIMEOUT_MS = 2500L
    }
}

// -------------------------------------------------------------------------------------------------
// Helpers shared with the ViewModel
// -------------------------------------------------------------------------------------------------

/** Active SIMs; empty when READ_PHONE_STATE is missing. Blocking IPC - call off the main thread. */
@SuppressLint("MissingPermission")
internal fun readActiveSubscriptions(context: Context): List<SubscriptionInfo> {
    val manager: SubscriptionManager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
    return try {
        manager.activeSubscriptionInfoList ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}

internal fun carrierName(sub: SubscriptionInfo): String =
    sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
        ?: sub.displayName?.toString()?.takeIf { it.isNotBlank() }
        ?: "SIM${sub.simSlotIndex + 1}"

internal fun simPlmn(sub: SubscriptionInfo): String? =
    ((sub.mccString ?: "") + (sub.mncString ?: "")).takeIf { it.isNotBlank() }

internal fun serviceStateName(serviceState: ServiceState?): String = when (serviceState?.state) {
    ServiceState.STATE_IN_SERVICE -> ServiceStateName.IN_SERVICE
    ServiceState.STATE_OUT_OF_SERVICE -> ServiceStateName.OUT_OF_SERVICE
    ServiceState.STATE_EMERGENCY_ONLY -> ServiceStateName.EMERGENCY_ONLY
    ServiceState.STATE_POWER_OFF -> ServiceStateName.POWER_OFF
    else -> ServiceStateName.UNKNOWN
}

internal fun networkTypeName(type: Int): String = when (type) {
    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_NR -> "NR"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
    else -> type.toString()
}

/** Runs [block]; any failure (SecurityException, vendor quirks) becomes null. Cancellation is rethrown. */
private inline fun <T> safe(block: () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
