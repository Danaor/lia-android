package app.lia.android.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.lia.android.MainActivity
import app.lia.android.R

/**
 * Foreground services whose only job is to keep the process alive and visible
 * while Lia records or transcribes.
 *
 * The work itself runs in the application-scoped coroutine scope in
 * `AppGraph`, so it survives the Activity being destroyed. This split matters
 * on the test device: One UI freezes background work aggressively, and a long
 * file transcription with the screen off dies without a visible notification
 * (plan section 10.4).
 */
abstract class KeepAliveService : Service() {

    abstract val channelId: String
    abstract val channelName: String
    abstract val defaultTitle: String
    abstract val serviceType: Int

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: defaultTitle
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val notification = build(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, serviceType)
        } else {
            startForeground(notificationId, notification)
        }
        return START_NOT_STICKY
    }

    private val notificationId: Int get() = channelId.hashCode() and 0x0000FFFF

    private fun build(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_lia)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, service: Class<out KeepAliveService>, title: String, text: String) {
            val intent = Intent(context, service)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, service: Class<out KeepAliveService>) {
            context.stopService(Intent(context, service))
        }
    }
}

class RecordingService : KeepAliveService() {
    override val channelId = "lia.recording"
    override val channelName = "Recording"
    override val defaultTitle = "Lia is recording"
    override val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
}

class TranscribeService : KeepAliveService() {
    override val channelId = "lia.transcribing"
    override val channelName = "Transcribing"
    override val defaultTitle = "Lia is transcribing"
    override val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}
