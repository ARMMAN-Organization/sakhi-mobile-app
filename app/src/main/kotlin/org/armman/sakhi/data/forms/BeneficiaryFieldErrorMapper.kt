package org.armman.sakhi.data.forms

/**
 * Reverse of [DynamicFormSubmissionMapper]'s `question_code → DTO field` routing: turns the
 * backend's `fieldErrors` map (keyed by dotted DTO path, e.g. `pii.firstName`,
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

  /** The v1 single combined-name question, kept only as a fallback for older schema versions that
   * predate the `first_name`/`middle_name`/`last_name` split — see [DynamicFormSubmissionMapper]'s
   * `QuestionCode` doc for that history. */
  private const val COMBINED_NAME_QUESTION_CODE = "beneficary_name_first_name_middle_name_last_name"

  /** DTO name paths that fall back to [COMBINED_NAME_QUESTION_CODE] when the split fields aren't in
   * the active schema. */
  private val NAME_PATHS = setOf("pii.firstName", "pii.middleName", "pii.lastName")

  /** Dotted DTO path → the `question_code` the forward mapper read it from. Geography paths use
   * [GeographyQuestionCodes]; the rest mirror `DynamicFormSubmissionMapper.QuestionCode`. */
  private val PATH_TO_QUESTION_CODE: Map<String, String> = mapOf(
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
    "case.registrationDate" to "registrtion_date",
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
    val direct = PATH_TO_QUESTION_CODE[path]
    if (direct != null && direct in known) return direct
    // Split-name fields absent (older schema) — pin name errors to the combined-name field instead.
    if (path in NAME_PATHS && COMBINED_NAME_QUESTION_CODE in known) return COMBINED_NAME_QUESTION_CODE
    return null
  }
}
