package org.armman.sakhi.data.motherlink

import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.MOTHER_DOB_QUESTION_CODE
import org.armman.sakhi.data.lookup.LookupLabelMatcher
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

  /**
   * The mother's age, CHILD_REGISTRATION's sibling field to [MOTHER_DATE_OF_BIRTH] (CR-039).
   * Per CR-039 this field is, by design, independently Sakhi-editable on the DIRECT path (spec's
   * "either DOB or age" fallback) — see [MOTHER_DOB_QUESTION_CODE]'s own doc.
   *
   * However, once a registered mother is linked via [MotherPrefill.apply], her real DOB is known,
   * so leaving this field manually editable let the Sakhi type an age that contradicts the
   * prefilled DOB (reported bug: "Age field is editable even when Mother DOB is already fetched
   * using Beneficiary ID"). [MotherPrefill.apply] now also derives and writes this field from
   * [LinkedMother.dateOfBirth] whenever it's able to, and includes it in [Result.prefilledCodes] so
   * the host screen can render it read-only for exactly as long as it stays prefilled — the same
   * mechanism [DID_WE_RECEIVE_CONSENT] and every other prefilled field already use for the "From
   * mother's record" hint, reused here (via `readOnlyQuestionCodes`) to also lock the field shut.
   */
  const val MOTHER_AGE = "mother_age"

  const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"

  // Rows 21–34 (CR-032) — confirmed against the live CHILD_REGISTRATION schema's `question_code`s
  // (api-calls-live.jsonl), not guessed from the CSV spec's wording.
  const val ADDRESS = "enter_the_beneficiary_address"
  const val MOBILE_NUMBER = "mobile_number"
  const val PHONE_OWNER = "who_owns_the_phone"
  const val MOBILE_NETWORK_AVAILABILITY = "availability_of_mobile_network"
  const val EDUCATION_LEVEL = "what_is_the_highest_level_of_education_you_have_completed"
  const val PARTNER_EDUCATION_LEVEL = "what_is_the_highest_level_of_education_your_partner_have_completed"
  const val PARTNER_OCCUPATION = "what_is_the_occupation_of_your_partner"
  const val YEARS_IN_VILLAGE = "since_when_have_you_been_staying_in_this_village"
  const val MIGRATION_PATTERN = "which_of_the_following_best_describes_your_household_s_migration_pattern"
  const val MONTHLY_INCOME = "what_is_the_income_of_the_family_per_month"
  const val RELIGION = "what_is_your_religion"
  const val SOCIAL_CATEGORY = "what_is_your_category"
  const val FAMILY_MEMBERS_COUNT = "how_many_family_members_in_your_household_including_children_under_5_years_of_age"
  const val CHILDREN_UNDER_5_COUNT = "how_many_children_under_5_years_of_age_are_in_your_household"
}

/**
 * Copies a selected mother's record onto a child-enrollment draft (CR-031/CR-032, spec rows 1, 4, 9,
 * 12–34).
 *
 * Pure and Android-free so the whole mapping is unit-testable. Two rules the callers depend on:
 *
 * 1. **A field is only written when the value is actually usable.** A blank name, a null DOB, a
 *    geography id the backend did not ship for this Sakhi, or a socio-demographic answer that
 *    doesn't confidently match a form option is skipped, not written as empty or as a wrong code.
 *    Writing an unshipped `geographyUnitId` is precisely what produced
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
 * - consent video/audio/photo (rows 2, 3, 5) — the mother's consent photo is never uploaded
 *   anywhere, only a device file path sits in her `formData` (CR-028).
 * - rows 35–49 (delivery details, infant-at-birth details) — no Delivery form exists yet to source
 *   them from.
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
   * Applies [mother] (and her [consent]/[socioDemographics], when available) onto [answers].
   *
   * [geography] is the active [org.armman.sakhi.data.forms.FormVersion.geography] — the only
   * `geographyUnitId`s the backend's `/beneficiaries` validation recognises. A mother living outside
   * the units shipped for this Sakhi simply doesn't prefill those levels; the values already
   * auto-selected from the Sakhi's own assignment stay in place.
   *
   * [formSchema] is the active [org.armman.sakhi.data.forms.FormVersion.schemaJson] — the source of
   * truth for each socio-demographic question's own option `value_code`s. [socioDemographics]'s
   * lookup answers arrive as a `{categoryCode, label}` from `beneficiary-service`'s own resolved
   * lookup, in its own upper-snake-case convention; that never matches the form schema's
   * lower-snake-case `value_code` byte-for-byte, so each is translated via [LookupLabelMatcher]
   * against the *live* schema's options for that question, never a hardcoded translation table that
   * could drift from a schema change.
   *
   * Existing answers for a target code are overwritten: selecting a mother is an explicit act by the
   * Sakhi and outranks an earlier auto-selected geography value or a resumed draft.
   */
  fun apply(
    answers: FormAnswers,
    mother: LinkedMother,
    consent: LinkedMotherConsent?,
    geography: List<FormGeographyUnit>,
    socioDemographics: MotherSocioDemographics? = null,
    formSchema: List<FormFieldSchema> = emptyList(),
    /** The child registration's own "today" — the same reference point
     * [org.armman.sakhi.data.forms.FormComputedFieldEvaluator] uses for every other DOB-derived
     * age. Defaulted to [LocalDate.now] only so existing callers/tests that don't care about
     * [MotherPrefillQuestionCodes.MOTHER_AGE] don't all need updating; the real ViewModel call site
     * always passes its own `registrationDate` explicitly. */
    registrationDate: LocalDate = LocalDate.now(),
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
    // Derived, not typed — see MOTHER_AGE's doc for why this field must stop being freely
    // Sakhi-editable once the mother's real DOB is known. `write` already skips null/blank, so a
    // mother with no usable DOB (MAP-06) simply leaves mother_age untouched and still editable.
    write(
      MotherPrefillQuestionCodes.MOTHER_AGE,
      mother.dateOfBirth?.let { dob -> ChronoUnit.YEARS.between(dob, registrationDate).toString() },
    )

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

    if (socioDemographics != null) {
      write(MotherPrefillQuestionCodes.ADDRESS, socioDemographics.address)
      write(MotherPrefillQuestionCodes.MOBILE_NUMBER, socioDemographics.mobileNumber)
      write(MotherPrefillQuestionCodes.YEARS_IN_VILLAGE, socioDemographics.yearsInVillage?.toString())
      write(MotherPrefillQuestionCodes.FAMILY_MEMBERS_COUNT, socioDemographics.familyMembersCount?.toString())
      write(MotherPrefillQuestionCodes.CHILDREN_UNDER_5_COUNT, socioDemographics.childrenUnder5Count?.toString())

      fun writeLookup(questionCode: String, answer: MotherLookupAnswer?) {
        write(questionCode, LookupLabelMatcher.match(answer?.label, optionsFor(questionCode, formSchema)))
      }
      writeLookup(MotherPrefillQuestionCodes.PHONE_OWNER, socioDemographics.phoneOwner)
      writeLookup(MotherPrefillQuestionCodes.MOBILE_NETWORK_AVAILABILITY, socioDemographics.mobileNetworkAvailability)
      writeLookup(MotherPrefillQuestionCodes.EDUCATION_LEVEL, socioDemographics.educationLevel)
      writeLookup(MotherPrefillQuestionCodes.PARTNER_EDUCATION_LEVEL, socioDemographics.partnerEducationLevel)
      writeLookup(MotherPrefillQuestionCodes.PARTNER_OCCUPATION, socioDemographics.partnerOccupation)
      writeLookup(MotherPrefillQuestionCodes.MIGRATION_PATTERN, socioDemographics.migrationPattern)
      writeLookup(MotherPrefillQuestionCodes.MONTHLY_INCOME, socioDemographics.monthlyIncome)
      writeLookup(MotherPrefillQuestionCodes.RELIGION, socioDemographics.religion)
      writeLookup(MotherPrefillQuestionCodes.SOCIAL_CATEGORY, socioDemographics.socialCategory)
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

  /** [questionCode]'s own `(value_code, label)` option pairs from the live [formSchema] — empty
   * (never a hardcoded fallback list) when the question isn't in the schema, so a renamed or removed
   * question simply stops matching instead of writing a code the current form no longer defines. */
  private fun optionsFor(questionCode: String, formSchema: List<FormFieldSchema>): List<Pair<String, String>> =
    formSchema.firstOrNull { it.questionCode == questionCode }
      ?.options.orEmpty()
      .map { it.valueCode to it.label }

  private const val VALUE_YES = "yes"
}
