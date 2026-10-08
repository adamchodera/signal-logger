package pl.com.chodera.signallogger.recorder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.com.chodera.signallogger.model.CsvFormat
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Appends CSV lines to one session file. Every write is flushed so that a killed process loses at most
 * the sample that was being written.
 */
class CsvSessionWriter private constructor(
    val file: File,
    private val writer: BufferedWriter,
) {
    private val mutex = Mutex()
    private var closed = false

    val fileName: String get() = file.name

    suspend fun write(lines: List<String>) {
        if (lines.isEmpty()) return
        mutex.withLock {
            if (!closed) {
                withContext(Dispatchers.IO) {
                    for (line in lines) {
                        writer.write(line)
                        writer.write("\n")
                    }
                    writer.flush()
                }
            }
        }
    }

    suspend fun writeLine(line: String) = write(listOf(line))

    /** Idempotent. */
    suspend fun close() {
        mutex.withLock {
            if (!closed) {
                closed = true
                withContext(Dispatchers.IO) {
                    try {
                        writer.close()
                    } catch (_: IOException) {
                        // nothing sensible left to do
                    }
                }
            }
        }
    }

    companion object {
        private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

        /** Creates `signal_yyyyMMdd_HHmmss.csv` (local time) and writes the header. Blocking IO. */
        @Throws(IOException::class)
        fun open(context: Context, nowMs: Long = System.currentTimeMillis()): CsvSessionWriter {
            val dir = context.getExternalFilesDir(SessionFiles.LOGS_DIR)
                ?: throw IOException("Pamięć aplikacji jest niedostępna")
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Nie można utworzyć katalogu ${dir.path}")
            }
            val stamp = NAME_FORMAT.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
            var file = File(dir, "signal_$stamp.csv")
            var suffix = 1
            while (file.exists()) { // two sessions within one second must not share a file
                file = File(dir, "signal_${stamp}_$suffix.csv")
                suffix++
            }
            val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            try {
                writer.write(CsvFormat.HEADER)
                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                try {
                    writer.close()
                } catch (_: IOException) {
                    // ignore, the original failure is what matters
                }
                throw e
            }
            return CsvSessionWriter(file, writer)
        }
    }
}
