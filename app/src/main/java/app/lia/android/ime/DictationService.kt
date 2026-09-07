package app.lia.android.ime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import app.lia.android.App
import app.lia.android.AppGraph
import app.lia.android.MainActivity
import app.lia.android.R
import app.lia.android.audio.AudioMath
import app.lia.android.audio.MicRecorder
import app.lia.android.backend.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One dictation, started from somewhere that has no UI of its own - today the
 * Quick Settings tile.
 *
 * It exists because Android will not hand the microphone to a background
 * component: a foreground service with a visible notification is what makes the
 * mic usable at all once you have left the app.
 *
 * It stops itself. Pulling the shade down twice, once to start and once to
 * stop, would be worse than the thing it replaces, so the session ends after a
 * stretch of quiet - the same endpointing the recogniser uses - or from the
 * Stop action on its notification.
 */
class DictationService : Service() {

    private val graph: AppGraph by lazy {
        (application as? App)?.graph ?: AppGraph.get(this)
    }

    private val recorder = MicRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        post(notification("Starting...", stoppable = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        recorder.cancel()
        active.value = false
        DictationTileService.refresh(this)
        super.onDestroy()
    }

    // ----------------------------------------------------------------- session

    private fun begin() {
        if (recorder.recording.value) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Lia needs the microphone. Open the app once to allow it.")
            stopSelf()
            return
        }
        if (!recorder.start(scope)) {
            toast("Could not open the microphone.")
            stopSelf()
            return
        }
        graph.diagnostics.log("tile: recording started")
        active.value = true
        DictationTileService.refresh(this)
        post(notification("Listening. Speak now.", stoppable = true))
        watcher = scope.launch { watchForSilence() }
    }

    /** Ends the session once the speaker stops, with a ceiling either way. */
    private suspend fun watchForSilence() {
        var heardSpeech = false
        var quietSince = 0L
        val started = System.currentTimeMillis()
        while (recorder.recording.value) {
            delay(POLL_MS)
            val level = recorder.amplitude.value
            if (level >= AudioMath.SILENCE_RMS * SPEECH_FACTOR) {
                heardSpeech = true
                quietSince = 0L
            } else if (heardSpeech) {
                if (quietSince == 0L) quietSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - quietSince >= TRAILING_SILENCE_MS) break
            }
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= MAX_SESSION_MS) break
            if (!heardSpeech && elapsed >= NO_SPEECH_TIMEOUT_MS) break
        }
        finish()
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        watcher?.cancel()
        watcher = null
        val audio = recorder.stop()
        active.value = false
        DictationTileService.refresh(this)
        post(notification("Transcribing...", stoppable = false))

        graph.scope.launch {
            graph.diagnostics.logTranscripts = graph.settings.state.value.logTranscripts
            val outcome = graph.router().transcribe(audio, Router.Mode.DICTATION)
            val delivery = Delivery.deliver(this@DictationService, graph, outcome, "tile")
            withContext(Dispatchers.Main) {
                toast(Delivery.message(delivery))
                stopSelf()
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun post(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String, stoppable: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Dictation", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("Lia")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_lia)
            .setContentIntent(open)
            .setOngoing(true)
        if (stoppable) {
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, DictationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "Stop and insert", stop).build())
        }
        return builder.build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val CHANNEL = "lia.dictation"
        private const val NOTIFICATION_ID = 4712
        const val ACTION_STOP = "app.lia.android.DICTATION_STOP"

        private const val POLL_MS = 100L
        private const val TRAILING_SILENCE_MS = 1_500L
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L
        private const val MAX_SESSION_MS = 120_000L
        private const val SPEECH_FACTOR = 3f

        private val active = MutableStateFlow(false)

        /** Drives the tile's on/off look. */
        val isActive: StateFlow<Boolean> = active

        fun start(context: Context) {
            val intent = Intent(context, DictationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DictationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
