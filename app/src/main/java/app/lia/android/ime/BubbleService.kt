package app.lia.android.ime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import app.lia.android.App
import app.lia.android.AppGraph
import app.lia.android.MainActivity
import app.lia.android.R
import app.lia.android.audio.MicRecorder
import app.lia.android.backend.Router
import app.lia.android.store.History
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A floating microphone button that sits on top of whatever you are doing.
 *
 * This exists because switching keyboards to dictate one message is too much
 * friction (Naor, 2026-09-07), and Gboard's own mic key cannot be pointed at
 * anything but Google's recogniser. So: stay in WhatsApp, stay on your
 * keyboard, tap the bubble, talk, tap it again.
 *
 * Where the text goes, in order:
 *  1. straight into the focused field, if [TextInserter] (an optional
 *     accessibility service) is switched on;
 *  2. otherwise onto the clipboard, where the keyboard's paste chip picks it
 *     up with one tap.
 *
 * It runs as a microphone foreground service started from the app while the app
 * is on screen, which is what keeps the mic usable later from the background.
 */
class BubbleService : Service() {

    private val graph: AppGraph by lazy {
        (application as? App)?.graph ?: AppGraph.get(this)
    }

    private val recorder = MicRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var timerJob: Job? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        recorder.cancel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        if (!canDrawOverlays(this)) {
            toast("Lia needs \"Display over other apps\" for the floating button.")
            stopSelf()
            return
        }
        val view = TextView(this).apply {
            text = MIC
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(PURPLE)
            contentDescription = "Lia dictation"
        }
        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            overlayType(),
            // NOT_FOCUSABLE is essential: the moment this window took focus the
            // text field behind it would lose it, and there would be nothing
            // left to insert into.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(72)
            y = resources.displayMetrics.heightPixels / 2
        }
        view.setOnTouchListener(DragOrTap(params) { toggle() })
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                toast("Could not show the floating button.")
                stopSelf()
                return
            }
        bubble = view
        layout = params
        render()
    }

    /** Distinguishes a tap from a drag, so the bubble can be moved out of the way. */
    private inner class DragOrTap(
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > SLOP || abs(dy) > SLOP) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            return true
        }
    }

    // --------------------------------------------------------------- recording

    private fun toggle() {
        when {
            busy -> Unit
            recorder.recording.value -> stopAndDeliver()
            !hasMicPermission() -> {
                toast("Lia needs the microphone. Opening the app.")
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            else -> start()
        }
    }

    private fun start() {
        if (!recorder.start(scope)) {
            toast("Could not open the microphone.")
            return
        }
        graph.diagnostics.log("bubble: recording started")
        render()
        timerJob = scope.launch {
            while (recorder.recording.value) {
                bubble?.text = formatSeconds(recorder.seconds)
                delay(250)
            }
        }
    }

    private fun stopAndDeliver() {
        timerJob?.cancel()
        timerJob = null
        val audio = recorder.stop()
        busy = true
        render()
        graph.scope.launch {
            graph.diagnostics.logTranscripts = graph.settings.state.value.logTranscripts
            val outcome = graph.router().transcribe(audio, Router.Mode.DICTATION)
            withContext(Dispatchers.Main) { deliver(outcome) }
        }
    }

    private fun deliver(outcome: Router.Outcome) {
        busy = false
        render()
        when (outcome) {
            is Router.Outcome.Success -> {
                val text = outcome.result.text
                graph.diagnostics.logTranscript(
                    outcome.result.backend.short, outcome.result.elapsedMs, text
                )
                if (text.isBlank()) {
                    toast("Nothing was heard.")
                    return
                }
                graph.history.add(
                    History.Entry(
                        text = text,
                        backend = outcome.result.backend.short,
                        timestamp = System.currentTimeMillis(),
                        elapsedMs = outcome.result.elapsedMs,
                        source = "bubble",
                    )
                )
                val wantsInsert = graph.settings.state.value.bubbleAutoInsert
                if (wantsInsert && TextInserter.insert(text)) {
                    flash(GREEN)
                } else {
                    copy(text)
                    flash(GREEN)
                    toast(
                        if (wantsInsert && !TextInserter.isConnected) {
                            "Copied. Turn on Lia in Accessibility to insert it directly."
                        } else {
                            "Copied - tap paste on your keyboard."
                        }
                    )
                }
            }
            is Router.Outcome.Rejected -> {
                graph.diagnostics.log("bubble rejected: ${outcome.message}")
                toast(outcome.message)
            }
            is Router.Outcome.Failed -> {
                graph.diagnostics.log("bubble failed (${outcome.kind}): ${outcome.message}")
                toast(outcome.message)
            }
        }
    }

    // ------------------------------------------------------------------ visuals

    private fun render() {
        val view = bubble ?: return
        val recording = recorder.recording.value
        view.text = when {
            busy -> "..."
            recording -> formatSeconds(recorder.seconds)
            else -> MIC
        }
        view.background = circle(
            when {
                busy -> AMBER
                recording -> RED
                else -> PURPLE
            }
        )
    }

    private fun flash(color: Int) {
        val view = bubble ?: return
        view.background = circle(color)
        scope.launch {
            delay(900)
            render()
        }
    }

    private fun formatSeconds(seconds: Float): String = seconds.toInt().toString()

    // ------------------------------------------------------------------ helpers

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun copy(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Lia", text))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.argb(70, 255, 255, 255))
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Floating button", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Lia dictation button")
            .setContentText("Tap the bubble to dictate anywhere")
            .setSmallIcon(R.drawable.ic_stat_lia)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "lia.bubble"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "app.lia.android.BUBBLE_STOP"
        private const val SLOP = 12
        private const val MIC = "🎤"

        private val PURPLE = Color.parseColor("#7C4DFF")
        private val RED = Color.parseColor("#D32F2F")
        private val AMBER = Color.parseColor("#F9A825")
        private val GREEN = Color.parseColor("#2E7D32")

        fun canDrawOverlays(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}
