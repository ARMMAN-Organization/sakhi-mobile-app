package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.schedule.ScheduleContext
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that can stop [DeliveryFormSubmissionCoordinator.submit] from completing — mirrors
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionException]'s shape.
 */
sealed class DeliveryFormSubmissionException(message: String) : Exception(message) {
  open val userMessage: String get() = SubmitErrorCopy.GENERIC

  data object NoActiveSession : DeliveryFormSubmissionException("No signed-in Sakhi session")

  /** Same rationale as [org.armman.sakhi.data.adhocform.AdHocFormSubmissionException.NotYetSynced]
   * — the mother's own server beneficiary id, resolved from her existing schedule rows, isn't
   * known yet because she herself hasn't finished syncing. Retryable, not fatal. */
  data object NotYetSynced : DeliveryFormSubmissionException(
    "Cannot submit: no server beneficiary id known yet for this mother",
  ) {
    override val userMessage: String
      get() = "This beneficiary's data hasn't finished syncing yet. Connect to the internet, use Data Upload, then try again."
  }

  data class FormSubmissionFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
    val violations: List<String> = emptyList(),
  ) : DeliveryFormSubmissionException("POST /forms/DELIVERY_VISIT/submissions failed: HTTP $httpCode — $body") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }

  data object NoSubmissionIdReturned :
    DeliveryFormSubmissionException("Delivery form submitted but no id was returned in the response")
}

/**
 * Orchestrates the single-call `DELIVERY_VISIT` submit — the CR-042 twin of
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator], with one addition that
 * warrants its own coordinator rather than reuse: on a successful submission this also (a) writes
 * the [DeliverySessionEntity] row that anchors the rest of the delivery session and (b) calls
 * [VisitScheduleCoordinator.onDeliveryRecorded] to generate the PP/NN schedules. Both of those run
 * exactly once, on whichever attempt actually succeeds — the immediate online attempt right after
 * Submit, or a later background retry — because both paths call this same [submit] method (see
 * [DeliveryFormSyncExecutor]). This mirrors the precedent already in this codebase for
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator.submitClosure]: a post-submission
 * side effect that only fires after a real success, regardless of which caller triggered it.
 *
 * Deliberately does NOT handle child registration, PP1, or NN itself — those are separate form
 * submissions the Sakhi fills next, each through their own existing/future coordinators. This
 * class's job ends at: submit the delivery form, record which children (if any) now need
 * registering, and kick off the schedule.
 */
@Singleton
class DeliveryFormSubmissionCoordinator @Inject constructor(
  private val formSubmissionApi: FormSubmissionApi,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val visitScheduleCoordinator: VisitScheduleCoordinator,
  private val deliverySessionRepository: DeliverySessionRepository,
  private val sessionStore: SessionStore,
  private val formAuditRepository: FormAuditRepository,
) {

  /**
   * Submits the `DELIVERY_VISIT` form for [localSessionUuid]'s mother, then — only on success —
   * advances the [DeliverySessionEntity] row and the visit schedule. Returns the childBeneficiaryIds
   * the backend reported (see [org.armman.sakhi.data.forms.SubmissionResponseData.childBeneficiaryIds]'s
   * doc for the null-vs-empty contract this preserves as-is).
   *
   * [deliveryDate] and [deliveryFormFilledOn] come from the form's own answers (resolved by the
   * caller — this coordinator does not parse [FormAnswers] itself, same division of labor as
   * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]) and are passed straight
   * through to [ScheduleContext]. [deliveryFormFilledOn] is also persisted onto the
   * [DeliverySessionEntity] row itself (see [advanceSession]) so that
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator] can later ask
   * [org.armman.sakhi.data.schedule.sameSessionNnVisit] whether a same-session NN visit exists,
   * once this session reaches [DeliverySessionStep.PP1], without re-deriving it from anywhere else.
   */
  suspend fun submit(
    localSubmissionUuid: String,
    localSessionUuid: String,
    localBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
    deliveryDate: LocalDate,
    deliveryFormFilledOn: LocalDate,
  ): Result<List<String>?> = runCatching {
    sessionStore.readSession() ?: throw DeliveryFormSubmissionException.NoActiveSession

    // Same resolution path as AdHocFormSubmissionCoordinator: no schedule of our own yet to pull a
    // server beneficiary id from (that's exactly what this submission is about to create), so we
    // reuse whatever server beneficiary id the mother's EXISTING (ANC-era) schedule rows already
    // carry.
    val serverBeneficiaryId = visitScheduleRepository.getForBeneficiary(localBeneficiaryId)
      .firstNotNullOfOrNull { it.serverBeneficiaryId }
      ?: throw DeliveryFormSubmissionException.NotYetSynced

    val submissionRequest = CreateSubmissionRequestDto(
      formVersionId = formVersionId,
      beneficiaryId = serverBeneficiaryId,
      visitId = null,
      localSubmissionUuid = localSubmissionUuid,
      formData = answers.singleValues + answers.multiValues,
    )
    val submissionResponse = formSubmissionApi.createSubmission(FORM_CODE_DELIVERY_VISIT, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val rawBody = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw DeliveryFormSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = rawBody,
        apiMessage = apiError.message?.takeIf { it != rawBody },
        violations = apiError.violations,
      )
    }
    val submissionData = submissionResponse.body()?.data
      ?: throw DeliveryFormSubmissionException.NoSubmissionIdReturned

    formAuditRepository.recordSubmitted(localSubmissionUuid, FORM_CODE_DELIVERY_VISIT)

    val childIds = submissionData.childBeneficiaryIds
    advanceSession(
      localSessionUuid = localSessionUuid,
      localBeneficiaryId = localBeneficiaryId,
      localSubmissionUuid = localSubmissionUuid,
      deliveryFormFilledOn = deliveryFormFilledOn,
      childIds = childIds,
    )

    visitScheduleCoordinator.onDeliveryRecorded(
      ScheduleContext(
        localBeneficiaryId = localBeneficiaryId,
        // registrationDate is unused by the PP/NN generators (see ScheduleContext's own doc and
        // NnScheduleGeneratorTest's fixtures) — deliveryDate is passed here purely to satisfy the
        // non-null contract, same convention already established for this call site's tests.
        registrationDate = deliveryDate,
        deliveryDate = deliveryDate,
        deliveryFormFilledOn = deliveryFormFilledOn,
      ),
    )

    childIds
  }

  /** [childIds] == null means no live birth: skip CHILD_REGISTRATION entirely and go straight to
   * PP1 (see [org.armman.sakhi.data.forms.SubmissionResponseData.childBeneficiaryIds]'s doc — this
   * is the one call site in the app that must honor that distinction). An empty (non-null) list is
   * treated the same as null for stepping purposes — the backend contract says this should not
   * happen, but there is no live child to register either way. */
  private suspend fun advanceSession(
    localSessionUuid: String,
    localBeneficiaryId: String,
    localSubmissionUuid: String,
    deliveryFormFilledOn: LocalDate,
    childIds: List<String>?,
  ) {
    val now = Instant.now().toEpochMilli()
    val existing = deliverySessionRepository.getBySessionUuid(localSessionUuid)
    deliverySessionRepository.save(
      DeliverySessionEntity(
        localSessionUuid = localSessionUuid,
        localBeneficiaryId = localBeneficiaryId,
        step = if (childIds.isNullOrEmpty()) DeliverySessionStep.PP1 else DeliverySessionStep.CHILD_REGISTRATION,
        deliverySubmissionLocalUuid = localSubmissionUuid,
        deliveryFormFilledOn = deliveryFormFilledOn,
        child1BeneficiaryId = childIds?.getOrNull(0),
        child2BeneficiaryId = childIds?.getOrNull(1),
        child3BeneficiaryId = childIds?.getOrNull(2),
        nextChildIndexToRegister = 0,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
        updatedAtEpochMillis = now,
      ),
    )
  }

  private companion object {
    const val FORM_CODE_DELIVERY_VISIT = "DELIVERY_VISIT"
  }
}
