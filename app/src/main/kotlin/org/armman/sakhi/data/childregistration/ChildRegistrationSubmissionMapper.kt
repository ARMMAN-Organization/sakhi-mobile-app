package org.armman.sakhi.data.childregistration

import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.BeneficiaryCaseDto
import org.armman.sakhi.data.enrollment.BeneficiaryPiiDto
import org.armman.sakhi.data.enrollment.ChildDetailsDto
import org.armman.sakhi.data.enrollment.ConsentDto
import org.armman.sakhi.data.enrollment.CreateBeneficiaryRequestDto
import org.armman.sakhi.data.enrollment.EnrollmentMappingException
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.registrationDateAnswer
import org.armman.sakhi.data.lookup.LookupRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val CATEGORY_CASE_TYPE = "CASE_TYPE"
private const val CATEGORY_BENEFICIARY_TYPE = "BENEFICIARY_TYPE"
private const val VALUE_CODE_CHILD = "CHILD"

/** `question_code`s the CHILD_REGISTRATION schema declares that [toCreateBeneficiaryRequest] reads
 * into the `/beneficiaries` DTO. These same answers are ALSO sent in the `formData` blob to
 * `POST /forms/CHILD_REGISTRATION/submissions` — the submissions endpoint validates the full schema
 * and rejects (HTTP 422) any required field that's absent, so `formData` carries every answer, not
 * just the ones without a beneficiary-DTO home. See [toFormSubmissionData]. */
private object QuestionCode {
  // The path radio and infant DOB are aliased from the shared declaration rather than re-typed:
  // FormDateRuleset and DynamicChildRegistrationViewModel key their eligibility rules off the same
  // strings, and a rename that reached only two of the three would fail at submit, not at build.
  const val WHO_ARE_YOU_REGISTERING = ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING
  const val PATH_REGISTERED_MOTHER = ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER
  const val DATE_OF_BIRTH_OF_INFANT = ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT
  const val MOTHER_BENEFICIARY_ID = "mother_beneficiary_id"
  const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"
  const val NAME_OF_THE_CHILD = "name_of_the_child"
  const val SEX_OF_CHILD = "sex_of_child"
  const val CHILD_LENGTH_CM = "child_length_at_birth_in_cm"
  const val CHILD_WEIGHT_KG = "child_weight_at_birth_in_kg"
  const val TERM_OF_DELIVERY = "term_of_delivery"
  const val MOBILE_NUMBER = "mobile_number"
  const val ADDRESS = "enter_the_beneficiary_address"
  // Registration date is NOT declared here: the published schemas use two different spellings, so it
  // is read via `answers.registrationDateAnswer()` / REGISTRATION_DATE_QUESTION_CODES instead of a
  // single literal.
}

/** `sex_of_child` value_codes → `pii.sex`/`childDetails.sex` enum. Assumption flagged: the backend
 * beneficiary enum has not been confirmed for CHILD; these are the natural mappings and must be
 * verified against the real `/beneficiaries` contract before go-live. */
private const val SEX_MALE = "male"
private const val SEX_FEMALE = "female"
private const val SEX_INTERSEX_OTHER = "intersex_other"

/** `term_of_delivery` value_codes → `childDetails.prematureFlag`. */
private const val TERM_PRE = "pre_term"
private const val TERM_POST = "post_term"
private const val TERM_FULL = "full_term"

/**
 * Stopgap value sent for `pii.rchNumber`. A child beneficiary has no RCH-card concept at all, yet
 * the `/beneficiaries` validation (proven against the mother flow) demands a non-empty
 * `pii.rchNumber` on every submission. Flagged, not silently papered over: this MUST be verified
 * against the real backend contract for CHILD cases — the backend may not require it for children,
 * or may want a distinct "not applicable" sentinel. "NA" is a placeholder, not a real RCH number.
 */
private const val RCH_NUMBER_NOT_APPLICABLE = "NA"

/**
 * Maps CR-020's Children Register [FormAnswers] to the two real backend calls: `POST /beneficiaries`
 * (the CHILD case + its infant details — same DTO family the mother/static flows use) and the
 * `formData` blob for `POST /forms/CHILD_REGISTRATION/submissions` (every answer, archived against
 * the beneficiary created by the first call). Standalone twin of
 * [org.armman.sakhi.data.forms.DynamicFormSubmissionMapper] — it does not touch that class.
 *
 * Lookup ids ([CATEGORY_CASE_TYPE]/[CATEGORY_BENEFICIARY_TYPE] → `CHILD`) are resolved via
 * [LookupRepository], never hardcoded — a missing seed fails fast with
 * [EnrollmentMappingException.LookupNotAvailable].
 */
@Singleton
class ChildRegistrationSubmissionMapper @Inject constructor(
  private val sessionStore: SessionStore,
  private val lookupRepository: LookupRepository,
) {

  suspend fun toCreateBeneficiaryRequest(
    localCaseUuid: String,
    answers: FormAnswers,
    fallbackRegistrationDate: LocalDate,
  ): Result<CreateBeneficiaryRequestDto> = runCatching {
    val session = sessionStore.readSession() ?: throw EnrollmentMappingException.NoActiveSession
    val projectId = session.projectId ?: throw EnrollmentMappingException.MissingProjectId

    val caseTypeLookupId = lookupRepository.findValue(CATEGORY_CASE_TYPE, VALUE_CODE_CHILD)?.id
      ?: throw EnrollmentMappingException.LookupNotAvailable(CATEGORY_CASE_TYPE, VALUE_CODE_CHILD)
    val beneficiaryTypeLookupId =
      lookupRepository.findValue(CATEGORY_BENEFICIARY_TYPE, VALUE_CODE_CHILD)?.id
        ?: throw EnrollmentMappingException.LookupNotAvailable(
          CATEGORY_BENEFICIARY_TYPE,
          VALUE_CODE_CHILD,
        )

    // "Did we receive consent?" is the schema-driven source of truth for consent. A "no" must never
    // reach submission — same principle as the mother flow, enforced here in the mapper.
    if (answers.valueOf(QuestionCode.DID_WE_RECEIVE_CONSENT) != "yes") {
      throw EnrollmentMappingException.CrossFieldValidation("Consent was not given")
    }

    // The infant's DOB is `required: true` in the schema and the ViewModel's isReadyToSubmit()
    // blocks Submit while it's blank/invalid — reaching here with a bad value means a bypass (e.g.
    // an old queued draft) or a gate gap. Fail here with a specific message rather than sending
    // whatever's there and letting the backend respond with an opaque "Invalid date". Mirrors the
    // mother mapper's DOB guard.
    val dateOfBirth = answers.valueOf(QuestionCode.DATE_OF_BIRTH_OF_INFANT)
    if (dateOfBirth == null || runCatching { LocalDate.parse(dateOfBirth) }.isFailure) {
      throw EnrollmentMappingException.CrossFieldValidation(
        "Date of birth is required and must be a valid date",
      )
    }

    val registrationDate =
      answers.registrationDateAnswer() ?: fallbackRegistrationDate.toString()
    val childName = parseChildName(answers.valueOf(QuestionCode.NAME_OF_THE_CHILD))
    val mappedSex = mapSex(answers.valueOf(QuestionCode.SEX_OF_CHILD))
    val motherBeneficiaryId =
      if (answers.valueOf(QuestionCode.WHO_ARE_YOU_REGISTERING) == QuestionCode.PATH_REGISTERED_MOTHER) {
        answers.valueOf(QuestionCode.MOTHER_BENEFICIARY_ID)
      } else {
        // Direct path: the mother is not registered in the program, so there is no id to link.
        null
      }

    CreateBeneficiaryRequestDto(
      // The beneficiary here IS the child.
      pii = BeneficiaryPiiDto(
        firstName = childName.firstName,
        middleName = childName.middleName,
        lastName = childName.lastName,
        phone = answers.valueOf(QuestionCode.MOBILE_NUMBER),
        alternatePhone = null,
        dateOfBirth = dateOfBirth,
        sex = mappedSex,
        addressLine = answers.valueOf(QuestionCode.ADDRESS),
        villageId = answers.valueOf(GeographyQuestionCodes.VILLAGE),
        padaId = answers.valueOf(GeographyQuestionCodes.PADA),
        healthSubCentreId = answers.valueOf(GeographyQuestionCodes.SUB_CENTRE),
        phcId = answers.valueOf(GeographyQuestionCodes.PHC),
        healthBlockId = null, // No distinct source for this in the current geography cascade.
        stateId = answers.valueOf(GeographyQuestionCodes.STATE),
        districtId = answers.valueOf(GeographyQuestionCodes.DISTRICT),
        talukaId = answers.valueOf(GeographyQuestionCodes.BLOCK_TALUKA),
        // See RCH_NUMBER_NOT_APPLICABLE: a child has no RCH number, but the /beneficiaries contract
        // (proven for the mother flow) requires pii.rchNumber non-empty. Verify for CHILD cases.
        rchNumber = RCH_NUMBER_NOT_APPLICABLE,
      ),
      case = BeneficiaryCaseDto(
        projectId = projectId,
        sakhiId = session.subjectId,
        caseType = "CHILD",
        registrationDate = registrationDate,
        previousBeneficiaryId = null,
        motherBeneficiaryId = motherBeneficiaryId,
        beneficiaryTypeLookupId = beneficiaryTypeLookupId,
        caseTypeLookupId = caseTypeLookupId,
        localCaseUuid = localCaseUuid,
      ),
      // Child-only: no obstetric/mother details for a CHILD case.
      motherDetails = null,
      childDetails = ChildDetailsDto(
        dateOfBirth = dateOfBirth,
        sex = mappedSex,
        birthWeightKg = answers.valueOf(QuestionCode.CHILD_WEIGHT_KG)?.toDoubleOrNull(),
        birthLengthCm = answers.valueOf(QuestionCode.CHILD_LENGTH_CM)?.toDoubleOrNull(),
        prematureFlag = when (answers.valueOf(QuestionCode.TERM_OF_DELIVERY)) {
          TERM_PRE -> true
          TERM_FULL, TERM_POST -> false
          else -> null
        },
      ),
      consent = ConsentDto(status = "GIVEN", date = registrationDate),
      acknowledgeDuplicate = null,
    )
  }

  /** The full `formData` blob for `POST /forms/CHILD_REGISTRATION/submissions`: EVERY answered
   * `question_code` (single + multi), including the ones also routed into the `/beneficiaries` DTO.
   * The submissions endpoint validates against the complete schema and 422s on any absent required
   * field, so nothing is filtered out here. `beneficiary_id` is NOT set here — it needs the
   * server-assigned id and is injected by [ChildRegistrationSubmissionCoordinator] after
   * `POST /beneficiaries` returns. */
  fun toFormSubmissionData(answers: FormAnswers): Map<String, Any?> =
    answers.singleValues + answers.multiValues

  /** Split parts of `name_of_the_child`. Heuristic to CONFIRM WITH ARMMAN: firstName = first token,
   * lastName = last token (or the first when there's only one), middleName = everything in between
   * (null if none). Whitespace-delimited, collapsing repeated spaces. */
  private fun parseChildName(rawName: String?): ChildName {
    val tokens = rawName.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
      tokens.isEmpty() -> ChildName(firstName = "", middleName = null, lastName = "")
      tokens.size == 1 -> ChildName(firstName = tokens.first(), middleName = null, lastName = tokens.first())
      tokens.size == 2 -> ChildName(firstName = tokens.first(), middleName = null, lastName = tokens.last())
      else -> ChildName(
        firstName = tokens.first(),
        middleName = tokens.subList(1, tokens.size - 1).joinToString(" "),
        lastName = tokens.last(),
      )
    }
  }

  /** `sex_of_child` value_code → backend enum. Assumption flagged (see SEX_* constants). */
  private fun mapSex(code: String?): String? = when (code) {
    SEX_MALE -> "MALE"
    SEX_FEMALE -> "FEMALE"
    SEX_INTERSEX_OTHER -> "OTHER"
    else -> null
  }

  private data class ChildName(val firstName: String, val middleName: String?, val lastName: String)
}
