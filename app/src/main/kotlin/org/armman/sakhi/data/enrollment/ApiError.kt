package org.armman.sakhi.data.enrollment

import com.google.gson.Gson

/**
 * Typed view of the backend's standard error envelope for `POST /beneficiaries`, parsed from the
 * Retrofit `errorBody()` string. The backend returns two shapes we care about (both confirmed
 * against real responses in `api-calls.jsonl`):
 *
 *  - `400 VALIDATION_ERROR` — includes a `fieldErrors` map keyed by dotted DTO path
 *    (e.g. `{"pii.firstName":"String must contain at least 1 character(s)"}`). This is the ONLY
 *    error shape that can be attributed to individual form fields.
 *  - `422 UNPROCESSABLE` (and others) — a plain `message` with no `fieldErrors`
 *    (e.g. `"pii.phcId does not refer to a known geography unit."`). Not field-attributable, so it
 *    stays a page-level banner.
 *
 * [fieldErrors] is never null here — an absent/empty `fieldErrors` becomes an empty map so callers
 * don't have to null-check. See [ApiErrorParser].
 */
data class ApiError(
  val message: String?,
  val errorCode: String?,
  val fieldErrors: Map<String, String>,
)

/**
 * Parses a raw JSON error body into an [ApiError], defensively: a null/blank body, a body that
 * isn't the expected envelope, or malformed JSON all degrade to an [ApiError] carrying whatever
 * message text is available and an empty [ApiError.fieldErrors] — never throws, so a surprising
 * error shape can't crash the submit flow (it just falls back to the page-level banner).
 */
object ApiErrorParser {
  private val gson = Gson()

  private data class ApiErrorBody(
    val message: String? = null,
    val errorCode: String? = null,
    val fieldErrors: Map<String, String>? = null,
  )

  fun parse(body: String?): ApiError {
    if (body.isNullOrBlank()) return ApiError(message = null, errorCode = null, fieldErrors = emptyMap())
    val parsed = runCatching { gson.fromJson(body, ApiErrorBody::class.java) }.getOrNull()
      ?: return ApiError(message = body, errorCode = null, fieldErrors = emptyMap())
    return ApiError(
      message = parsed.message ?: body,
      errorCode = parsed.errorCode,
      fieldErrors = parsed.fieldErrors ?: emptyMap(),
    )
  }
}
