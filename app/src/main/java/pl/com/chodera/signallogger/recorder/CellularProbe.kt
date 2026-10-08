package pl.com.chodera.signallogger.recorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.com.chodera.signallogger.model.ProbeResult
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tests real internet access over the cellular network of the default data SIM. The request for a
 * TRANSPORT_CELLULAR network keeps mobile data up and routes the probe around the train's Wi-Fi.
 */
class CellularProbe(context: Context, intervalMs: Long) {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var network: Network? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    // Blocking sockets cannot be cancelled reliably (DNS on a dead link can outlast the connect timeout),
    // so the blocking part runs in its own scope and the caller only waits for a bounded time.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per-sample timeout: min(3000, 0.8 * interval). */
    val defaultTimeoutMs: Int = minOf(3000, (intervalMs * 0.8).toInt())

    fun start() {
        val manager = connectivityManager ?: return
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@CellularProbe.network = network
            }

            override fun onLost(network: Network) {
                if (this@CellularProbe.network == network) {
                    this@CellularProbe.network = null
                }
            }
        }
        try {
            manager.requestNetwork(request, networkCallback)
            callback = networkCallback
        } catch (_: RuntimeException) {
            // SecurityException or too many outstanding requests: the probe then reports "no internet"
        }
    }

    suspend fun run(timeoutMs: Int = defaultTimeoutMs): ProbeResult {
        val cellular = network ?: return ProbeResult(ok = false, ms = null)
        val result = CompletableDeferred<ProbeResult>()
        ioScope.launch { result.complete(probeBlocking(cellular, timeoutMs)) }
        return withTimeoutOrNull(timeoutMs + HARD_LIMIT_SLACK_MS) { result.await() }
            ?: ProbeResult(ok = false, ms = null)
    }

    fun stop() {
        val registered = callback
        callback = null
        network = null
        if (registered != null) {
            try {
                connectivityManager?.unregisterNetworkCallback(registered)
            } catch (_: RuntimeException) {
                // already unregistered
            }
        }
        ioScope.cancel()
    }

    private fun probeBlocking(cellular: Network, timeoutMs: Int): ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val opened = cellular.openConnection(URL(PROBE_URL)) as HttpURLConnection
            connection = opened
            opened.connectTimeout = timeoutMs
            opened.readTimeout = timeoutMs
            opened.useCaches = false
            opened.instanceFollowRedirects = false
            val startedAt = SystemClock.elapsedRealtime()
            val code = opened.responseCode
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            ProbeResult(ok = code == HttpURLConnection.HTTP_NO_CONTENT, ms = elapsed)
        } catch (_: Exception) {
            ProbeResult(ok = false, ms = null)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val HARD_LIMIT_SLACK_MS = 500L
    }
}
