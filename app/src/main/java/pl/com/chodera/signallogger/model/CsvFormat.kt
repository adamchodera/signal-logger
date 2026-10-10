package pl.com.chodera.signallogger.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * CSV serialisation of [SampleRecord]s. The column order is a contract with the analysis scripts.
 *
 * Every row has exactly [COLUMN_COUNT] fields: 3 (time + event) + 5 (location) + 28 (SIM) + 1 (note).
 */
object CsvFormat {

    const val HEADER: String =
        "timestamp_iso,epoch_ms,event,lat,lon,gps_acc_m,gps_age_s,speed_kmh," +
            "sim_slot,sub_id,carrier,sim_plmn,is_data_sim,is_active_data,service_state,ps_registered,reg_plmn," +
            "network_type,rat,cell_id,tac,pci,arfcn,bands,cell_age_s," +
            "level,rsrp,rsrq,sinr,rssi,dbm,nr_rsrp,nr_rsrq,nr_sinr,probe_ok,probe_ms,note"

    val COLUMNS: List<String> = HEADER.split(',')
    val COLUMN_COUNT: Int = COLUMNS.size

    const val EVENT_SAMPLE = "SAMPLE"
    const val EVENT_NO_SIM = "NO_SIM"
    const val EVENT_MARK = "MARK"

    private const val LOCATION_COLUMNS = 5
    private const val SIM_COLUMNS = 28

    /** One line per SIM (event SAMPLE); a single NO_SIM line when no SIM is active. */
    fun rows(record: SampleRecord, zone: ZoneId): List<String> {
        if (record.sims.isEmpty()) {
            return listOf(buildLine(record.epochMs, EVENT_NO_SIM, record.location, null, null, zone))
        }
        return record.sims.map { sim ->
            buildLine(record.epochMs, EVENT_SAMPLE, record.location, sim, null, zone)
        }
    }

    /** User marker (e.g. "no internet"); SIM columns stay empty. */
    fun markerRow(epochMs: Long, location: LocationFix?, note: String, zone: ZoneId): String =
        buildLine(epochMs, EVENT_MARK, location, null, note, zone)

    fun formatTimestamp(epochMs: Long, zone: ZoneId): String {
        val odt = Instant.ofEpochMilli(epochMs).atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.MILLIS)
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt)
    }

    /** RFC 4180 escaping: quote if the field contains a comma, a quote, CR or LF; double inner quotes. */
    fun escape(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuoting) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }

    // -----------------------------------------------------------------------------------------

    private fun buildLine(
        epochMs: Long,
        event: String,
        location: LocationFix?,
        sim: SimSample?,
        note: String?,
        zone: ZoneId,
    ): String {
        val fields = ArrayList<String?>(COLUMN_COUNT)
        fields.add(formatTimestamp(epochMs, zone))          // timestamp_iso
        fields.add(epochMs.toString())                      // epoch_ms
        fields.add(event)                                   // event
        fields.addAll(locationFields(location))             // lat .. speed_kmh (5)
        fields.addAll(if (sim != null) simFields(sim) else List(SIM_COLUMNS) { null })
        fields.add(note)                                    // note
        check(fields.size == COLUMN_COUNT) { "CSV row has ${fields.size} fields, expected $COLUMN_COUNT" }
        return fields.joinToString(",") { escape(it.orEmpty()) }
    }

    private fun locationFields(loc: LocationFix?): List<String?> {
        val fields: List<String?> = listOf(
            loc?.let { number(it.lat, 6) },                         // lat
            loc?.let { number(it.lon, 6) },                         // lon
            loc?.accuracyM?.let { number(it.toDouble(), 1) },       // gps_acc_m
            loc?.let { number(it.ageS, 1) },                        // gps_age_s
            loc?.speedKmh?.let { number(it.toDouble(), 1) },        // speed_kmh
        )
        check(fields.size == LOCATION_COLUMNS)
        return fields
    }

    private fun simFields(sim: SimSample): List<String?> {
        val cell = sim.serving
        val sig = sim.signal
        val fields: List<String?> = listOf(
            sim.slot.toString(),                                    // sim_slot
            sim.subId.toString(),                                   // sub_id
            sim.carrier,                                            // carrier
            sim.simPlmn,                                            // sim_plmn
            flag(sim.isDataSim),                                    // is_data_sim
            flag(sim.isActiveData),                                 // is_active_data
            sim.serviceState,                                       // service_state
            flag(sim.psRegistered),                                 // ps_registered
            sim.registeredPlmn,                                     // reg_plmn
            sim.networkType,                                        // network_type
            cell?.rat?.name,                                        // rat
            cell?.cellId?.toString(),                               // cell_id
            cell?.tac?.toString(),                                  // tac
            cell?.pci?.toString(),                                  // pci
            cell?.arfcn?.toString(),                                // arfcn
            cell?.bands?.takeIf { it.isNotEmpty() }?.joinToString(";"), // bands
            cell?.ageS?.let { number(it, 1) },                      // cell_age_s
            sig.level?.toString(),                                  // level
            sig.rsrp?.toString(),                                   // rsrp
            sig.rsrq?.toString(),                                   // rsrq
            sig.sinr?.toString(),                                   // sinr
            sig.rssi?.toString(),                                   // rssi
            sig.dbm?.toString(),                                    // dbm
            sig.nrRsrp?.toString(),                                 // nr_rsrp
            sig.nrRsrq?.toString(),                                 // nr_rsrq
            sig.nrSinr?.toString(),                                 // nr_sinr
            flag(sim.probe?.ok),                                    // probe_ok
            sim.probe?.ms?.toString(),                              // probe_ms
        )
        check(fields.size == SIM_COLUMNS)
        return fields
    }

    private fun number(value: Double, decimals: Int): String? {
        if (value.isNaN() || value.isInfinite()) return null
        return String.format(Locale.ROOT, "%." + decimals + "f", value)
    }

    private fun flag(value: Boolean?): String? = when (value) {
        true -> "1"
        false -> "0"
        null -> null
    }
}
