package pl.com.chodera.signallogger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SignalLoggerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createRecordingChannel()
    }

    private fun createRecordingChannel() {
        val manager: NotificationManager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            "Nagrywanie zasięgu",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Trwa zapis poziomu sygnału i pozycji GPS"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
    }
}
