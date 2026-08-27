package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.forms.BeneficiaryNameQuestionCodes
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.DOB_QUESTION_CODE
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.motherlink.MotherPrefillQuestionCodes
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Builds the CR-042 `CHILD_REGISTRATION` prefill from a submitted `DELIVERY_VISIT` form's own
 * answers, for the child at [childIndex] (0-based — see [DeliveryQuestionCodes]'s own doc), plus
 * whatever's known about the mother herself ([motherAnswers], [motherGeography]) — see
 * [motherGeographyAnswersFor]'s own doc. Every field here is something the Sakhi already answered
 * once, either on the delivery form or on the mother's own MOTHER_REGISTRATION; the point is she
 * shouldn't have to answer it again for facts that don't change between forms (a birth weight
 * recorded five minutes ago isn't going to be re-measured for CHILD_REGISTRATION, and the mother's
 * village isn't going to be different for her own child).
 *
 * Deliberately narrow beyond that: only fields BOTH forms genuinely share the same concept for are
 * prefilled. `any_diformity_observed_in_the_newborn` and
 * `what_was_the_child_fed_immediately_after_birth` have no clean equivalent on `DELIVERY_VISIT`
 * (crying-at-birth is a different concept from feeding, "related complications" is not the same
 * framing as "diformity observed") and are left for the Sakhi to answer fresh rather than guessed at.
 *
 * `mother_beneficiary_id` is a confirmed-dead schema field (backend team, 2026-08-19): no server
 * code reads it by name, it's `number`-typed so a real UUID would coerce to `NaN` -> `null` on
 * submission anyway, and the real mother<->child link is `case.motherBeneficiaryId` on the
 * structured `POST /beneficiaries` payload, set server-side at auto-create time (CR-041). It only
 * needs a placeholder to satisfy its own `required: true` + `number` schema constraint — see
 * [DEAD_MOTHER_BENEFICIARY_ID_PLACEHOLDER].
 *
 * ### Mother's own personal/socio-demographic details (2026-08-19)
 * Beyond geography, this also copies the mother's name, DOB/age, mobile number, address, consent,
 * and household socio-demographics onto the child's registration — the same set
 * [org.armman.sakhi.data.motherlink.MotherPrefill.apply] copies for the standalone
 * registered-mother path (CR-031/CR-032), requested so this flow doesn't feel like a worse-covered
 * twin of that one. Two things make this SIMPLER and more reliable than that path, not a smaller
 * copy of it:
 *  1. No network call: [motherAnswers] is read straight from the mother's own local
 *     MOTHER_REGISTRATION draft ([org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
 *     .answersFor]), not fetched from `beneficiary-service`.
 *  2. No label translation: [org.armman.sakhi.data.motherlink.MotherPrefill.apply]'s socio-demographic
 *     answers arrive as backend-resolved `{categoryCode, label}` pairs and need
 *     [org.armman.sakhi.data.lookup.LookupLabelMatcher] to translate them back to a `value_code`
 *     the schema recognizes. Here the mother's own answers are ALREADY `value_code`s in her own
 *     MOTHER_REGISTRATION submission, and — confirmed against [org.armman.sakhi.data.forms
 *     .DynamicFormSubmissionMapper]'s own `question_code` literals — several of these fields
 *     (address, mobile number, consent) use the byte-identical `question_code` on both forms, so a
 *     verbatim copy is correct with no translation step at all.
 *
 * The household socio-demographic block (education, occupation, migration, income, religion,
 * category, years in village, family counts, phone owner, network availability — CR-032's rows
 * 21–34) is copied the same verbatim way, on the same evidence: these rows sit immediately
 * alongside address/mobile in that same numbered block, sharing the one underlying spec both forms
 * draw from. Unlike address/mobile this hasn't been individually confirmed against a live
 * MOTHER_REGISTRATION schema dump — but the copy is fail-safe either way: [motherDetailAnswersFor]
 * only ever writes a code that is actually present (non-blank) in the mother's own answers, so a
 * code that doesn't exist on MOTHER_REGISTRATION (or a value in a genuinely different vocabulary)
 * simply doesn't copy, exactly today's behavior — it can't silently write a wrong answer under a
 * code the mother was never asked.
 */
object DeliveryToChildRegistrationPrefill {

  private const val SEX_OF_CHILD = "sex_of_child"
  private const val CHILD_LENGTH_AT_BIRTH_CM = "child_length_at_birth_in_cm"
  private val CHILD_WEIGHT_AT_BIRTH_KG = ChildRegistrationQuestionCodes.CHILD_WEIGHT_AT_BIRTH_KG
  private const val DID_THE_BABY_HAVE_COMPLICATIONS = "did_the_baby_have_any_complications_at_the_time_of_birth"

  /** Any non-blank placeholder satisfies `mother_beneficiary_id`'s `required: true` + `number`
   * schema constraint with zero functional effect — see this object's own class doc for why a real
   * id is neither available nor needed here. Not derived from anything; a fixed constant is the
   * point, so nobody re-reads this expecting it to mean something. */
  private const val DEAD_MOTHER_BENEFICIARY_ID_PLACEHOLDER = "1"

  private val MOTHER_GEOGRAPHY_QUESTION_CODES = listOf(
    GeographyQuestionCodes.STATE,
    GeographyQuestionCodes.DISTRICT,
    GeographyQuestionCodes.BLOCK_TALUKA,
    GeographyQuestionCodes.VILLAGE,
    GeographyQuestionCodes.PADA,
    GeographyQuestionCodes.PHC,
    GeographyQuestionCodes.SUB_CENTRE,
  )

  /** MOTHER_REGISTRATION's own split-name fallback codes (pre-2026-08-06 schema / a resumed old
   * draft) — mirrors [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource.fullName]'s
   * own fallback rather than re-deriving a third copy of this logic; see
   * [BeneficiaryNameQuestionCodes]'s doc for why the combined field is tried first. */
  private const val FIRST_NAME = "first_name"
  private const val MIDDLE_NAME = "middle_name"
  private const val LAST_NAME = "last_name"

  /**
   * Household socio-demographic + contact `question_code`s CHILD_REGISTRATION and
   * MOTHER_REGISTRATION share byte-for-byte (CR-032's rows 21–34) — copied verbatim from the
   * mother's own answers, no translation. See this object's class doc for the confidence level
   * behind this list (ADDRESS/MOBILE_NUMBER confirmed against a live schema; the rest inferred from
   * sitting in the same numbered block, and fail-safe either way).
   */
  private val MOTHER_DETAIL_PASSTHROUGH_QUESTION_CODES = listOf(
    MotherPrefillQuestionCodes.ADDRESS,
    MotherPrefillQuestionCodes.MOBILE_NUMBER,
    MotherPrefillQuestionCodes.PHONE_OWNER,
    MotherPrefillQuestionCodes.MOBILE_NETWORK_AVAILABILITY,
    MotherPrefillQuestionCodes.EDUCATION_LEVEL,
    MotherPrefillQuestionCodes.PARTNER_EDUCATION_LEVEL,
    MotherPrefillQuestionCodes.PARTNER_OCCUPATION,
    MotherPrefillQuestionCodes.YEARS_IN_VILLAGE,
    MotherPrefillQuestionCodes.MIGRATION_PATTERN,
    MotherPrefillQuestionCodes.MONTHLY_INCOME,
    MotherPrefillQuestionCodes.RELIGION,
    MotherPrefillQuestionCodes.SOCIAL_CATEGORY,
    MotherPrefillQuestionCodes.FAMILY_MEMBERS_COUNT,
    MotherPrefillQuestionCodes.CHILDREN_UNDER_5_COUNT,
  )

  /** Only ever copied when "yes" — same [org.armman.sakhi.data.motherlink.MotherPrefill
   * .INHERIT_CONSENT_FROM_MOTHER] policy and rationale: auto-answering "no" would trip the
   * consent-refused gate on the Sakhi's behalf, based on a record about a different beneficiary. */
  private const val CONSENT_YES = "yes"

  /**
   * Single-value (`text`/`number`/`date`/`radio`/`dropdown`) answers to seed.
   *
   * [motherAnswers] is the mother's own MOTHER_REGISTRATION [FormAnswers] (from
   * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource.answersFor] against
   * [DeliveryChildRegistrationViewModel.motherLocalBeneficiaryId]), null if for any reason it
   * can't be read — the rest of the prefill still applies, just without her geography. Unlike
   * [org.armman.sakhi.data.motherlink.MotherPrefill.apply] (the direct-registration path's
   * equivalent), no network call is involved: she was enrolled locally on this exact device, by
   * this exact Sakhi, before this delivery/child-registration flow ever started, so her answers are
   * already sitting in the local store.
   */
  fun singleValueAnswersFor(
    deliveryAnswers: FormAnswers,
    childIndex: Int,
    motherAnswers: FormAnswers? = null,
    motherGeography: List<FormGeographyUnit> = emptyList(),
    /** The child registration's own "today" — same role as [org.armman.sakhi.data.motherlink
     * .MotherPrefill.apply]'s own `registrationDate` param, needed to derive
     * [MotherPrefillQuestionCodes.MOTHER_AGE] from the mother's DOB. Defaulted to [LocalDate.now]
     * only so existing callers/tests that don't care about mother_age don't all need updating. */
    registrationDate: LocalDate = LocalDate.now(),
  ): Map<String, String> {
    val values = mutableMapOf<String, String>()

    // Definitionally true for every child registered through this flow — see this ViewModel's own
    // caller, DeliveryChildRegistrationViewModel, for why WHO_ARE_YOU_REGISTERING is hidden rather
    // than left for the Sakhi to (redundantly, and possibly incorrectly) re-answer.
    values[ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING] = ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER

    values[MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID] = DEAD_MOTHER_BENEFICIARY_ID_PLACEHOLDER

    if (motherAnswers != null) {
      values += motherGeographyAnswersFor(motherAnswers, motherGeography)
      values += motherDetailAnswersFor(motherAnswers, registrationDate)
    }

    deliveryAnswers.valueOf(DeliveryQuestionCodes.DATE_OF_DELIVERY)?.let {
      values[ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT] = it
    }
    // Verbatim — confirmed byte-identical value_code vocabulary on both schemas.
    deliveryAnswers.valueOf(DeliveryQuestionCodes.TERM_OF_DELIVERY)?.let {
      values[DeliveryQuestionCodes.TERM_OF_DELIVERY] = it
    }
    deliveryAnswers.valueOf(DeliveryQuestionCodes.TYPE_OF_DELIVERY)?.let {
      values[DeliveryQuestionCodes.TYPE_OF_DELIVERY] = it
    }
    // Mapped — these two do NOT share a value_code vocabulary between the forms.
    deliveryAnswers.valueOf(DeliveryQuestionCodes.PLACE_OF_DELIVERY)?.let {
      values[DeliveryQuestionCodes.PLACE_OF_DELIVERY] = DeliveryOptionCodeMapper.placeOfDeliveryForChildRegistration(it)
    }
    deliveryAnswers.valueOf(DeliveryQuestionCodes.WHO_CONDUCTED_THE_DELIVERY)?.let {
      values[DeliveryQuestionCodes.WHO_CONDUCTED_THE_DELIVERY] =
        DeliveryOptionCodeMapper.whoConductedTheDeliveryForChildRegistration(it)
    }
    deliveryAnswers.valueOf(DeliveryQuestionCodes.childSexOfBaby(childIndex))?.let { values[SEX_OF_CHILD] = it }
    deliveryAnswers.valueOf(DeliveryQuestionCodes.childBirthLengthCm(childIndex))?.let {
      values[CHILD_LENGTH_AT_BIRTH_CM] = it
    }
    deliveryAnswers.valueOf(DeliveryQuestionCodes.childBirthWeightKg(childIndex))?.let {
      values[CHILD_WEIGHT_AT_BIRTH_KG] = it
    }
    return values
  }

  /** `multiselect` answers to seed — currently just the one shared field. */
  fun multiValueAnswersFor(deliveryAnswers: FormAnswers, childIndex: Int): Map<String, List<String>> {
    val complications = deliveryAnswers.multiValueOf(DeliveryQuestionCodes.childRelatedComplications(childIndex))
    if (complications.isEmpty()) return emptyMap()
    return mapOf(
      DID_THE_BABY_HAVE_COMPLICATIONS to DeliveryOptionCodeMapper.birthComplicationsForChildRegistration(complications),
    )
  }

  /**
   * The mother's own state/district/block/village/pada/PHC/sub-centre, copied straight from her
   * MOTHER_REGISTRATION answers — CHILD_REGISTRATION shares the exact same `question_code`s for
   * these fields (see [GeographyQuestionCodes]'s own doc on why mother and child forms can share
   * codes but still ship separate `geography` arrays).
   *
   * Same "only write a shipped id" rule [org.armman.sakhi.data.motherlink.MotherPrefill.apply]
   * follows for the direct-registration path: a geography id CHILD_REGISTRATION's own active
   * version didn't ship at that level is skipped rather than written, to avoid reproducing the
   * `pii.phcId does not refer to a known geography unit` (HTTP 422) that rule exists to prevent.
   * In practice this should always be shipped — it's the same Sakhi's own assignment on both
   * forms — but the check costs nothing and the historical bug it prevents was expensive.
   */
  private fun motherGeographyAnswersFor(
    motherAnswers: FormAnswers,
    geography: List<FormGeographyUnit>,
  ): Map<String, String> {
    val values = mutableMapOf<String, String>()
    MOTHER_GEOGRAPHY_QUESTION_CODES.forEach { questionCode ->
      val unitId = motherAnswers.valueOf(questionCode)?.takeIf { it.isNotBlank() } ?: return@forEach
      val geoType = GeographyQuestionCodes.QUESTION_CODE_TO_GEO_TYPE[questionCode] ?: return@forEach
      if (geography.any { it.geographyUnitId == unitId && it.geoType == geoType }) {
        values[questionCode] = unitId
      }
    }
    return values
  }

  /**
   * The mother's own name, DOB/age, consent, and household socio-demographics, copied straight
   * from her MOTHER_REGISTRATION answers — see this object's class doc for why no network call or
   * label translation is needed here, unlike [org.armman.sakhi.data.motherlink.MotherPrefill.apply].
   */
  private fun motherDetailAnswersFor(motherAnswers: FormAnswers, registrationDate: LocalDate): Map<String, String> {
    val values = mutableMapOf<String, String>()

    val name = BeneficiaryNameQuestionCodes.combinedNameAnswer(motherAnswers) ?: listOfNotNull(
      motherAnswers.valueOf(FIRST_NAME)?.trim()?.takeIf { it.isNotBlank() },
      motherAnswers.valueOf(MIDDLE_NAME)?.trim()?.takeIf { it.isNotBlank() },
      motherAnswers.valueOf(LAST_NAME)?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(" ").takeIf { it.isNotBlank() }
    name?.let { values[MotherPrefillQuestionCodes.CAREGIVER_NAME] = it }

    val dob = motherAnswers.valueOf(DOB_QUESTION_CODE)?.takeIf { it.isNotBlank() }
    dob?.let { values[MotherPrefillQuestionCodes.MOTHER_DATE_OF_BIRTH] = it }
    // Derived, not copied verbatim — MOTHER_REGISTRATION and CHILD_REGISTRATION each compute the
    // mother's age from her own DOB independently rather than sharing one age answer, same
    // "derived, not typed" rule [org.armman.sakhi.data.motherlink.MotherPrefillQuestionCodes
    // .MOTHER_AGE]'s own doc describes for the registered-mother path.
    dob?.let { raw ->
      runCatching { LocalDate.parse(raw) }.getOrNull()?.let { parsedDob ->
        values[MotherPrefillQuestionCodes.MOTHER_AGE] = ChronoUnit.YEARS.between(parsedDob, registrationDate).toString()
      }
    }

    if (motherAnswers.valueOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT) == CONSENT_YES) {
      values[MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT] = CONSENT_YES
    }

    MOTHER_DETAIL_PASSTHROUGH_QUESTION_CODES.forEach { questionCode ->
      motherAnswers.valueOf(questionCode)?.trim()?.takeIf { it.isNotBlank() }?.let { values[questionCode] = it }
    }

    return values
  }
}
