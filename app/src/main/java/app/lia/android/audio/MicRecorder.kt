package app.lia.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/**
 * Microphone capture: 16 kHz mono PCM16 in 20 ms buffers, the same shape the
 * desktop records (plan Phase 2).
 *
 * `VOICE_RECOGNITION` is deliberate: it is the source Android does the least
 * processing on, so no aggressive noise gate eats the start of a quiet Hebrew
 * word before the model ever sees it.
 *
 * The caller is responsible for holding a foreground service while this runs -
 * on One UI the process is otherwise frozen the moment the screen goes off.
 */
class MicRecorder {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = ByteArrayOutputStream(BYTES_PER_SECOND * 30)

    private val _amplitude = MutableStateFlow(0f)

    /** 0..1 level for the waveform, updated ~50 times a second. */
    val amplitude: StateFlow<Float> = _amplitude

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    val seconds: Float
        get() = buffer.size() / 2f / AudioMath.RATE

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (_recording.value) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioMath.RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val size = maxOf(minBuffer, FRAME_BYTES * 8)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioMath.RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                size,
            )
        } catch (e: Exception) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        buffer.reset()
        record = recorder
        recorder.startRecording()
        _recording.value = true
        job = scope.launch(Dispatchers.IO) {
            val frame = ByteArray(FRAME_BYTES)
            while (_recording.value) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue
                buffer.write(frame, 0, read)
                _amplitude.value = AudioMath.rms(AudioMath.pcm16ToFloat(frame, read))
            }
        }
        return true
    }

    /** Stops capture and returns the whole clip as 16 kHz mono float samples. */
    fun stop(): FloatArray {
        if (!_recording.value && record == null) return FloatArray(0)
        _recording.value = false
        job?.cancel()
        job = null
        val recorder = record
        record = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        _amplitude.value = 0f
        return AudioMath.pcm16ToFloat(buffer.toByteArray())
    }

    fun cancel() {
        stop()
        buffer.reset()
    }

    companion object {
        /** 20 ms of 16 kHz mono PCM16. */
        const val FRAME_BYTES = AudioMath.RATE / 50 * 2
        const val BYTES_PER_SECOND = AudioMath.RATE * 2

        /** Auto-stop, matching the desktop's dictation ceiling. */
        const val MAX_SECONDS = 30 * 60
    }
}
