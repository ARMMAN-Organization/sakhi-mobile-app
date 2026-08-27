package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that can stop [DeliveryChildRegistrationSubmissionCoordinator.submit] from
 * completing — mirrors [DeliveryFormSubmissionException]'s shape.
 */
sealed class DeliveryChildRegistrationSubmissionException(message: String) : Exception(message) {
  open val userMessage: String get() = SubmitErrorCopy.GENERIC

  data object NoActiveSession : DeliveryChildRegistrationSubmissionException("No signed-in Sakhi session")

  data object NoActiveDeliverySession : DeliveryChildRegistrationSubmissionException(
    "No active delivery session found for localSessionUuid",
  )

  data class FormSubmissionFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
    val violations: List<String> = emptyList(),
  ) : DeliveryChildRegistrationSubmissionException(
    "POST /forms/CHILD_REGISTRATION/submissions failed: HTTP $httpCode — $body",
  ) {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }
}

/**
 * Orchestrates CR-042's `CHILD_REGISTRATION` submission for a child auto-created by a
 * `DELIVERY_VISIT` submission. Deliberately NOT
 * [org.armman.sakhi.data.childregistration.ChildRegistrationSubmissionCoordinator]'s twin despite
 * the identical form code: that standalone coordinator's very first call is
 * `POST /beneficiaries`, which creates a brand-new beneficiary — exactly wrong here, since the
 * child beneficiary already exists (created by the backend as a side effect of the
 * `DELIVERY_VISIT` submission; see [DeliveryFormSubmissionCoordinator.advanceSession]'s
 * `childIds`, carried on [DeliverySessionEntity.child1BeneficiaryId] etc.). Reusing that
 * coordinator unmodified would silently create a SECOND, duplicate child beneficiary record on
 * every submission. This submits directly against the already-known [serverBeneficiaryId] and
 * skips beneficiary creation entirely — the same "inject the known server id, don't ask
 * `POST /beneficiaries` to hand us one" convention the standalone flow itself already uses for
 * `beneficiary_id` (see [org.armman.sakhi.data.childregistration.ChildNonRenderableQuestionCodes]),
 * just applied one step earlier since here the id is known BEFORE Submit rather than returned BY
 * it.
 *
 * On success, advances the session's [DeliverySessionEntity] via [advanceSessionAfterChildRegistered]:
 * increments [DeliverySessionEntity.nextChildIndexToRegister], or moves to
 * [DeliverySessionStep.PP1] once every listed child (child1/2/3BeneficiaryId) is registered.
 */
@Singleton
class DeliveryChildRegistrationSubmissionCoordinator @Inject constructor(
  private val formSubmissionApi: FormSubmissionApi,
  private val deliverySessionRepository: DeliverySessionRepository,
  private val sessionStore: SessionStore,
  private val formAuditRepository: FormAuditRepository,
) {

  /**
   * Submits `CHILD_REGISTRATION` for [serverBeneficiaryId] (the already-known auto-created child),
   * then — only on success — advances [localSessionUuid]'s [DeliverySessionEntity]. [answers]
   * should already include [org.armman.sakhi.data.childregistration.ChildNonRenderableQuestionCodes
   * .BENEFICIARY_ID] mapped to [serverBeneficiaryId], the same convention the standalone
   * CHILD_REGISTRATION flow uses — this coordinator does not inject it itself, so a caller that
   * forgets it gets a real backend validation error rather than a silently-different payload than
   * what the Sakhi reviewed on the Summary tab (if this flow ever grows one).
   */
  suspend fun submit(
    localSessionUuid: String,
    serverBeneficiaryId: String,
    localSubmissionUuid: String,
    formVersionId: String,
    answers: FormAnswers,
  ): Result<Unit> = runCatching {
    sessionStore.readSession() ?: throw DeliveryChildRegistrationSubmissionException.NoActiveSession

    val submissionRequest = CreateSubmissionRequestDto(
      formVersionId = formVersionId,
      beneficiaryId = serverBeneficiaryId,
      visitId = null,
      localSubmissionUuid = localSubmissionUuid,
      formData = answers.singleValues + answers.multiValues,
    )
    val submissionResponse = formSubmissionApi.createSubmission(FORM_CODE_CHILD_REGISTRATION, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val rawBody = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw DeliveryChildRegistrationSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = rawBody,
        apiMessage = apiError.message?.takeIf { it != rawBody },
        violations = apiError.violations,
      )
    }

    formAuditRepository.recordSubmitted(localSubmissionUuid, FORM_CODE_CHILD_REGISTRATION)

    advanceSessionAfterChildRegistered(localSessionUuid)
  }

  /** No-op (not an error) if the session row is somehow already gone — defensive only, mirrors
   * every other best-effort session read in this package. */
  private suspend fun advanceSessionAfterChildRegistered(localSessionUuid: String) {
    val session = deliverySessionRepository.getBySessionUuid(localSessionUuid) ?: return
    val totalChildren = listOfNotNull(
      session.child1BeneficiaryId,
      session.child2BeneficiaryId,
      session.child3BeneficiaryId,
    ).size
    val nextIndex = session.nextChildIndexToRegister + 1
    val allChildrenRegistered = nextIndex >= totalChildren
    deliverySessionRepository.save(
      session.copy(
        step = if (allChildrenRegistered) DeliverySessionStep.PP1 else DeliverySessionStep.CHILD_REGISTRATION,
        nextChildIndexToRegister = nextIndex,
        updatedAtEpochMillis = Instant.now().toEpochMilli(),
      ),
    )
  }

  private companion object {
    const val FORM_CODE_CHILD_REGISTRATION = "CHILD_REGISTRATION"
  }
}
