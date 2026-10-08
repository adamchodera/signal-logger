package pl.com.chodera.signallogger.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.com.chodera.signallogger.MainActivity
import pl.com.chodera.signallogger.R
import pl.com.chodera.signallogger.SignalLoggerApp
import pl.com.chodera.signallogger.model.CsvFormat
import pl.com.chodera.signallogger.model.SampleRecord
import pl.com.chodera.signallogger.model.summaryLine
import java.time.ZoneId

/**
 * Foreground service (type location) that samples GPS + telephony every N seconds and appends the
 * result to a CSV file. START / STOP / MARK are delivered as intent actions.
 */
class RecorderService : Service() {

    // Everything below is touched from the main thread only, except the @Volatile members.
    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tracker: LocationTracker? = null
    private var probe: CellularProbe? = null
    private var collector: RadioCollector? = null

    @Volatile
    private var writer: CsvSessionWriter? = null

    @Volatile
    private var recording = false

    private val notificationLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val contentIntent: PendingIntent by lazy {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        PendingIntent.getActivity(this, REQUEST_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private val markIntent: PendingIntent by lazy { servicePendingIntent(REQUEST_MARK, ACTION_MARK) }
    private val stopIntent: PendingIntent by lazy { servicePendingIntent(REQUEST_STOP, ACTION_STOP) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopRecording()
            ACTION_MARK -> handleMark(intent)
            else -> if (!recording) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---- actions -------------------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        if (recording) return

        if (!hasRequiredPermissions()) {
            RecorderRepository.update {
                copy(isRecording = false, lastError = "Brak uprawnień do lokalizacji lub stanu telefonu")
            }
            // startForegroundService() obliges us to call startForeground() - satisfy it if the platform
            // still allows it, then leave right away.
            try {
                promoteToForeground(buildNotification(null, 0))
            } catch (_: Exception) {
                // e.g. SecurityException on API 34+ without any location permission
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // startForeground comes before any other work.
        try {
            promoteToForeground(buildNotification(null, 0))
        } catch (e: Exception) {
            RecorderRepository.update { copy(isRecording = false, lastError = "Nie można uruchomić usługi: ${e.message}") }
            stopSelf()
            return
        }

        val intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val probeEnabled = intent.getBooleanExtra(EXTRA_PROBE, true)

        try {
            synchronized(notificationLock) { recording = true }
            acquireWakeLock()

            val locationTracker = LocationTracker(this, intervalMs).also { it.start() }
            val cellularProbe = if (probeEnabled) CellularProbe(this, intervalMs).also { it.start() } else null
            val radioCollector = RadioCollector(this)
            tracker = locationTracker
            probe = cellularProbe
            collector = radioCollector

            RecorderRepository.update {
                RecorderState(
                    isRecording = true,
                    startedAtMs = System.currentTimeMillis(),
                    gpsProviderEnabled = locationTracker.isGpsEnabled(),
                )
            }

            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = newScope
            loopJob = newScope.launch { sampleLoop(intervalMs, locationTracker, radioCollector, cellularProbe) }
        } catch (e: Exception) {
            stopRecording()
            RecorderRepository.update { copy(lastError = "Nie można rozpocząć nagrywania: ${e.message}") }
        }
    }

    private fun handleMark(intent: Intent) {
        val activeWriter = writer
        if (!recording) {
            stopSelf() // a stray MARK must not leave an idle service behind
            return
        }
        if (activeWriter == null) return
        val note = intent.getStringExtra(EXTRA_NOTE) ?: DEFAULT_NOTE
        val location = tracker?.current()
        val epochMs = System.currentTimeMillis()
        scope?.launch {
            try {
                activeWriter.writeLine(CsvFormat.markerRow(epochMs, location, note, ZoneId.systemDefault()))
                RecorderRepository.update { copy(markerCount = markerCount + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecorderRepository.update { copy(lastError = "Zapis znacznika nie powiódł się: ${e.message}") }
            }
        }
    }

    private fun stopRecording() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- sampling loop -------------------------------------------------------------------------

    private suspend fun sampleLoop(
        intervalMs: Long,
        locationTracker: LocationTracker,
        radioCollector: RadioCollector,
        cellularProbe: CellularProbe?,
    ) {
        val sessionWriter: CsvSessionWriter = try {
            withContext(Dispatchers.IO) { CsvSessionWriter.open(applicationContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecorderRepository.update { copy(lastError = "Nie można utworzyć pliku logu: ${e.message}") }
            mainHandler.post { stopRecording() }
            return
        }
        writer = sessionWriter
        RecorderRepository.update { copy(fileName = sessionWriter.fileName) }

        val zone: ZoneId = ZoneId.systemDefault()
        while (currentCoroutineContext().isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val record = radioCollector.collect(locationTracker.current(), cellularProbe)
                sessionWriter.write(CsvFormat.rows(record, zone))
                val gpsEnabled = locationTracker.isGpsEnabled()
                RecorderRepository.update {
                    copy(sampleCount = sampleCount + 1, last = record, gpsProviderEnabled = gpsEnabled)
                }
                updateNotification(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // one bad read must never end the session
                RecorderRepository.update { copy(lastError = e.message ?: e.javaClass.simpleName) }
            }
            val spentMs = SystemClock.elapsedRealtime() - startedAt
            delay((intervalMs - spentMs).coerceAtLeast(0L))
        }
    }

    // ---- cleanup (idempotent) ------------------------------------------------------------------

    private fun cleanup() {
        val wasRecording: Boolean = synchronized(notificationLock) {
            val previous = recording
            recording = false
            previous
        }

        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null

        tracker?.stop()
        tracker = null
        probe?.stop()
        probe = null
        collector?.close()
        collector = null

        val finishedWriter = writer
        writer = null
        if (finishedWriter != null) {
            // close() takes the writer mutex, so it waits for an in-flight write; keep it off the main thread
            closeScope.launch { finishedWriter.close() }
        }

        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null

        if (wasRecording) {
            RecorderRepository.update { copy(isRecording = false) }
        }
    }

    // ---- foreground / notification -------------------------------------------------------------

    private fun promoteToForeground(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun buildNotification(record: SampleRecord?, sampleCount: Int): Notification {
        val text = record?.summaryLine() ?: "Uruchamianie…"
        return NotificationCompat.Builder(this, SignalLoggerApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nagrywanie zasięgu")
            .setContentText(text)
            .setSubText("Próbki: $sampleCount")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, "Znacznik", markIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(record: SampleRecord) {
        if (!canPostNotifications()) return
        val sampleCount = RecorderRepository.state.value.sampleCount
        val notification = buildNotification(record, sampleCount)
        // Serialised with cleanup() so that a late update cannot resurrect the notification after Stop.
        synchronized(notificationLock) {
            if (!recording) return
            postNotification(notification)
        }
    }

    @SuppressLint("MissingPermission") // guarded by canPostNotifications()
    private fun postNotification(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // permission revoked while recording
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ---- misc ----------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val powerManager: PowerManager = getSystemService(PowerManager::class.java) ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun hasRequiredPermissions(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) && isGranted(Manifest.permission.READ_PHONE_STATE)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "pl.com.chodera.signallogger.action.START"
        const val ACTION_STOP = "pl.com.chodera.signallogger.action.STOP"
        const val ACTION_MARK = "pl.com.chodera.signallogger.action.MARK"
        const val EXTRA_INTERVAL_MS = "pl.com.chodera.signallogger.extra.INTERVAL_MS"
        const val EXTRA_PROBE = "pl.com.chodera.signallogger.extra.PROBE"
        const val EXTRA_NOTE = "pl.com.chodera.signallogger.extra.NOTE"

        const val DEFAULT_NOTE = "brak internetu"
        const val DEFAULT_INTERVAL_MS = 5000L
        private const val MIN_INTERVAL_MS = 1000L
        private const val MAX_INTERVAL_MS = 60_000L

        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 0
        private const val REQUEST_MARK = 1
        private const val REQUEST_STOP = 2
        private const val WAKE_LOCK_TAG = "SignalLogger:rec"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L

        // Outlives the service so that the final close() of the CSV file is never cancelled.
        private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Starts the foreground service (must be called while the app is visible). */
        fun start(context: Context, intervalMs: Long, probe: Boolean) {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
                .putExtra(EXTRA_PROBE, probe)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun mark(context: Context, note: String = DEFAULT_NOTE) {
            context.startService(
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_MARK)
                    .putExtra(EXTRA_NOTE, note),
            )
        }
    }
}
