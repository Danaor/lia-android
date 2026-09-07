package app.lia.android.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.lia.android.App
import app.lia.android.AppGraph
import app.lia.android.MainActivity
import app.lia.android.audio.MicRecorder
import app.lia.android.backend.Router
import app.lia.android.store.History
import app.lia.android.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Lia keyboard: a voice-only IME whose single job is to put dictated text
 * into whatever field you are already in - a WhatsApp chat, an email, a search
 * box (plan v2 item 3, "dictate anywhere").
 *
 * An IME is the only sanctioned way on Android to insert text into another
 * app's field. The alternative, a floating button plus an AccessibilityService,
 * would need a permission that can read every screen; that is not a reasonable
 * trade for a dictation button.
 *
 * The flow this is built around: switch to the Lia keyboard, it starts
 * listening at once, you speak, you tap stop, the text is committed and the
 * keyboard hands control straight back to your usual one. Both of those
 * automatic steps are switchable in Settings.
 */
class LiaKeyboardService : InputMethodService() {

    private val graph: AppGraph by lazy {
        (application as? App)?.graph ?: AppGraph.get(this)
    }

    private val recorder = MicRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var statusView: TextView? = null
    private var micButton: Button? = null
    private var timerJob: Job? = null
    private var busy = false

    override fun onCreateInputView(): View = buildKeyboard()

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
        val autoStart = invokedAsVoiceInput() || graph.settings.state.value.imeAutoStart
        if (!restarting && autoStart && hasMicPermission()) {
            startRecording()
        }
    }

    /**
     * True when another keyboard handed us the microphone rather than the user
     * picking Lia from the switcher.
     *
     * Keyboards that do not run their own recogniser - SwiftKey among them -
     * delegate their mic key to whatever IME is registered for voice input. In
     * that case Lia is a guest for exactly one utterance: it listens at once and
     * gives the keyboard back afterwards, whatever the Settings toggles say.
     */
    private fun invokedAsVoiceInput(): Boolean {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return manager?.currentInputMethodSubtype?.mode == "voice"
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Leaving the field mid-sentence must not leave the mic open.
        if (recorder.recording.value) cancelRecording()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        cancelRecording()
        super.onDestroy()
    }

    // --------------------------------------------------------------- recording

    private fun toggleRecording() {
        when {
            busy -> Unit
            recorder.recording.value -> stopAndInsert()
            !hasMicPermission() -> openAppForPermission()
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (busy || recorder.recording.value) return
        if (!recorder.start(scope)) {
            setStatus("Could not open the microphone.")
            return
        }
        graph.diagnostics.log("keyboard: recording started")
        render()
        timerJob = scope.launch {
            while (recorder.recording.value) {
                setStatus("Listening  ${formatDuration(recorder.seconds)}")
                delay(200)
            }
        }
    }

    private fun cancelRecording() {
        timerJob?.cancel()
        timerJob = null
        recorder.cancel()
        busy = false
        render()
    }

    private fun stopAndInsert() {
        timerJob?.cancel()
        timerJob = null
        val audio = recorder.stop()
        busy = true
        render()
        setStatus("Transcribing...")

        graph.scope.launch {
            graph.diagnostics.logTranscripts = graph.settings.state.value.logTranscripts
            val outcome = graph.router().transcribe(audio, Router.Mode.DICTATION)
            withContext(Dispatchers.Main) { apply(outcome) }
        }
    }

    private fun apply(outcome: Router.Outcome) {
        busy = false
        when (outcome) {
            is Router.Outcome.Success -> {
                val text = outcome.result.text
                graph.diagnostics.logTranscript(
                    outcome.result.backend.short, outcome.result.elapsedMs, text
                )
                if (text.isBlank()) {
                    setStatus("Nothing was heard.")
                    render()
                    return
                }
                graph.history.add(
                    History.Entry(
                        text = text,
                        backend = outcome.result.backend.short,
                        timestamp = System.currentTimeMillis(),
                        elapsedMs = outcome.result.elapsedMs,
                        source = "keyboard",
                    )
                )
                if (insert(text)) {
                    setStatus("Inserted by ${outcome.result.backend.short}")
                    render()
                    val goBack =
                        invokedAsVoiceInput() || graph.settings.state.value.imeAutoReturn
                    if (goBack) returnToPreviousKeyboard()
                } else {
                    // No field to write into: leave it somewhere the user can reach.
                    copyToClipboard(text)
                    setStatus("No text field - copied to the clipboard instead.")
                    render()
                }
            }
            is Router.Outcome.Rejected -> {
                graph.diagnostics.log("keyboard rejected: ${outcome.message}")
                setStatus(outcome.message)
                render()
            }
            is Router.Outcome.Failed -> {
                graph.diagnostics.log("keyboard failed (${outcome.kind}): ${outcome.message}")
                setStatus(outcome.message)
                render()
            }
        }
    }

    /** Writes into whatever field currently has focus. */
    private fun insert(text: String): Boolean {
        val connection = currentInputConnection ?: return false
        val spaced = if (needsLeadingSpace(text)) " $text" else text
        return connection.commitText(spaced, 1)
    }

    /**
     * A second dictation into the same field should not glue itself to the last
     * word. Only add the space when there is already text and neither side
     * brings its own separator.
     */
    private fun needsLeadingSpace(text: String): Boolean {
        val connection = currentInputConnection ?: return false
        val before = connection.getTextBeforeCursor(1, 0) ?: return false
        if (before.isEmpty()) return false
        val last = before.last()
        return !last.isWhitespace() && !text.firstOrNull().let { it == null || it.isWhitespace() }
    }

    // ----------------------------------------------------------------- keyboard

    private fun buildKeyboard(): View {
        val context: Context = this
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }

        statusView = TextView(context).apply {
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            key("⌨", "Switch keyboard") { returnToPreviousKeyboard() },
            rowParams(weight = 1f),
        )
        micButton = Button(context).apply {
            text = "Speak"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pill(PURPLE)
            setOnClickListener { toggleRecording() }
        }
        topRow.addView(
            micButton,
            LinearLayout.LayoutParams(0, dp(64), 2.4f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        topRow.addView(
            key("⌫", "Backspace") { backspace() },
            rowParams(weight = 1f),
        )
        root.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        bottomRow.addView(key("space") { insertRaw(" ") }, rowParams(weight = 2f))
        bottomRow.addView(key("↵", "New line") { enter() }, rowParams(weight = 1f))
        bottomRow.addView(key("Lia") { openApp() }, rowParams(weight = 1f))
        root.addView(bottomRow, LinearLayout.LayoutParams(MATCH, WRAP))

        return root
    }

    private fun key(label: String, description: String = label, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setTextColor(INK)
            contentDescription = description
            background = pill(SURFACE)
            setOnClickListener { onClick() }
        }

    private fun rowParams(weight: Float) =
        LinearLayout.LayoutParams(0, dp(56), weight).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    private fun render() {
        val recording = recorder.recording.value
        micButton?.apply {
            text = when {
                busy -> "Working"
                recording -> "Stop"
                else -> "Speak"
            }
            isEnabled = !busy
            background = pill(if (recording) RED else PURPLE)
        }
        if (!recording && !busy) {
            setStatus(
                when {
                    !hasMicPermission() -> "Tap Lia to allow the microphone."
                    else -> "Tap Speak, then talk."
                }
            )
        }
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }

    // -------------------------------------------------------------------- keys

    private fun insertRaw(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            connection.deleteSurroundingText(1, 0)
        } else {
            connection.commitText("", 1)
        }
    }

    private fun enter() {
        val connection = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            connection.performEditorAction(action)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun returnToPreviousKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            false
        }
        if (!switched) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showInputMethodPicker()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** An IME cannot ask for a permission itself; the app has to do it. */
    private fun openAppForPermission() {
        setStatus("Lia needs the microphone. Opening the app...")
        openApp()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Lia", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val PURPLE = Color.parseColor("#7C4DFF")
        val RED = Color.parseColor("#D32F2F")
        val SURFACE = Color.parseColor("#E7E3F5")
        val BACKGROUND = Color.parseColor("#F3F0FA")
        val INK = Color.parseColor("#1B1B26")
        val MUTED = Color.parseColor("#5A5A6E")
    }
}
