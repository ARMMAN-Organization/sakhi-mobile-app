package org.armman.sakhi.data.forms

/**
 * Reverse of [DynamicFormSubmissionMapper]'s `question_code → DTO field` routing: turns the
 * backend's `fieldErrors` map (keyed by dotted DTO path, e.g. `pii.fullName`,
 * `motherDetails.stillbirths`) back into per-`question_code` errors the dynamic form can render
 * inline on the exact field the Sakhi filled.
 *
 * This table MUST stay in sync with [DynamicFormSubmissionMapper.toCreateBeneficiaryRequest] and
 * [GeographyQuestionCodes] — if the forward mapper ever reads a different `question_code` into a
 * DTO field, the matching entry here has to change too, or that field's server error silently
 * stops being attributable (it falls back to the page-level banner instead of an inline message).
 *
 * A DTO path with no entry here (a new backend field, or one with no single form-field home like
 * `case.projectId`) is dropped rather than guessed at — its message still reaches the Sakhi via the
 * page-level banner, it just can't be pinned to a field. Scope is deliberately the
 * `VALIDATION_ERROR` (400) `fieldErrors` contract only; `422 UNPROCESSABLE` carries no `fieldErrors`
 * and never reaches this mapper with anything to map.
 */
object BeneficiaryFieldErrorMapper {

  /** Every `question_code` shape the beneficiary-name field has used, most-likely-live first —
   * see [BeneficiaryNameQuestionCodes]'s doc for the full timeline this must track. Kept as an
   * ordered list (not the unordered set that object exports) because priority matters here: today
   * the combined field is what's actually in the schema, but the split `first_name` question is
   * still a real possibility if this field reverts again. */
  private val NAME_QUESTION_CODE_FALLBACK_ORDER: List<String> = listOf(
    BeneficiaryNameQuestionCodes.CURRENT,
    BeneficiaryNameQuestionCodes.LEGACY_TYPO,
    "first_name",
  )

  /** `pii.fullName` — the CURRENT live error path (2026-08-06: the backend reverted to a single
   * joined `fullName`, see [BeneficiaryPiiDto][org.armman.sakhi.data.enrollment
   * .BeneficiaryPiiDto]'s doc). Always resolved via [NAME_QUESTION_CODE_FALLBACK_ORDER], never a
   * 1:1 [PATH_TO_QUESTION_CODE] entry, because ONE message here can only ever be pinned to ONE
   * field, and which field that should be depends on which shape the live schema is in. */
  private const val FULL_NAME_PATH = "pii.fullName"

  /** `pii.firstName`/`middleName`/`lastName` are each direct-mapped in [PATH_TO_QUESTION_CODE]
   * (unlike [FULL_NAME_PATH], a 400 sending these DOES know which specific field is wrong). They
   * only fall through to [NAME_QUESTION_CODE_FALLBACK_ORDER] when their direct target isn't in the
   * active schema at all — e.g. a schema that has gone back to a combined field, so `first_name`
   * no longer exists to pin `pii.firstName`'s message to. Kept rather than deleted because an
   * older cached response could in principle still send these, same reasoning as the
   * multi-spelling sets elsewhere in this codebase (never delete a spelling the backend might
   * still send). */
  private val SPLIT_NAME_PATHS = setOf("pii.firstName", "pii.middleName", "pii.lastName")

  /** Registration date has no single `question_code`: published schemas spell it two ways (see
   * [REGISTRATION_DATE_QUESTION_CODES]), so its error is pinned to whichever spelling the active
   * schema declares. */
  private const val REGISTRATION_DATE_PATH = "case.registrationDate"

  /** Dotted DTO path → the `question_code` the forward mapper read it from. Geography paths use
   * [GeographyQuestionCodes]; the rest mirror `DynamicFormSubmissionMapper.QuestionCode`. */
  private val PATH_TO_QUESTION_CODE: Map<String, String> = mapOf(
    // pii.fullName is NOT listed here — see FULL_NAME_PATH's doc for why it always goes through
    // the fallback order instead of a fixed target.
    "pii.firstName" to "first_name",
    "pii.middleName" to "middle_name",
    "pii.lastName" to "last_name",
    "pii.phone" to "mobile_number",
    "pii.dateOfBirth" to "date_of_birth",
    "pii.addressLine" to "enter_the_beneficiary_address",
    "pii.rchNumber" to "input_rch_number",
    "pii.villageId" to GeographyQuestionCodes.VILLAGE,
    "pii.padaId" to GeographyQuestionCodes.PADA,
    "pii.healthSubCentreId" to GeographyQuestionCodes.SUB_CENTRE,
    "pii.phcId" to GeographyQuestionCodes.PHC,
    "pii.stateId" to GeographyQuestionCodes.STATE,
    "pii.districtId" to GeographyQuestionCodes.DISTRICT,
    "pii.talukaId" to GeographyQuestionCodes.BLOCK_TALUKA,
    "motherDetails.lmpDate" to "lmp_date",
    "motherDetails.gravida" to "gravida_total_number_of_pregnancies",
    "motherDetails.parity" to "para_number_of_births_after_24_weeks",
    "motherDetails.liveBirths" to "living_children",
    "motherDetails.stillbirths" to "still_births",
    "motherDetails.abortions" to "abortions_pregnancy_losses_before_24_weeks",
    "motherDetails.deadChildren" to "dead_children",
    // case.registrationDate is resolved in `resolve` — see REGISTRATION_DATE_PATH.
  )

  /**
   * Maps [fieldErrors] (DTO-path keyed) to `question_code → message`, keeping only paths that
   * resolve to a `question_code` present in [knownQuestionCodes] (the active schema's fields). When
   * several DTO paths collapse to the same `question_code` (e.g. `firstName`+`lastName` →
   * combined-name fallback), the first message wins so the field shows one error, not a clobbered
   * mix. Insertion order is preserved so "first errored field" selection downstream is stable.
   */
  fun toQuestionCodeErrors(
    fieldErrors: Map<String, String>,
    knownQuestionCodes: Set<String>,
  ): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    fieldErrors.forEach { (path, message) ->
      val questionCode = resolve(path, knownQuestionCodes) ?: return@forEach
      result.putIfAbsent(questionCode, message)
    }
    return result
  }

  private fun resolve(path: String, known: Set<String>): String? {
    if (path == FULL_NAME_PATH) return NAME_QUESTION_CODE_FALLBACK_ORDER.firstOrNull { it in known }

    val direct = PATH_TO_QUESTION_CODE[path]
    if (direct != null && direct in known) return direct

    // direct's target (e.g. first_name) isn't in this schema at all — the schema has moved to a
    // combined field, so fall back the same way FULL_NAME_PATH does rather than dropping the
    // message entirely.
    if (path in SPLIT_NAME_PATHS) return NAME_QUESTION_CODE_FALLBACK_ORDER.firstOrNull { it in known }

    if (path == REGISTRATION_DATE_PATH) return REGISTRATION_DATE_QUESTION_CODES.firstOrNull { it in known }
    return null
  }
}
