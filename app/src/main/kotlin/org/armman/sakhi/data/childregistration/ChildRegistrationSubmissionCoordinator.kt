package org.armman.sakhi.data.childregistration

import org.armman.sakhi.data.enrollment.EnrollmentApi
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val FORM_CODE = "CHILD_REGISTRATION"

/** Everything that can stop [ChildRegistrationSubmissionCoordinator.submit] mid-flight — mirror of
 * `DynamicFormSubmissionException`, declared separately so the child flow stays standalone. */
sealed class ChildRegistrationSubmissionException(message: String) : Exception(message) {
  data class MappingFailed(val mappingCause: Throwable) : ChildRegistrationSubmissionException(
    mappingCause.message ?: "Could not map the form's answers for submission",
  )

  data class BeneficiaryCreationFailed(val httpCode: Int, val body: String?) :
    ChildRegistrationSubmissionException("POST /beneficiaries failed: HTTP $httpCode — $body")

  data object NoBeneficiaryIdReturned :
    ChildRegistrationSubmissionException("Beneficiary created but no id was returned in the response")

  data class FormSubmissionFailed(val httpCode: Int, val body: String?) :
    ChildRegistrationSubmissionException("POST /forms/$FORM_CODE/submissions failed: HTTP $httpCode — $body")
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
  ): Result<Unit> = runCatching {
    val beneficiaryRequest = mapper.toCreateBeneficiaryRequest(localCaseUuid, answers, fallbackRegistrationDate)
      .getOrElse { throw ChildRegistrationSubmissionException.MappingFailed(it) }

    val beneficiaryResponse = enrollmentApi.createBeneficiary(beneficiaryRequest)
    if (!beneficiaryResponse.isSuccessful) {
      throw ChildRegistrationSubmissionException.BeneficiaryCreationFailed(
        beneficiaryResponse.code(),
        beneficiaryResponse.errorBody()?.string(),
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
      throw ChildRegistrationSubmissionException.FormSubmissionFailed(
        submissionResponse.code(),
        submissionResponse.errorBody()?.string(),
      )
    }
  }
}
