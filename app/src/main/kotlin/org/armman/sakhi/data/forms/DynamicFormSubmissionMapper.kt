package org.armman.sakhi.data.forms

import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.BeneficiaryCaseDto
import org.armman.sakhi.data.enrollment.BeneficiaryPiiDto
import org.armman.sakhi.data.enrollment.ConsentDto
import org.armman.sakhi.data.enrollment.CreateBeneficiaryRequestDto
import org.armman.sakhi.data.enrollment.EnrollmentMappingException
import org.armman.sakhi.data.enrollment.MotherDetailsDto
import org.armman.sakhi.data.lookup.LookupRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val CATEGORY_CASE_TYPE = "CASE_TYPE"
private const val CATEGORY_BENEFICIARY_TYPE = "BENEFICIARY_TYPE"
private const val VALUE_CODE_MOTHER = "MOTHER"
private const val VALUE_CODE_PREGNANT_WOMAN = "PREGNANT_WOMAN"

/** `question_code`s the MOTHER_REGISTRATION schema declares that [toCreateBeneficiaryRequest]
 * reads into the `/beneficiaries` DTO. These same answers are ALSO sent in the `formData` blob to
 * `POST /forms/MOTHER_REGISTRATION/submissions` — the submissions endpoint validates the full
 * schema and rejects (HTTP 422) any required field that's absent, so `formData` carries every
 * answer, not just the ones without a beneficiary-DTO home. See [toFormSubmissionData]. */
private object QuestionCode {
  const val LMP_DATE = "lmp_date"
  const val GRAVIDA = "gravida_total_number_of_pregnancies"
  const val PARA = "para_number_of_births_after_24_weeks"
  const val LIVING_CHILDREN = "living_children"
  const val ABORTIONS = "abortions_pregnancy_losses_before_24_weeks"
  const val STILL_BIRTHS = "still_births"
  const val DEAD_CHILDREN = "dead_children"

  /**
   * 2026-07-22: the v5 schema's single combined name question
   * (`beneficary_name_first_name_middle_name_last_name`) was replaced with three separate
   * questions — confirmed against a real `active-version` response (`api-calls.jsonl`). The
   * backend's `/beneficiaries` PII contract changed the same day, twice, both confirmed against
   * real 400 bodies: it briefly wanted a single joined `fullName`, then switched to requiring
   * `firstName`/`lastName` as discrete fields and rejecting `fullName` outright
   * (`pii: Unrecognized key(s) in object: 'fullName'`). These three answers are now sent straight
   * through to `BeneficiaryPiiDto.firstName`/`middleName`/`lastName` — see that DTO's doc before
   * changing this again, the contract has been unstable.
   */
  const val FIRST_NAME = "first_name"
  const val MIDDLE_NAME = "middle_name"
  const val LAST_NAME = "last_name"
  const val MOBILE_NUMBER = "mobile_number"
  // Live schema v9 (2026-07-22, confirmed against a real `active-version` response) renamed this
  // from the old `age_of_the_beneficiary` to `date_of_birth` and split the derived age into its
  // own `age_years` number question. The old code silently read null here, so DOB never mapped and
  // this mapper's own DOB guard rejected every submission. Keep this in sync with
  // FormComputedFieldEvaluator.DOB_QUESTION_CODE.
  const val DATE_OF_BIRTH = "date_of_birth"
  const val ADDRESS = "enter_the_beneficiary_address"
  const val RCH_NUMBER = "input_rch_number"
  const val REGISTRATION_DATE = "registrtion_date"
  const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"
}

/**
 * Stopgap value sent for `pii.rchNumber` when `input_rch_number` is legitimately hidden by its own
 * `visibleWhen` rule (RCH status ≠ "card available") — a real schema/API mismatch, not a client
 * bug: the form correctly doesn't ask for an RCH number in that case, but the backend's
 * `/beneficiaries` validation demands a non-empty `pii.rchNumber` on every submission regardless
 * (`pii.rchNumber: Required`, confirmed in `api-calls.jsonl`). Flagged rather than silently
 * papered over: this should be revisited once the backend either makes the field conditionally
 * required (matching the form's own visibility rule) or defines what value it actually wants for
 * "no RCH card" — "NA" is a placeholder, not a real RCH number, and downstream reports/exports
 * reading this field need to treat it as "not applicable", not a genuine missing-data gap.
 */
private const val RCH_NUMBER_NOT_APPLICABLE = "NA"

/**
 * Maps CR-018's dynamic-form [FormAnswers] to the two real backend calls: `POST /beneficiaries`
 * (structured fields that drive actual care workflows — same DTO the static enrollment flow
 * already submits to, CR-011/013) and the `formData` blob for
 * `POST /forms/MOTHER_REGISTRATION/submissions` (everything else, archived against the
 * beneficiary created by the first call).
 *
 * Known gap, flagged rather than silently built: this mapper is *not* wired into the existing
 * offline Room/WorkManager sync pipeline ([org.armman.sakhi.data.enrollment.RoomEnrollmentRepository]/
 * [org.armman.sakhi.data.enrollment.EnrollmentSyncExecutor]) — those are keyed to the static
 * [org.armman.sakhi.data.enrollment.EnrollmentRecord] model, not [FormAnswers]. Submitting through
 * this mapper today requires connectivity; whether dynamic-form drafts get their own offline queue
 * or reuse the existing one needs its own design decision before that gap closes.
 */
@Singleton
class DynamicFormSubmissionMapper @Inject constructor(
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

    val caseTypeLookupId = lookupRepository.findValue(CATEGORY_CASE_TYPE, VALUE_CODE_MOTHER)?.id
      ?: throw EnrollmentMappingException.LookupNotAvailable(CATEGORY_CASE_TYPE, VALUE_CODE_MOTHER)
    val beneficiaryTypeLookupId =
      lookupRepository.findValue(CATEGORY_BENEFICIARY_TYPE, VALUE_CODE_PREGNANT_WOMAN)?.id
        ?: throw EnrollmentMappingException.LookupNotAvailable(
          CATEGORY_BENEFICIARY_TYPE,
          VALUE_CODE_PREGNANT_WOMAN,
        )

    // "Did we receive consent?" is now the schema-driven source of truth for consent, replacing
    // the static flow's own Consent-step gate for this dynamic form. A "no" must never reach
    // submission — same principle as the static flow, enforced here instead of in a UI gate.
    if (answers.valueOf(QuestionCode.DID_WE_RECEIVE_CONSENT) != "yes") {
      throw EnrollmentMappingException.CrossFieldValidation("Consent was not given")
    }

    validateMotherCrossFieldRules(answers)

    // `date_of_birth` (DOB) is `required: true` in the schema, and
    // DynamicMotherRegistrationViewModel.isReadyToSubmit() should already block Submit while it's
    // unanswered — so reaching here with a blank/unparseable value would mean either a stale build
    // predating that check, a bypass of it (e.g. the offline sync path retrying an old queued
    // draft saved before this validation existed), or a real gap in that gate. Rather than sending
    // whatever's there and letting the backend's `pii.dateOfBirth: Invalid date` respond with no
    // indication of which field or why, fail here with a specific, actionable message.
    val dateOfBirth = answers.valueOf(QuestionCode.DATE_OF_BIRTH)
    if (dateOfBirth == null || runCatching { LocalDate.parse(dateOfBirth) }.isFailure) {
      throw EnrollmentMappingException.CrossFieldValidation(
        "Date of birth is required and must be a valid date",
      )
    }

    val registrationDate = answers.valueOf(QuestionCode.REGISTRATION_DATE) ?: fallbackRegistrationDate.toString()

    CreateBeneficiaryRequestDto(
      pii = BeneficiaryPiiDto(
        firstName = answers.valueOf(QuestionCode.FIRST_NAME).orEmpty(),
        middleName = answers.valueOf(QuestionCode.MIDDLE_NAME)?.takeIf { it.isNotBlank() },
        lastName = answers.valueOf(QuestionCode.LAST_NAME).orEmpty(),
        phone = answers.valueOf(QuestionCode.MOBILE_NUMBER),
        alternatePhone = null,
        dateOfBirth = dateOfBirth,
        sex = "FEMALE", // This form only registers Pregnant Woman beneficiaries.
        addressLine = answers.valueOf(QuestionCode.ADDRESS),
        villageId = answers.valueOf(GeographyQuestionCodes.VILLAGE),
        padaId = answers.valueOf(GeographyQuestionCodes.PADA),
        healthSubCentreId = answers.valueOf(GeographyQuestionCodes.SUB_CENTRE),
        phcId = answers.valueOf(GeographyQuestionCodes.PHC),
        healthBlockId = null, // No distinct source for this in the current geography cascade.
        stateId = answers.valueOf(GeographyQuestionCodes.STATE),
        districtId = answers.valueOf(GeographyQuestionCodes.DISTRICT),
        talukaId = answers.valueOf(GeographyQuestionCodes.BLOCK_TALUKA),
        // See RCH_NUMBER_NOT_APPLICABLE's doc: the form hides this question when RCH status isn't
        // "card available", but the backend requires pii.rchNumber non-empty regardless.
        rchNumber = answers.valueOf(QuestionCode.RCH_NUMBER)?.takeIf { it.isNotBlank() }
          ?: RCH_NUMBER_NOT_APPLICABLE,
      ),
      case = BeneficiaryCaseDto(
        projectId = projectId,
        sakhiId = session.subjectId,
        caseType = "MOTHER",
        registrationDate = registrationDate,
        previousBeneficiaryId = null,
        motherBeneficiaryId = null,
        beneficiaryTypeLookupId = beneficiaryTypeLookupId,
        caseTypeLookupId = caseTypeLookupId,
        localCaseUuid = localCaseUuid,
      ),
      motherDetails = MotherDetailsDto(
        lmpDate = answers.valueOf(QuestionCode.LMP_DATE).orEmpty(),
        gravida = answers.valueOf(QuestionCode.GRAVIDA)?.toIntOrNull() ?: 0,
        parity = answers.valueOf(QuestionCode.PARA)?.toIntOrNull() ?: 0,
        liveBirths = answers.valueOf(QuestionCode.LIVING_CHILDREN)?.toIntOrNull() ?: 0,
        stillbirths = answers.valueOf(QuestionCode.STILL_BIRTHS)?.toIntOrNull() ?: 0,
        abortions = answers.valueOf(QuestionCode.ABORTIONS)?.toIntOrNull() ?: 0,
        deadChildren = answers.valueOf(QuestionCode.DEAD_CHILDREN)?.toIntOrNull(),
        heightCm = null, // Not present as a distinct field in the v5 dynamic schema.
        weightKg = null,
      ),
      // Mother-only (CR-018 covers this form; child enrollment is a separate, not-yet-built phase).
      childDetails = null,
      consent = ConsentDto(status = "GIVEN", date = registrationDate),
      acknowledgeDuplicate = null,
    )
  }

  /**
   * The live CR-018 schema's `validationJson` (see `api-calls.jsonl`) declares `para <= gravida`,
   * `abortions <= gravida`, and `deadChildren <= livingChildren` as cross-field rules, but does
   * NOT declare the `/beneficiaries` API's own `liveBirths + stillbirths + abortions == gravida`
   * check — [FormCrossFieldValidator] fully supports a `SUM_EQUALS` rule, it's just never sent by
   * the backend for this form version. Without this, a Sakhi can freely submit numbers that don't
   * add up and only find out at the very end via a raw backend 400
   * (`motherDetails.gravida: liveBirths + stillbirths + abortions must equal gravida`). Mirrors
   * [org.armman.sakhi.ui.enrollment.EnrollmentViewModel]'s equivalent static-flow check
   * ([org.armman.sakhi.data.enrollment.EnrollmentApiMapper.validateMotherCrossFieldRules]) so both
   * submission paths fail the same way, this early rather than round-tripping to the server first.
   */
  private fun validateMotherCrossFieldRules(answers: FormAnswers) {
    val gravida = answers.valueOf(QuestionCode.GRAVIDA)?.toIntOrNull() ?: return
    val living = answers.valueOf(QuestionCode.LIVING_CHILDREN)?.toIntOrNull() ?: return
    val stillbirths = answers.valueOf(QuestionCode.STILL_BIRTHS)?.toIntOrNull() ?: return
    val abortions = answers.valueOf(QuestionCode.ABORTIONS)?.toIntOrNull() ?: return
    if (living + stillbirths + abortions != gravida) {
      throw EnrollmentMappingException.CrossFieldValidation(
        "liveBirths + stillbirths + abortions must equal gravida",
      )
    }
  }

  /** The full `formData` blob for `POST /forms/.../submissions`: EVERY answered `question_code`,
   * including the ones also routed into [toCreateBeneficiaryRequest]'s DTO. The submissions
   * endpoint validates against the complete schema and returns HTTP 422 listing any required field
   * that's missing, so nothing is filtered out here (an earlier version excluded the
   * beneficiary-DTO fields and every submission failed validation). Multi-value answers are sent as
   * a JSON array (matching the sample payload's `["no_complications"]`-style fields); single values
   * as plain strings. `beneficiary_id` is not set here — it needs the server-assigned id and is
   * injected by [DynamicFormSubmissionCoordinator] after `POST /beneficiaries` returns. */
  fun toFormSubmissionData(answers: FormAnswers): Map<String, Any?> =
    answers.singleValues + answers.multiValues
}
