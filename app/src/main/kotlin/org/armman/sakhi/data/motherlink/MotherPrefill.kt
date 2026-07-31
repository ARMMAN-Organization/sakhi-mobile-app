package org.armman.sakhi.data.motherlink

import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.MOTHER_DOB_QUESTION_CODE

/**
 * Child-enrollment question codes this prefill writes. Kept here rather than in the ViewModel so the
 * whole mapping is reviewable in one place against the form spec.
 *
 * Backend typos (`beneficary_pada_name`, `beneficary_phc_name`) are reproduced verbatim — they are
 * the live `question_code`s and "fixing" them here would silently stop the mapping from applying.
 */
object MotherPrefillQuestionCodes {
  const val MOTHER_BENEFICIARY_ID = "mother_beneficiary_id"
  const val CAREGIVER_NAME = "caregiver_name_first_name_middle_name_last_name"
  /** Aliased rather than re-declared: the prefill and [org.armman.sakhi.data.forms.FormDateRuleset]
   * must always target the same field. Two independent literals is precisely how the picker bounds
   * and this prefill silently drifted apart from the published schema. */
  const val MOTHER_DATE_OF_BIRTH = MOTHER_DOB_QUESTION_CODE
  const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"
}

/**
 * Copies a selected mother's record onto a child-enrollment draft (CR-031, spec rows 1, 4, 9, 12–20).
 *
 * Pure and Android-free so the whole mapping is unit-testable. Two rules the callers depend on:
 *
 * 1. **A field is only written when the value is actually usable.** A blank name, a null DOB, or a
 *    geography id the backend did not ship for this Sakhi are skipped, not written as empty or as a
 *    foreign id. Writing an unshipped `geographyUnitId` is precisely what produced
 *    `pii.phcId does not refer to a known geography unit` (HTTP 422) on the mother flow.
 * 2. **[Result.prefilledCodes] lists exactly what was written** — never what was skipped. That set
 *    drives the "From mother's record" hint and, via [clear], what a path switch is allowed to
 *    remove. Getting it wrong either lies to the Sakhi or deletes something she typed.
 *
 * Deliberately **not** prefilled:
 * - `project_name` — its option `valueCode` is the project *name* from the Sakhi profile, not the
 *   `projectId` UUID the list endpoint returns. It is already auto-selected on form load.
 * - every Infant Details field (`date_of_birth_of_infant`, `name_of_the_child`, `sex_of_child`,
 *   birth weight/length …) — those describe the baby, not the mother.
 * - rows 21–34 (address, mobile, phone owner, education, income …) — they live only in the mother's
 *   `MOTHER_REGISTRATION.formData`, which needs the submissions-read endpoint (CR-032).
 * - consent video/audio/photo (rows 2, 3, 5) — the mother's consent photo is never uploaded
 *   anywhere, only a device file path sits in her `formData` (CR-028).
 */
object MotherPrefill {

  /**
   * Whether "Did we receive consent?" is inherited from the mother (spec row 4, decision D2).
   *
   * The spec asks for this. It is also a live risk: the infant is a distinct beneficiary and
   * inherited consent is a legal/ethical exposure (plan risk R1, pending written ARMMAN sign-off).
   * Isolated to this one constant so reversing it is a one-line change with no other edits and no
   * schema dependency.
   */
  const val INHERIT_CONSENT_FROM_MOTHER: Boolean = true

  data class Result(
    val answers: FormAnswers,
    val prefilledCodes: Set<String>,
  )

  /**
   * Applies [mother] (and her [consent], when available) onto [answers].
   *
   * [geography] is the active [org.armman.sakhi.data.forms.FormVersion.geography] — the only
   * `geographyUnitId`s the backend's `/beneficiaries` validation recognises. A mother living outside
   * the units shipped for this Sakhi simply doesn't prefill those levels; the values already
   * auto-selected from the Sakhi's own assignment stay in place.
   *
   * Existing answers for a target code are overwritten: selecting a mother is an explicit act by the
   * Sakhi and outranks an earlier auto-selected geography value or a resumed draft.
   */
  fun apply(
    answers: FormAnswers,
    mother: LinkedMother,
    consent: LinkedMotherConsent?,
    geography: List<FormGeographyUnit>,
  ): Result {
    var next = answers
    val written = mutableSetOf<String>()

    fun write(questionCode: String, value: String?) {
      if (value.isNullOrBlank()) return
      next = next.withSingleValue(questionCode, value)
      written += questionCode
    }

    write(MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID, mother.id)
    write(MotherPrefillQuestionCodes.CAREGIVER_NAME, mother.fullName)
    write(MotherPrefillQuestionCodes.MOTHER_DATE_OF_BIRTH, mother.dateOfBirth?.toString())

    geographyPairs(mother).forEach { (questionCode, unitId) ->
      // `?.takeIf` (not `.takeIf`): a mother missing a level has a null id there, and the safe call
      // both skips the check and smart-casts `it` to the non-null String [isShipped] takes. `write`
      // ignores nulls anyway, so an absent level simply doesn't prefill.
      write(questionCode, unitId?.takeIf { isShipped(questionCode, it, geography) })
    }

    // Only ever auto-answer "yes". Auto-answering "no" would trip the consent-refused gate and stop
    // the registration on the Sakhi's behalf, based on a record about a different beneficiary.
    if (INHERIT_CONSENT_FROM_MOTHER && consent?.consentGiven == true) {
      write(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT, VALUE_YES)
    }

    return Result(answers = next, prefilledCodes = written)
  }

  /**
   * Removes the still-prefilled answers in [prefilledCodes] — used when the Sakhi switches to the
   * direct path, where a linked mother's values no longer apply.
   *
   * A code the Sakhi has edited has already been dropped from `prefilledCodes` by the ViewModel, so
   * it is untouched here: her own typing survives a path switch, the inherited values do not.
   */
  fun clear(answers: FormAnswers, prefilledCodes: Set<String>): FormAnswers {
    var next = answers
    prefilledCodes.forEach { next = next.withSingleValue(it, null) }
    return next
  }

  /** Question code → the mother's `geographyUnitId` for that level. */
  private fun geographyPairs(mother: LinkedMother): List<Pair<String, String?>> = listOf(
    GeographyQuestionCodes.STATE to mother.stateId,
    GeographyQuestionCodes.DISTRICT to mother.districtId,
    // talukaId, NOT healthBlockId: ChildRegistrationSubmissionMapper sends this answer as
    // `pii.talukaId` and hardcodes `healthBlockId = null`. The live data has them equal, which would
    // hide a mix-up here until it didn't.
    GeographyQuestionCodes.BLOCK_TALUKA to mother.talukaId,
    GeographyQuestionCodes.VILLAGE to mother.villageId,
    GeographyQuestionCodes.PADA to mother.padaId,
    GeographyQuestionCodes.PHC to mother.phcId,
    GeographyQuestionCodes.SUB_CENTRE to mother.healthSubCentreId,
  )

  /** Whether [unitId] is a unit the backend shipped for this form version *at this level*. Matching
   * on `geoType` too, so a village id arriving in the pada slot is rejected rather than submitted. */
  private fun isShipped(
    questionCode: String,
    unitId: String,
    geography: List<FormGeographyUnit>,
  ): Boolean {
    val geoType = GeographyQuestionCodes.QUESTION_CODE_TO_GEO_TYPE[questionCode] ?: return false
    return geography.any { it.geographyUnitId == unitId && it.geoType == geoType }
  }

  private const val VALUE_YES = "yes"
}
