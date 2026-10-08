package pl.com.chodera.signallogger.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayHelpersTest {

    private fun sim(
        carrier: String,
        serviceState: String = ServiceStateName.IN_SERVICE,
        networkType: String = "LTE",
        signal: SignalSnapshot = SignalSnapshot.EMPTY,
        serving: CellSnapshot? = null,
    ) = SimSample(
        slot = 0, subId = 1, carrier = carrier, simPlmn = null, isDataSim = false,
        serviceState = serviceState, psRegistered = null, registeredPlmn = null,
        networkType = networkType, serving = serving, signal = signal, probe = null,
    )

    @Test
    fun summaryLineShowsTechnologyAndDbmWhenInService() {
        val signal = SignalSnapshot.EMPTY.copy(rsrp = -97)

        assertEquals("Orange LTE -97 dBm", sim("Orange", signal = signal).summaryLine())
    }

    @Test
    fun summaryLineFallsBackToNrAndGenericDbm() {
        assertEquals("Orange LTE -101 dBm", sim("Orange", signal = SignalSnapshot.EMPTY.copy(nrRsrp = -101)).summaryLine())
        assertEquals("Orange LTE -90 dBm", sim("Orange", signal = SignalSnapshot.EMPTY.copy(dbm = -90)).summaryLine())
        assertEquals("Orange LTE", sim("Orange").summaryLine())
    }

    @Test
    fun summaryLineSaysNoServiceWhenOutOfService() {
        assertEquals("Play brak usługi", sim("Play", serviceState = ServiceStateName.OUT_OF_SERVICE).summaryLine())
        assertEquals("Play tylko alarmowe", sim("Play", serviceState = ServiceStateName.EMERGENCY_ONLY).summaryLine())
    }

    @Test
    fun recordSummaryJoinsSimsAndHandlesNoSim() {
        val record = SampleRecord(
            0, null,
            listOf(
                sim("Orange", signal = SignalSnapshot.EMPTY.copy(rsrp = -97)),
                sim("Play", serviceState = ServiceStateName.OUT_OF_SERVICE),
            ),
        )

        assertEquals("Orange LTE -97 dBm · Play brak usługi", record.summaryLine())
        assertEquals("Brak aktywnych kart SIM", SampleRecord(0, null, emptyList()).summaryLine())
    }

    @Test
    fun techLabelPrefersNetworkTypeThenServingRat() {
        val lteCell = CellSnapshot(Rat.LTE, true, true, "260", "03", 1, 1, 1, 1, emptyList(), 0.0)

        assertEquals("HSPAP", sim("A", networkType = "HSPAP", serving = lteCell).techLabel)
        assertEquals("LTE", sim("A", networkType = "UNKNOWN", serving = lteCell).techLabel)
        assertEquals("?", sim("A", networkType = "UNKNOWN").techLabel)
    }

    @Test
    fun serviceStateLabelsArePolish() {
        assertEquals("W zasięgu", serviceStateLabelPl(ServiceStateName.IN_SERVICE))
        assertEquals("Brak usługi", serviceStateLabelPl(ServiceStateName.OUT_OF_SERVICE))
        assertEquals("Tylko alarmowe", serviceStateLabelPl(ServiceStateName.EMERGENCY_ONLY))
        assertEquals("Wyłączona", serviceStateLabelPl(ServiceStateName.POWER_OFF))
        assertEquals("Brak danych", serviceStateLabelPl(ServiceStateName.UNKNOWN))
    }
}
