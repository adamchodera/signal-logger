package pl.com.chodera.signallogger.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import pl.com.chodera.signallogger.model.LocationFix

/**
 * Keeps the most recent fix from GPS (and the fused provider on API 31+). Staleness is deliberately not
 * filtered here: the fix age goes into the CSV and is judged at analysis time.
 */
class LocationTracker(context: Context, intervalMs: Long) {

    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(LocationManager::class.java)

    // Fresh fixes are requested at least once per second so that gps_age_s stays small at train speeds.
    private val minTimeMs: Long = minOf(intervalMs, MAX_MIN_TIME_MS)

    @Volatile
    private var latest: Location? = null

    private var started = false

    // Runs on the main looper and only updates a volatile field.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            offer(location)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = locationManager ?: return
        if (started) return
        started = true
        try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { offer(it) }
        } catch (_: SecurityException) {
            // permission revoked: no seed fix
        } catch (_: IllegalArgumentException) {
            // provider not available on this device
        }
        request(manager, LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val providers: List<String> = try {
                manager.allProviders
            } catch (_: SecurityException) {
                emptyList()
            }
            if (providers.contains(LocationManager.FUSED_PROVIDER)) {
                request(manager, LocationManager.FUSED_PROVIDER)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun request(manager: LocationManager, provider: String) {
        try {
            manager.requestLocationUpdates(provider, minTimeMs, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // permission revoked
        } catch (_: IllegalArgumentException) {
            // provider not available
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            locationManager?.removeUpdates(listener)
        } catch (_: SecurityException) {
            // nothing to do
        }
    }

    fun current(): LocationFix? {
        val loc = latest ?: return null
        val ageS = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / NANOS_PER_SECOND
        val accuracy: Float? = if (loc.hasAccuracy()) loc.accuracy else null
        val speedKmh: Float? = if (loc.hasSpeed()) loc.speed * MS_TO_KMH else null
        return LocationFix(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = accuracy,
            ageS = ageS.coerceAtLeast(0.0),
            speedKmh = speedKmh,
        )
    }

    fun isGpsEnabled(): Boolean = try {
        locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
    } catch (_: SecurityException) {
        false
    }

    private fun offer(location: Location) {
        val current = latest
        if (current == null || location.elapsedRealtimeNanos >= current.elapsedRealtimeNanos) {
            latest = location
        }
    }

    private companion object {
        const val MAX_MIN_TIME_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MS_TO_KMH = 3.6f
    }
}
