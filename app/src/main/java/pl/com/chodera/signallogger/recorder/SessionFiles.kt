package pl.com.chodera.signallogger.recorder

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

data class SessionFile(val name: String, val sizeBytes: Long, val lastModified: Long)

/** Lists, deletes and shares the CSV logs stored in the app's external files directory. */
class SessionFiles(context: Context) {

    private val appContext: Context = context.applicationContext

    private fun logsDir(): File? = appContext.getExternalFilesDir(LOGS_DIR)

    /** Newest first. */
    fun list(): List<SessionFile> {
        val dir = logsDir() ?: return emptyList()
        val files: Array<File> = dir.listFiles { file: File -> file.isFile && file.name.endsWith(".csv") }
            ?: return emptyList()
        return files
            .map { SessionFile(name = it.name, sizeBytes = it.length(), lastModified = it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    fun delete(name: String): Boolean {
        val file = resolve(name) ?: return false
        return file.delete()
    }

    /** A chooser intent that shares the given log as `text/csv` through the FileProvider. */
    fun shareIntent(name: String): Intent {
        val file = resolve(name) ?: throw IllegalArgumentException("Brak pliku: $name")
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Udostępnij log")
    }

    /** Only plain file names inside the logs directory are accepted (no path traversal). */
    private fun resolve(name: String): File? {
        if (name.isEmpty() || name != File(name).name) return null
        val dir = logsDir() ?: return null
        val file = File(dir, name)
        return if (file.isFile) file else null
    }

    companion object {
        const val LOGS_DIR = "logs"
    }
}
