package org.armman.sakhi.data.enrollment

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Typed view of the backend's standard error envelope, parsed from the Retrofit `errorBody()`
 * string. Three shapes are handled, all confirmed against real responses in `api-calls.jsonl`:
 *
 *  - `400 VALIDATION_ERROR` — `fieldErrors` is a map of dotted DTO path → message
 *    (e.g. `{"pii.firstName":"String must contain at least 1 character(s)"}`). The only shape that
 *    can be attributed to individual form fields; see [BeneficiaryFieldErrorMapper].
 *  - `422 UNPROCESSABLE` from the **submissions** endpoint — `fieldErrors` is a map whose value is
 *    an ARRAY, not a string: `{"violations":["Missing required field: mother_beneficiary_id"]}`.
 *    These come from the backend's schema validator (`form-validation.ts`), name a
 *    `question_code` rather than a DTO path, and are not DTO-attributable — they surface as a
 *    page-level banner via [violations].
 *  - `422` / anything else — a plain `message` with no `fieldErrors`
 *    (e.g. `"pii.phcId does not refer to a known geography unit."`).
 *
 * The array shape used to break parsing outright: `fieldErrors` was typed `Map<String, String>`, so
 * Gson threw on the array value, the parser fell back to `message = body`, and the Sakhi was shown
 * the entire raw JSON envelope — traceId and all. [violations] exists so that shape degrades to a
 * readable sentence instead.
 *
 * [fieldErrors] and [violations] are never null — absent means empty, so callers don't null-check.
 */
data class ApiError(
  val message: String?,
  val errorCode: String?,
  val fieldErrors: Map<String, String>,
  /** Schema-validator messages from a `fieldErrors` array value (see the `422` shape above). */
  val violations: List<String> = emptyList(),
)

/**
 * Parses a raw JSON error body into an [ApiError], defensively: a null/blank body, a body that
 * isn't the expected envelope, or malformed JSON all degrade to an [ApiError] carrying whatever
 * message text is available and empty [ApiError.fieldErrors] — never throws, so a surprising error
 * shape can't crash the submit flow (it just falls back to the page-level banner).
 */
object ApiErrorParser {
  private val gson = Gson()

  /**
   * `fieldErrors` is read as raw [JsonElement]s rather than a typed map because the backend uses the
   * same key for two different value types (string per DTO path, array per validator). Typing it
   * either way makes Gson throw on the other.
   */
  private data class ApiErrorBody(
    val message: String? = null,
    val errorCode: String? = null,
    val fieldErrors: JsonObject? = null,
  )

  fun parse(body: String?): ApiError {
    if (body.isNullOrBlank()) return ApiError(message = null, errorCode = null, fieldErrors = emptyMap())
    val parsed = runCatching { gson.fromJson(body, ApiErrorBody::class.java) }.getOrNull()
      ?: return ApiError(message = body, errorCode = null, fieldErrors = emptyMap())

    val fieldErrors = mutableMapOf<String, String>()
    val violations = mutableListOf<String>()
    parsed.fieldErrors?.entrySet()?.forEach { (key, value) ->
      when {
        value.isJsonArray -> value.asJsonArray.forEach { element ->
          asStringOrNull(element)?.let(violations::add)
        }
        else -> asStringOrNull(value)?.let { fieldErrors[key] = it }
      }
    }

    return ApiError(
      message = parsed.message ?: body,
      errorCode = parsed.errorCode,
      fieldErrors = fieldErrors,
      violations = violations,
    )
  }

  /** Primitive → its string form; anything else (nested object, null) is dropped rather than
   * stringified into JSON that would end up on screen. */
  private fun asStringOrNull(element: JsonElement): String? =
    if (element.isJsonPrimitive) element.asString.takeIf { it.isNotBlank() } else null
}
