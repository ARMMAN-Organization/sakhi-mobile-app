package org.armman.sakhi.data.childregistration

import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.enrollment.EnrollmentApi
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val FORM_CODE = "CHILD_REGISTRATION"

/** Everything that can stop [ChildRegistrationSubmissionCoordinator.submit] mid-flight — mirror of
 * `DynamicFormSubmissionException`, declared separately so the child flow stays standalone. */
sealed class ChildRegistrationSubmissionException(message: String) : Exception(message) {
  /**
   * The one sentence to show the Sakhi, or null to let the caller pick a fallback. Deliberately
   * separate from [Exception.message], which stays diagnostic (endpoint, HTTP code, verbatim body)
   * for logs and the draft's `lastErrorMessage` debug column.
   *
   * Its absence was a real bug: [ChildFormSyncExecutor] surfaced `Throwable.message` directly, so a
   * failed submit put the whole envelope on screen —
   * `POST /forms/CHILD_REGISTRATION/submissions failed: HTTP 422 — {"success":false,…,"traceId":…}`.
   * The mother flow already fixed this via `DynamicFormSubmissionException.userMessage`; the child
   * clone never picked it up. See [SubmitErrorCopy].
   */
  open val userMessage: String? get() = null

  data class MappingFailed(val mappingCause: Throwable) : ChildRegistrationSubmissionException(
    mappingCause.message ?: "Could not map the form's answers for submission",
  )

  /** [errorCode]/[fieldErrors]/[apiMessage] are parsed from [body] via [ApiErrorParser]; [body] is
   * retained verbatim for the draft's debug column. */
  data class BeneficiaryCreationFailed(
    val httpCode: Int,
    val body: String?,
    val errorCode: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val apiMessage: String? = null,
  ) : ChildRegistrationSubmissionException("POST /beneficiaries failed: HTTP $httpCode — $body") {
    override val userMessage: String get() = SubmitErrorCopy.forApiError(apiMessage, fieldErrors)
  }

  data object NoBeneficiaryIdReturned :
    ChildRegistrationSubmissionException("Beneficiary created but no id was returned in the response") {
    override val userMessage: String get() = SubmitErrorCopy.GENERIC
  }

  /** Mirrors [NoBeneficiaryIdReturned] for the submission call — see
   * [org.armman.sakhi.data.forms.DynamicFormSubmissionException.NoSubmissionIdReturned] for why
   * this is worth failing loudly on rather than silently carrying a blank id
   * (CR-Registration-Edit). */
  data object NoSubmissionIdReturned :
    ChildRegistrationSubmissionException("Form submitted but no submission id was returned in the response") {
    override val userMessage: String get() = SubmitErrorCopy.GENERIC
  }

  /**
   * [violations] carries the backend schema validator's messages (`form-validation.ts`), which a
   * `422` from this endpoint returns under `fieldErrors.violations` as an ARRAY — e.g.
   * `"Missing required field: mother_beneficiary_id"`. They name a `question_code`, not a DTO path,
   * so they aren't field-attributable and surface as a page-level banner.
   */
  data class FormSubmissionFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
    val violations: List<String> = emptyList(),
  ) : ChildRegistrationSubmissionException("POST /forms/$FORM_CODE/submissions failed: HTTP $httpCode — $body") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }
}

/**
 * Orchestrates CR-020's two-call submission: `POST /beneficiaries` first (creates the CHILD
 * beneficiary record), then `POST /forms/CHILD_REGISTRATION/submissions` using the *server-returned*
 * beneficiary id (not the app's local id). Standalone twin of
 * [org.armman.sakhi.data.forms.DynamicFormSubmissionCoordinator] — reuses the shared
 * [CreateSubmissionRequestDto]/[FormSubmissionApi]/[EnrollmentApi] contracts but its own mapper and
 * exception hierarchy.
 *
 * Called from [ChildFormSyncExecutor] under a network-constrained WorkManager job.
 *
 * Retry-safe end to end: `POST /beneficiaries` is idempotent on `localCaseUuid` (CR-017 — returns
 * the original case rather than duplicating) and `POST /forms/.../submissions` is idempotent on
 * `localSubmissionUuid`, so a retry that re-creates-then-resubmits is safe.
 *
 * [formVersionId] is the version the Sakhi answered against at save time — deliberately NOT
 * re-fetched, since a newer version may have been published between offline fill and sync.
 */
@Singleton
class ChildRegistrationSubmissionCoordinator @Inject constructor(
  private val enrollmentApi: EnrollmentApi,
  private val formSubmissionApi: FormSubmissionApi,
  private val mapper: ChildRegistrationSubmissionMapper,
) {

  suspend fun submit(
    formVersionId: String,
    localCaseUuid: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    fallbackRegistrationDate: LocalDate,
    // Returns the server-assigned beneficiary id AND submission id on success —
    // RoomChildFormDraftRepository's sync executor needs the beneficiary id to record
    // ChildFormDraftEntity.remoteBeneficiaryId (mirrors DynamicFormSubmissionCoordinator.submit's
    // identical contract for the mother flow); the submission id is what CR-Registration-Edit's
    // PATCH /form-submissions/:id/answers targets. Both used to be discarded entirely, which left
    // every child draft's remoteBeneficiaryId/remoteSubmissionId permanently null.
  ): Result<ChildRegistrationSubmissionOutcome> = runCatching {
    val beneficiaryRequest = mapper.toCreateBeneficiaryRequest(localCaseUuid, answers, fallbackRegistrationDate)
      .getOrElse { throw ChildRegistrationSubmissionException.MappingFailed(it) }

    val beneficiaryResponse = enrollmentApi.createBeneficiary(beneficiaryRequest)
    if (!beneficiaryResponse.isSuccessful) {
      val body = beneficiaryResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(body)
      throw ChildRegistrationSubmissionException.BeneficiaryCreationFailed(
        httpCode = beneficiaryResponse.code(),
        body = body,
        errorCode = apiError.errorCode,
        fieldErrors = apiError.fieldErrors,
        // ApiErrorParser echoes the whole body back as `message` when the response isn't the
        // expected envelope (plain-text 500s, HTML gateway pages). SubmitErrorCopy only detects
        // raw bodies starting with `{`/`[`/`<`, so a plain-text one would otherwise reach the
        // Sakhi verbatim. Mirrors DynamicFormSubmissionCoordinator's guard for the mother flow.
        apiMessage = apiError.message?.takeIf { it != body },
      )
    }
    val serverBeneficiaryId = beneficiaryResponse.body()?.data?.id
      ?: throw ChildRegistrationSubmissionException.NoBeneficiaryIdReturned

    val submissionRequest = CreateSubmissionRequestDto(
      formVersionId = formVersionId,
      beneficiaryId = serverBeneficiaryId,
      visitId = null,
      localSubmissionUuid = localSubmissionUuid,
      // `beneficiary_id` is a required schema field the Sakhi never answers (it doesn't exist until
      // `POST /beneficiaries` returns) — injected here from the server-assigned id.
      formData = mapper.toFormSubmissionData(answers) +
        (ChildNonRenderableQuestionCodes.BENEFICIARY_ID to serverBeneficiaryId),
    )
    val submissionResponse = formSubmissionApi.createSubmission(FORM_CODE, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val body = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(body)
      throw ChildRegistrationSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = body,
        // Same raw-body guard as the beneficiary-creation call above.
        apiMessage = apiError.message?.takeIf { it != body },
        violations = apiError.violations,
      )
    }

    val serverSubmissionId = submissionResponse.body()?.data?.id
      ?: throw ChildRegistrationSubmissionException.NoSubmissionIdReturned

    ChildRegistrationSubmissionOutcome(beneficiaryId = serverBeneficiaryId, submissionId = serverSubmissionId)
  }
}

/** [ChildRegistrationSubmissionCoordinator.submit]'s success payload — mirrors
 * [org.armman.sakhi.data.forms.DynamicFormSubmissionOutcome] for the mother flow
 * (CR-Registration-Edit). */
data class ChildRegistrationSubmissionOutcome(
  val beneficiaryId: String,
  val submissionId: String,
)
