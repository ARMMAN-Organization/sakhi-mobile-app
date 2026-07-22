package org.armman.sakhi.data.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_DIR_NAME = "debug-logs"
private const val LOG_FILE_NAME = "api-calls.jsonl"
private const val MAX_LOGGED_BODY_BYTES = 64L * 1024
private const val MAX_LOG_FILE_BYTES = 5L * 1024 * 1024
private const val REDACTED = "***REDACTED***"

/** Field names (case-insensitive) whose values are masked before a body is written to the log,
 * wherever they appear in the JSON structure (top-level or nested). Covers login credentials and
 * session tokens — everything else (names, phone numbers, health answers, etc.) is intentionally
 * left readable since that's the whole point of this log. */
private val SENSITIVE_KEYS = setOf(
  "password",
  "token",
  "accesstoken",
  "refreshtoken",
  "otp",
  "authorization",
)

/** One line of [ApiFileLoggingInterceptor]'s log — [requestBody] and [responseBody] are embedded
 * as real nested JSON values (not escaped strings) whenever the body was valid JSON, so the log
 * reads naturally when opened in a JSON-aware viewer; falls back to a plain string for non-JSON/
 * unreadable bodies. Sensitive fields (see [SENSITIVE_KEYS]) are redacted in both. */
private data class ApiLogEntry(
  val timestamp: String,
  val method: String,
  val url: String,
  val requestBody: Any?,
  val statusCode: Int?,
  val tookMs: Long?,
  val responseBody: Any?,
  val error: String? = null,
)

/**
 * Appends every API request/response the app makes to a JSON Lines file on-device (one JSON
 * object per line) so a full request payload + response + URL can be inspected end-to-end
 * (login through logout) without a logcat session. JSON Lines rather than one big JSON array:
 * appending a line is cheap and safe even if the app is killed mid-session; a single growing
 * array would need the whole file rewritten (and risk corruption) on every request.
 *
 * Sensitive fields (password, token, accessToken, refreshToken, otp, authorization) are redacted
 * wherever they appear in the request or response body — see [SENSITIVE_KEYS]. Everything else
 * (including other PII such as names/phone numbers/health answers) is logged as-is; that's the
 * point of this tool, so it must never be wired into a release build.
 *
 * **Debug builds only** — wired in [org.armman.sakhi.di.NetworkModule] behind `BuildConfig.DEBUG`,
 * same guard as the existing `HttpLoggingInterceptor`.
 *
 * File lives at the app's private internal storage. Pull it with:
 * `adb exec-out run-as org.armman.sakhi cat files/debug-logs/api-calls.jsonl > api-calls.jsonl`
 * then view with `cat api-calls.jsonl | jq .` (or any JSONL-aware viewer), or open it directly in
 * Android Studio's Device File Explorer.
 */
@Singleton
class ApiFileLoggingInterceptor internal constructor(
  private val logFile: File,
) : Interceptor {

  // Real usage always goes through this constructor; the primary one above only exists so tests
  // can point the interceptor at a temp file instead of needing a real Android Context/filesDir.
  @Inject
  constructor(@ApplicationContext context: Context) : this(
    File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME).apply { parentFile?.mkdirs() },
  )

  private val gson = Gson()

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val startNanos = System.nanoTime()

    // Peek the request body without consuming it — Retrofit/OkHttp still send the original.
    val requestBodyText = runCatching {
      request.body?.let { body ->
        Buffer().also { body.writeTo(it) }.readUtf8().take(MAX_LOGGED_BODY_BYTES.toInt())
      }
    }.getOrNull()
    val requestBodyEntry = requestBodyText?.let(::parseAsJsonOrRawString)?.let(::redact)

    val response = try {
      chain.proceed(request)
    } catch (e: IOException) {
      appendEntry(
        ApiLogEntry(
          timestamp = Instant.now().toString(),
          method = request.method,
          url = request.url.toString(),
          requestBody = requestBodyEntry,
          statusCode = null,
          tookMs = null,
          responseBody = null,
          error = e.message ?: e.toString(),
        ),
      )
      throw e
    }

    val tookMs = (System.nanoTime() - startNanos) / 1_000_000
    // peekBody reads without consuming the stream — the real response body is still available to
    // Retrofit/Gson afterwards.
    val bodyText = runCatching { response.peekBody(MAX_LOGGED_BODY_BYTES).string() }.getOrNull()

    appendEntry(
      ApiLogEntry(
        timestamp = Instant.now().toString(),
        method = request.method,
        url = request.url.toString(),
        requestBody = requestBodyEntry,
        statusCode = response.code,
        tookMs = tookMs,
        responseBody = bodyText?.let(::parseAsJsonOrRawString)?.let(::redact),
      ),
    )
    return response
  }

  /** Embeds the body as a real JSON structure when it parses as one, so it's not double-encoded
   * (an escaped string full of `\"`) in the final log line — falls back to the raw string
   * (truncated bodies, non-JSON error pages, etc.) when it doesn't parse. */
  private fun parseAsJsonOrRawString(body: String): Any =
    try {
      gson.fromJson(JsonParser.parseString(body), Any::class.java)
    } catch (e: JsonSyntaxException) {
      body
    } catch (e: IllegalStateException) {
      body
    }

  /** Recursively masks any [SENSITIVE_KEYS] entry found in a map/list structure produced by
   * [parseAsJsonOrRawString]. Leaves non-map/list values (including plain-string fallback
   * bodies) untouched. */
  @Suppress("UNCHECKED_CAST")
  private fun redact(value: Any?): Any? = when (value) {
    is Map<*, *> -> (value as Map<String, Any?>).mapValues { (key, v) ->
      if (key.lowercase() in SENSITIVE_KEYS) REDACTED else redact(v)
    }
    is List<*> -> value.map { redact(it) }
    else -> value
  }

  @Synchronized
  private fun appendEntry(entry: ApiLogEntry) {
    runCatching {
      if (logFile.exists() && logFile.length() > MAX_LOG_FILE_BYTES) {
        // Simple cap — not a rotation scheme, just avoids the file growing unbounded during a
        // long testing session.
        logFile.delete()
      }
      logFile.appendText(gson.toJson(entry) + "\n")
    }
  }
}
