package app.lia.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode any audio (or the audio track of a video) the system can open, into
 * Lia's 16 kHz mono float format (plan Phase 4).
 *
 * MediaExtractor + MediaCodec are built into Android and cover m4a/aac, mp3,
 * wav, ogg/opus, mp4 and 3gp on any modern device, so the app ships without
 * ffmpeg (which would add ~30 MB and its own licence question).
 */
object FileDecoder {

    class UnsupportedAudio(message: String) : Exception(message)

    suspend fun decode(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
            } ?: throw UnsupportedAudio("Could not open that file.")

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw UnsupportedAudio("That file has no audio track.")

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw UnsupportedAudio("Unknown audio format.")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
                ?: throw UnsupportedAudio("This device cannot decode $mime.")

            val pcm = try {
                codec.configure(format, null, null, 0)
                codec.start()
                drain(extractor, codec)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val interleaved = AudioMath.pcm16ToFloat(pcm)
            val mono = AudioMath.toMono(interleaved, channels)
            Resampler.to16k(mono, sourceRate)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec): ByteArray {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        while (!sawOutputEnd) {
            if (!sawInputEnd) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0) {
                    val chunk = ByteArray(info.size)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset)
                    buffer.get(chunk, 0, info.size)
                    buffer.clear()
                    out.write(chunk)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && sawInputEnd) {
                // Nothing more is coming; avoid spinning forever on a broken file.
                if (out.size() > 0) sawOutputEnd = true
            }
        }
        return out.toByteArray()
    }

    private const val TIMEOUT_US = 10_000L
}
