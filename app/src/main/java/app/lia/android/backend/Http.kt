package app.lia.android.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing for the three cloud backends. */
object Http {

    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    val WAV_MEDIA = "audio/wav".toMediaType()

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Runs the call off the main thread and maps transport errors to our kinds. */
    suspend fun execute(call: Call): Response = withContext(Dispatchers.IO) {
        try {
            call.execute()
        } catch (e: UnknownHostException) {
            throw BackendException(
                BackendException.Kind.UNREACHABLE,
                "No connection - check the phone's network.",
                e,
            )
        } catch (e: SocketTimeoutException) {
            throw BackendException(BackendException.Kind.TIMEOUT, "The request timed out.", e)
        } catch (e: IOException) {
            throw BackendException(
                BackendException.Kind.UNREACHABLE,
                "Network error: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    fun jsonBody(text: String) = text.toRequestBody(JSON_MEDIA)

    fun get(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()

    /**
     * Turn an HTTP failure into the right [BackendException]. [detail] is the
     * provider's own message when we managed to parse one - always prefer it
     * over a bare status line (that was the S42 "AssemblyAI failed" trap, where
     * a mislabelled 400 hid `API_KEY_INVALID`).
     */
    fun failure(provider: String, code: Int, detail: String?): BackendException {
        val suffix = if (detail.isNullOrBlank()) "" else ": $detail"
        return when (code) {
            401, 403 -> BackendException(
                BackendException.Kind.UNAUTHORIZED,
                "$provider rejected the API key$suffix",
            )
            429 -> BackendException(
                BackendException.Kind.RATE_LIMITED,
                "$provider rate limit reached$suffix",
            )
            in 500..599 -> BackendException(
                BackendException.Kind.SERVER_ERROR,
                "$provider is having trouble (HTTP $code)$suffix",
            )
            else -> BackendException(
                BackendException.Kind.BAD_REQUEST,
                "$provider returned HTTP $code$suffix",
            )
        }
    }
}
