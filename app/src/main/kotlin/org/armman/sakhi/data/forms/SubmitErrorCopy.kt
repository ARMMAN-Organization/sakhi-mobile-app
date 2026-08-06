package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.DuplicateOutcomeParser

/**
 * Turns a backend submit failure into one short sentence the Sakhi can act on.
 *
 * The banner used to render the exception's own message, which embedded the verbatim response body
 * — the Sakhi saw
 * `POST /beneficiaries failed: HTTP 400 — {"success":false,"message":"motherDetails.lmpDate: …",
 * "errorCode":"VALIDATION_ERROR","traceId":"5c42c1e5…","fieldErrors":{…}}`.
 * Nothing in that is useful to her, and the traceId/HTTP noise buries the one sentence that is.
 *
 * The raw body is still kept: it stays on
 * [DynamicFormSubmissionException.BeneficiaryCreationFailed.body] and is written to the draft's
 * `lastErrorMessage` debug column (see `DynamicFormSyncExecutor.markFailed`). Only what the *UI*
 * reads changes.
 *
 * Note this surfaces the backend's English text as-is (minus the DTO path). Localising it would
 * mean mapping backend field paths onto the existing `enrollment_error_*` strings, which already
 * have Marathi copy — that needs resource lookup in the ViewModel and is deliberately left to its
 * own CR rather than half-done here.
 */
object SubmitErrorCopy {

  /** Shown when the backend gave us nothing quotable (5xx, empty body, non-envelope JSON). */
  const val GENERIC = "Could not submit the form. Please try again."

  /** Leading dotted DTO path the backend prepends to `message` (`motherDetails.lmpDate: …`). */
  private val PATH_PREFIX = Regex("""^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+:\s*""")

  /**
   * DTO leaf names the backend starts its messages with, in the Sakhi-facing form. Without this,
   * "lmpDate cannot be in the future" reads as code; a generic camelCase splitter would produce
   * "Lmp Date", which is worse. Only the fields [BeneficiaryFieldErrorMapper] already knows about
   * are listed — an unlisted token is left untouched rather than mangled.
   */
  private val FIELD_LABELS = mapOf(
    "lmpDate" to "LMP date",
    "firstName" to "First name",
    "middleName" to "Middle name",
    "lastName" to "Last name",
    "dateOfBirth" to "Date of birth",
    "addressLine" to "Address",
    "rchNumber" to "RCH number",
    "phone" to "Mobile number",
    "registrationDate" to "Registration date",
    "gravida" to "Gravida",
    "parity" to "Para",
    "liveBirths" to "Living children",
    "stillbirths" to "Still births",
    "abortions" to "Abortions",
    "deadChildren" to "Dead children",
  )

  /**
   * The banner sentence for a failed submit.
   *
   * Per-field messages win when present — they name what to change. Otherwise the envelope's
   * `message`, provided it isn't just the raw body echoed back (which is what [ApiErrorParser]
   * hands over when the response isn't the expected envelope). Anything else falls back to
   * [GENERIC].
   */
  fun forApiError(
    message: String?,
    fieldErrors: Map<String, String>,
    violations: List<String> = emptyList(),
  ): String {
    // A duplicate `409` puts machine-readable detail under `fieldErrors` (reason/
    // existingBeneficiaryId/resolution) rather than per-field validation copy. Rendering those
    // showed the Sakhi "RE_ENROLLMENT" and "Resubmit with acknowledgeDuplicate: true…"; they are
    // consumed by DuplicateOutcomeParser instead. See DuplicateOutcomeParser.DETAIL_KEYS.
    val fromFields = fieldErrors
      .filterKeys { it !in DuplicateOutcomeParser.DETAIL_KEYS }
      .values
      .mapNotNull { humanize(it) }
      .distinct()
    if (fromFields.isNotEmpty()) return fromFields.joinToString("\n")
    // Schema-validator violations (422 from the submissions endpoint) name a question_code, not a
    // DTO path, so they can't be pinned to a field — but they are still far more useful than the
    // envelope's generic "Submission failed validation." See ApiError.violations.
    val fromViolations = violations.mapNotNull { humanize(it) }.distinct()
    if (fromViolations.isNotEmpty()) return fromViolations.joinToString("\n")
    return humanize(message) ?: GENERIC
  }

  /**
   * [raw] with the DTO path prefix removed and, if it then opens with a known DTO leaf name, that
   * token replaced by a readable label. Returns null when there is nothing worth showing — blank,
   * or a raw JSON/HTML body that would put us right back to dumping the response on screen.
   *
   * Anything else is passed through verbatim. Deliberately no sentence-casing or other cosmetic
   * rewriting of messages we don't recognise: the backend already writes most of them as proper
   * sentences, and quietly editing text we don't understand risks making it read worse, not better.
   */
  fun humanize(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || looksLikeRawBody(trimmed)) return null
    val withoutPath = trimmed.replace(PATH_PREFIX, "")
    if (withoutPath.isEmpty()) return null
    val token = withoutPath.substringBefore(' ')
    val label = FIELD_LABELS[token] ?: return withoutPath
    return label + withoutPath.removePrefix(token)
  }

  private fun looksLikeRawBody(value: String): Boolean =
    value.startsWith("{") || value.startsWith("[") || value.startsWith("<")
}
