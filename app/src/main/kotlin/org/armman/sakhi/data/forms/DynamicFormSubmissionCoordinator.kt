package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.enrollment.DuplicateAcknowledgement
import org.armman.sakhi.data.enrollment.DuplicateOutcome
import org.armman.sakhi.data.enrollment.DuplicateOutcomeParser
import org.armman.sakhi.data.enrollment.EnrollmentApi
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val FORM_CODE = "MOTHER_REGISTRATION"

/** `POST /beneficiaries` duplicate-detection rejection (SRS FR-S-2.4/2.5). */
private const val HTTP_CONFLICT = 409

sealed class DynamicFormSubmissionException(message: String) : Exception(message) {
  /**
   * The one sentence to show the Sakhi, or null to let the caller pick a fallback. Deliberately
   * separate from [Exception.message], which stays diagnostic (endpoint, HTTP code, raw body) for
   * logs and the draft's `lastErrorMessage` debug column — rendering that in the UI is exactly the
   * bug this exists to fix. See [SubmitErrorCopy].
   */
  open val userMessage: String? get() = null

  data class MappingFailed(val mappingCause: Throwable) : DynamicFormSubmissionException(
    mappingCause.message ?: "Could not map the form's answers for submission",
  )

  /**
   * [errorCode] and [fieldErrors] are parsed from the [body] via [ApiErrorParser]: for a
   * `400 VALIDATION_ERROR` [fieldErrors] carries the backend's per-field messages keyed by dotted
   * DTO path (`pii.firstName`, `motherDetails.stillbirths`, …); for a `422 UNPROCESSABLE` or any
   * other shape it's empty (those aren't field-attributable and surface as a page-level banner).
   * [body] is retained verbatim for the draft's debug log — see [DynamicFormSyncExecutor.markFailed].
   */
  data class BeneficiaryCreationFailed(
    val httpCode: Int,
    val body: String?,
    val errorCode: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val apiMessage: String? = null,
    /**
     * Non-null only for a `409`: which of the two duplicate situations this is (SRS FR-S-2.4/2.5).
     * Callers branch on it instead of re-parsing the body, and never render [userMessage] for a
     * `409` — that copy comes from string resources so it exists in Marathi too.
     */
    val duplicateOutcome: DuplicateOutcome? = null,
  ) : DynamicFormSubmissionException("POST /beneficiaries failed: HTTP $httpCode — $body") {
    override val userMessage: String get() = SubmitErrorCopy.forApiError(apiMessage, fieldErrors)
  }

  data object NoBeneficiaryIdReturned :
    DynamicFormSubmissionException("Beneficiary created but no id was returned in the response") {
    override val userMessage: String get() = SubmitErrorCopy.GENERIC
  }

  /**
   * [violations] carries the backend schema validator's messages (`form-validation.ts`), which a
   * `422` from this endpoint returns under `fieldErrors.violations` as an ARRAY — e.g.
   * `"Missing required field: <question_code>"`. They name a `question_code` rather than a DTO path,
   * so they aren't field-attributable and surface as a page-level banner. See [ApiError.violations].
   */
  data class FormSubmissionFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
    val violations: List<String> = emptyList(),
  ) : DynamicFormSubmissionException("POST /forms/$FORM_CODE/submissions failed: HTTP $httpCode — $body") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }
}

/**
 * Orchestrates CR-018's two-call submission: `POST /beneficiaries` first (creates the real
 * beneficiary record — same endpoint the static enrollment flow already uses), then
 * `POST /forms/MOTHER_REGISTRATION/submissions` using the *server-returned* beneficiary id (not
 * the app's local [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.beneficiaryId]) —
 * confirmed against the backend's `FormSubmission.beneficiaryId` column, which references
 * `beneficiary_cases.beneficiary_id`, the server-assigned id, not any client-generated one.
 *
 * Called from [DynamicFormSyncExecutor] under a network-constrained WorkManager job — never
 * called directly from the UI layer (see that class for why: this makes submission genuinely
 * offline-first, matching the static enrollment flow's guarantee).
 *
 * Retry-safe end to end: a dropped connection between the two calls (or a retry of the whole
 * queue item) is safe to just redo entirely — `POST /beneficiaries` is idempotent on
 * `localCaseUuid` (CR-017: the backend returns the *original* case instead of creating a
 * duplicate), and `POST /forms/.../submissions` is idempotent on `localSubmissionUuid` the same
 * way. So a retry that re-creates-then-resubmits is safe, not just "probably fine."
 *
 * [formVersionId] is the version the Sakhi actually answered against at save time — deliberately
 * *not* re-fetched from [FormsRepository]'s current active version, since that may have changed
 * (a newer version published) between when she filled the form offline and when this sync runs.
 */
@Singleton
class DynamicFormSubmissionCoordinator @Inject constructor(
  private val enrollmentApi: EnrollmentApi,
  private val formSubmissionApi: FormSubmissionApi,
  private val mapper: DynamicFormSubmissionMapper,
) {

  /**
   * [duplicateAcknowledgement] is forwarded straight to the mapper: non-null only when the Sakhi has
   * confirmed an FR-S-2.5 new-pregnancy prompt for this draft. See
   * [org.armman.sakhi.data.forms.DynamicFormDraftPayload.duplicateAcknowledgement].
   */
  suspend fun submit(
    formVersionId: String,
    localCaseUuid: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    fallbackRegistrationDate: LocalDate,
    duplicateAcknowledgement: DuplicateAcknowledgement? = null,
    // Returns the server-assigned beneficiary id on success. CR-022 needs it: a locally generated
    // visit schedule cannot be uploaded until its beneficiary exists server-side, and this is the
    // only place that id is ever known. It used to be discarded, which left every schedule
    // permanently ineligible for upload.
  ): Result<String> = runCatching {
    val beneficiaryRequest = mapper.toCreateBeneficiaryRequest(
      localCaseUuid = localCaseUuid,
      answers = answers,
      fallbackRegistrationDate = fallbackRegistrationDate,
      duplicateAcknowledgement = duplicateAcknowledgement,
    ).getOrElse { throw DynamicFormSubmissionException.MappingFailed(it) }

    val beneficiaryResponse = enrollmentApi.createBeneficiary(beneficiaryRequest)
    if (!beneficiaryResponse.isSuccessful) {
      val rawBody = beneficiaryResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw DynamicFormSubmissionException.BeneficiaryCreationFailed(
        httpCode = beneficiaryResponse.code(),
        body = rawBody,
        errorCode = apiError.errorCode,
        fieldErrors = apiError.fieldErrors,
        // ApiErrorParser echoes the whole body back as `message` when the response isn't the
        // expected envelope (plain-text 500s, HTML gateway pages). That's not a sentence worth
        // showing, so treat "message == body" as "no usable message" and let the UI fall back.
        apiMessage = apiError.message?.takeIf { it != rawBody },
        duplicateOutcome = if (beneficiaryResponse.code() == HTTP_CONFLICT) {
          DuplicateOutcomeParser.parse(apiError)
        } else {
          null
        },
      )
    }
    val serverBeneficiaryId = beneficiaryResponse.body()?.data?.id
      ?: throw DynamicFormSubmissionException.NoBeneficiaryIdReturned

    val submissionRequest = CreateSubmissionRequestDto(
      formVersionId = formVersionId,
      beneficiaryId = serverBeneficiaryId,
      visitId = null,
      localSubmissionUuid = localSubmissionUuid,
      // `beneficiary_id` is a required schema field the Sakhi never answers (it doesn't exist until
      // `POST /beneficiaries` returns) — so it's injected here from the server-assigned id rather
      // than collected on the form. The schema types it as a number; we send the server id as-is
      // per product decision, same value as the top-level `beneficiaryId` link above.
      formData = mapper.toFormSubmissionData(answers) +
        (NonRenderableQuestionCodes.BENEFICIARY_ID to serverBeneficiaryId),
    )
    val submissionResponse = formSubmissionApi.createSubmission(FORM_CODE, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val rawSubmissionBody = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawSubmissionBody)
      throw DynamicFormSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = rawSubmissionBody,
        apiMessage = apiError.message?.takeIf { it != rawSubmissionBody },
        violations = apiError.violations,
      )
    }
    serverBeneficiaryId
  }
}

/** Generates the once-per-draft, stable-across-retries id [DynamicFormSubmissionCoordinator.submit]
 * needs for `localSubmissionUuid` — same pattern as `EnrollmentRecord.beneficiaryId`/`localCaseUuid`
 * (CR-017): mint once, hold for the draft's lifetime, never regenerate on retry. */
fun newLocalSubmissionUuid(): String = UUID.randomUUID().toString()
