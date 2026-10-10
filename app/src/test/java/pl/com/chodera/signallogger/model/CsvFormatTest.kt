package pl.com.chodera.signallogger.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class CsvFormatTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private val expectedHeader =
        "timestamp_iso,epoch_ms,event,lat,lon,gps_acc_m,gps_age_s,speed_kmh,sim_slot,sub_id,carrier," +
            "sim_plmn,is_data_sim,is_active_data,service_state,ps_registered,reg_plmn,network_type,rat,cell_id,tac,pci," +
            "arfcn,bands,cell_age_s,level,rsrp,rsrq,sinr,rssi,dbm,nr_rsrp,nr_rsrq,nr_sinr,probe_ok," +
            "probe_ms,note"

    // ---- helpers -------------------------------------------------------------------------------

    /** Tiny RFC 4180 splitter: commas inside quotes do not separate fields, "" is an escaped quote. */
    private fun splitCsv(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun List<String>.col(name: String): String {
        val index = CsvFormat.COLUMNS.indexOf(name)
        assertTrue("unknown column $name", index >= 0)
        return this[index]
    }

    private val fullCell = CellSnapshot(
        rat = Rat.LTE,
        isRegistered = true,
        isPrimaryServing = true,
        mcc = "260",
        mnc = "03",
        cellId = 123456789L,
        tac = 4321,
        pci = 321,
        arfcn = 1300,
        bands = listOf(3, 20),
        ageS = 0.26,
    )

    private val fullSignal = SignalSnapshot(
        level = 3,
        rsrp = -97,
        rsrq = -11,
        sinr = 12,
        rssi = -70,
        dbm = -97,
        nrRsrp = -101,
        nrRsrq = -12,
        nrSinr = 8,
    )

    private fun sim(
        slot: Int = 0,
        carrier: String = "Orange",
        isData: Boolean = true,
        isActive: Boolean = isData,
        serving: CellSnapshot? = fullCell,
        signal: SignalSnapshot = fullSignal,
        probe: ProbeResult? = ProbeResult(true, 123),
    ) = SimSample(
        slot = slot,
        subId = slot + 1,
        carrier = carrier,
        simPlmn = "26003",
        isDataSim = isData,
        isActiveData = isActive,
        serviceState = "IN_SERVICE",
        psRegistered = true,
        registeredPlmn = "26003",
        networkType = "LTE",
        serving = serving,
        signal = signal,
        probe = probe,
    )

    private val fix = LocationFix(lat = 54.352025, lon = 18.646639, accuracyM = 3.14159f, ageS = 0.04, speedKmh = 123.456f)

    // ---- header / column count -----------------------------------------------------------------

    @Test
    fun headerMatchesTheContract() {
        assertEquals(expectedHeader, CsvFormat.HEADER)
        assertEquals(37, CsvFormat.COLUMNS.size)
        assertEquals(37, splitCsv(CsvFormat.HEADER).size)
        assertEquals(CsvFormat.COLUMNS.indexOf("is_data_sim") + 1, CsvFormat.COLUMNS.indexOf("is_active_data"))
    }

    @Test
    fun everyRowBuilderProducesAsManyColumnsAsTheHeader() {
        val headerColumns = splitCsv(CsvFormat.HEADER).size
        val empty = SimSample(
            slot = 1, subId = 2, carrier = "", simPlmn = null, isDataSim = false, isActiveData = false, serviceState = "UNKNOWN",
            psRegistered = null, registeredPlmn = null, networkType = "UNKNOWN", serving = null,
            signal = SignalSnapshot.EMPTY, probe = null,
        )
        val lines = buildList {
            addAll(CsvFormat.rows(SampleRecord(1_700_000_000_123, fix, listOf(sim(0), sim(1, "Play", false, probe = null))), utc))
            addAll(CsvFormat.rows(SampleRecord(1_700_000_000_123, null, listOf(empty)), utc))
            addAll(CsvFormat.rows(SampleRecord(1_700_000_000_123, fix, emptyList()), utc))
            add(CsvFormat.markerRow(1_700_000_000_123, fix, "brak internetu", utc))
            add(CsvFormat.markerRow(1_700_000_000_123, null, "a, \"b\"\nc", utc))
        }

        assertEquals(6, lines.size)
        lines.forEach { line ->
            assertEquals("line: $line", headerColumns, splitCsv(line).size)
        }
    }

    // ---- rows ----------------------------------------------------------------------------------

    @Test
    fun oneLinePerSimWithSampleEvent() {
        val rows = CsvFormat.rows(
            SampleRecord(1_700_000_000_123, fix, listOf(sim(0), sim(1, "Play", false, probe = null))),
            utc,
        )

        assertEquals(2, rows.size)
        val first = splitCsv(rows[0])
        val second = splitCsv(rows[1])
        assertEquals("SAMPLE", first.col("event"))
        assertEquals("SAMPLE", second.col("event"))
        assertEquals("0", first.col("sim_slot"))
        assertEquals("1", second.col("sim_slot"))
        assertEquals("Play", second.col("carrier"))
        assertEquals("1", first.col("is_data_sim"))
        assertEquals("0", second.col("is_data_sim"))
        assertEquals("1", first.col("is_active_data"))
        assertEquals("0", second.col("is_active_data"))
    }

    @Test
    fun defaultDataSimAndActiveDataSimAreIndependentColumns() {
        // Automatic data switching: SIM 1 is the user's default, but SIM 2 currently carries the data.
        val rows = CsvFormat.rows(
            SampleRecord(
                1_700_000_000_123,
                fix,
                listOf(
                    sim(0, "Orange", isData = true, isActive = false, probe = null),
                    sim(1, "Play", isData = false, isActive = true),
                ),
            ),
            utc,
        )

        val first = splitCsv(rows[0])
        val second = splitCsv(rows[1])
        assertEquals("1", first.col("is_data_sim"))
        assertEquals("0", first.col("is_active_data"))
        assertEquals("", first.col("probe_ok"))
        assertEquals("0", second.col("is_data_sim"))
        assertEquals("1", second.col("is_active_data"))
        assertEquals("1", second.col("probe_ok"))
        assertEquals("123", second.col("probe_ms"))
    }

    @Test
    fun fullRowContainsAllValuesInContractOrder() {
        val row = splitCsv(CsvFormat.rows(SampleRecord(1_700_000_000_123, fix, listOf(sim())), utc).single())

        assertEquals("2023-11-14T22:13:20.123Z", row.col("timestamp_iso"))
        assertEquals("1700000000123", row.col("epoch_ms"))
        assertEquals("54.352025", row.col("lat"))
        assertEquals("18.646639", row.col("lon"))
        assertEquals("3.1", row.col("gps_acc_m"))
        assertEquals("0.0", row.col("gps_age_s"))
        assertEquals("123.5", row.col("speed_kmh"))
        assertEquals("1", row.col("sub_id"))
        assertEquals("26003", row.col("sim_plmn"))
        assertEquals("1", row.col("is_data_sim"))
        assertEquals("1", row.col("is_active_data"))
        assertEquals("IN_SERVICE", row.col("service_state"))
        assertEquals("1", row.col("ps_registered"))
        assertEquals("26003", row.col("reg_plmn"))
        assertEquals("LTE", row.col("network_type"))
        assertEquals("LTE", row.col("rat"))
        assertEquals("123456789", row.col("cell_id"))
        assertEquals("4321", row.col("tac"))
        assertEquals("321", row.col("pci"))
        assertEquals("1300", row.col("arfcn"))
        assertEquals("3;20", row.col("bands"))
        assertEquals("0.3", row.col("cell_age_s"))
        assertEquals("3", row.col("level"))
        assertEquals("-97", row.col("rsrp"))
        assertEquals("-11", row.col("rsrq"))
        assertEquals("12", row.col("sinr"))
        assertEquals("-70", row.col("rssi"))
        assertEquals("-97", row.col("dbm"))
        assertEquals("-101", row.col("nr_rsrp"))
        assertEquals("-12", row.col("nr_rsrq"))
        assertEquals("8", row.col("nr_sinr"))
        assertEquals("1", row.col("probe_ok"))
        assertEquals("123", row.col("probe_ms"))
        assertEquals("", row.col("note"))
    }

    @Test
    fun failedProbeIsZeroWithEmptyMilliseconds() {
        val row = splitCsv(CsvFormat.rows(SampleRecord(0, fix, listOf(sim(probe = ProbeResult(false, null)))), utc).single())

        assertEquals("0", row.col("probe_ok"))
        assertEquals("", row.col("probe_ms"))
    }

    @Test
    fun probeSubIdIsNotWrittenAsAnExtraColumn() {
        val line = CsvFormat.rows(SampleRecord(0, fix, listOf(sim(probe = ProbeResult(true, 87, subId = 2)))), utc).single()
        val row = splitCsv(line)

        assertEquals(CsvFormat.COLUMN_COUNT, row.size)
        assertEquals("1", row.col("probe_ok"))
        assertEquals("87", row.col("probe_ms"))
    }

    @Test
    fun nullValuesBecomeEmptyFields() {
        val bare = sim(serving = null, signal = SignalSnapshot.EMPTY, probe = null).copy(
            simPlmn = null, psRegistered = null, registeredPlmn = null,
        )
        val row = splitCsv(CsvFormat.rows(SampleRecord(1_700_000_000_123, null, listOf(bare)), utc).single())

        listOf(
            "lat", "lon", "gps_acc_m", "gps_age_s", "speed_kmh", "sim_plmn", "ps_registered", "reg_plmn",
            "rat", "cell_id", "tac", "pci", "arfcn", "bands", "cell_age_s", "level", "rsrp", "rsrq", "sinr",
            "rssi", "dbm", "nr_rsrp", "nr_rsrq", "nr_sinr", "probe_ok", "probe_ms", "note",
        ).forEach { assertEquals("column $it", "", row.col(it)) }
        // non-null values are still there
        assertEquals("Orange", row.col("carrier"))
        assertEquals("LTE", row.col("network_type"))
    }

    @Test
    fun emptyBandListIsAnEmptyField() {
        val row = splitCsv(CsvFormat.rows(SampleRecord(0, fix, listOf(sim(serving = fullCell.copy(bands = emptyList())))), utc).single())

        assertEquals("", row.col("bands"))
    }

    @Test
    fun noSimRowIsEmittedWhenThereAreNoSims() {
        val rows = CsvFormat.rows(SampleRecord(1_700_000_000_123, fix, emptyList()), utc)

        assertEquals(1, rows.size)
        val row = splitCsv(rows.single())
        assertEquals("NO_SIM", row.col("event"))
        assertEquals("54.352025", row.col("lat"))
        assertEquals("18.646639", row.col("lon"))
        assertEquals("1700000000123", row.col("epoch_ms"))
        val firstSimColumn = CsvFormat.COLUMNS.indexOf("sim_slot")
        val noteColumn = CsvFormat.COLUMNS.indexOf("note")
        for (i in firstSimColumn..noteColumn) {
            assertEquals("column ${CsvFormat.COLUMNS[i]}", "", row[i])
        }
    }

    @Test
    fun markerRowHasNoteAndEmptySimColumns() {
        val row = splitCsv(CsvFormat.markerRow(1_700_000_000_123, fix, "brak internetu", utc))

        assertEquals("MARK", row.col("event"))
        assertEquals("brak internetu", row.col("note"))
        assertEquals("54.352025", row.col("lat"))
        val firstSimColumn = CsvFormat.COLUMNS.indexOf("sim_slot")
        val lastSimColumn = CsvFormat.COLUMNS.indexOf("probe_ms")
        for (i in firstSimColumn..lastSimColumn) {
            assertEquals("column ${CsvFormat.COLUMNS[i]}", "", row[i])
        }
    }

    @Test
    fun markerWithoutLocationHasEmptyLocationColumns() {
        val row = splitCsv(CsvFormat.markerRow(1_700_000_000_123, null, "x", utc))

        assertEquals("", row.col("lat"))
        assertEquals("", row.col("lon"))
        assertEquals("x", row.col("note"))
    }

    // ---- escaping ------------------------------------------------------------------------------

    @Test
    fun escapeQuotesFieldsWithSpecialCharacters() {
        assertEquals("plain", CsvFormat.escape("plain"))
        assertEquals("", CsvFormat.escape(""))
        assertEquals("\"Play, \"\"test\"\"\"", CsvFormat.escape("Play, \"test\""))
        assertEquals("\"a\nb\"", CsvFormat.escape("a\nb"))
        assertEquals("\"a\rb\"", CsvFormat.escape("a\rb"))
        assertEquals("\"say \"\"hi\"\"\"", CsvFormat.escape("say \"hi\""))
    }

    @Test
    fun carrierNameWithCommaAndQuotesSurvivesRoundTrip() {
        val name = "Play, \"test\""
        val line = CsvFormat.rows(SampleRecord(0, fix, listOf(sim(carrier = name))), utc).single()

        assertTrue(line.contains("\"Play, \"\"test\"\"\""))
        assertEquals(name, splitCsv(line).col("carrier"))
        assertEquals(CsvFormat.COLUMN_COUNT, splitCsv(line).size)
    }

    @Test
    fun noteWithNewlineStaysInsideOneQuotedField() {
        val line = CsvFormat.markerRow(0, null, "line1\nline2, \"q\"", utc)

        assertEquals("line1\nline2, \"q\"", splitCsv(line).col("note"))
        assertEquals(CsvFormat.COLUMN_COUNT, splitCsv(line).size)
    }

    // ---- locale / formatting -------------------------------------------------------------------

    @Test
    fun decimalSeparatorIsADotRegardlessOfDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = splitCsv(CsvFormat.rows(SampleRecord(0, fix, listOf(sim())), utc).single())

            assertEquals("54.352025", row.col("lat"))
            assertEquals("3.1", row.col("gps_acc_m"))
            assertEquals("123.5", row.col("speed_kmh"))
            assertEquals("0.3", row.col("cell_age_s"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun timestampUsesTheGivenZoneOffset() {
        assertEquals("2023-11-14T23:13:20.123+01:00", CsvFormat.formatTimestamp(1_700_000_000_123, ZoneId.of("Europe/Warsaw")))
        assertEquals("2023-11-14T22:13:20.123Z", CsvFormat.formatTimestamp(1_700_000_000_123, utc))
    }

    @Test
    fun millisecondsKeepTheirLeadingZeros() {
        assertEquals("2023-11-14T22:13:20.007Z", CsvFormat.formatTimestamp(1_700_000_000_007, utc))
    }

    @Test
    fun timestampTruncatesToMillisecondsAndSkipsZeroFraction() {
        // ISO_OFFSET_DATE_TIME omits the fraction when it is zero; epoch_ms is the canonical time column.
        assertEquals("2023-11-14T22:13:20Z", CsvFormat.formatTimestamp(1_700_000_000_000, utc))
    }

    // ---- validOrNull ---------------------------------------------------------------------------

    @Test
    fun validOrNullTurnsUnavailableIntoNull() {
        assertNull(Int.MAX_VALUE.validOrNull())
        assertNull(UNAVAILABLE_INT.validOrNull())
        assertEquals(-97, (-97).validOrNull())
        assertEquals(0, 0.validOrNull())
    }

    @Test
    fun validOrNullTurnsUnavailableLongIntoNull() {
        assertNull(Long.MAX_VALUE.validOrNull())
        assertNull(UNAVAILABLE_LONG.validOrNull())
        assertEquals(123456789012L, 123456789012L.validOrNull())
    }
}
