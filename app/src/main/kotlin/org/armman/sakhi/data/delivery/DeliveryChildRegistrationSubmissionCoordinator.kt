package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.childregistration.ChildFormDraftPayload
import org.armman.sakhi.data.childregistration.childFormDraftGson
import org.armman.sakhi.data.childregistration.childFormDraftPayloadKey
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.schedule.ScheduleContext
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor
import org.armman.sakhi.data.schedule.sameSessionNnVisit
import java.time.Instant
import java.time.LocalDate
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
  private val childFormDraftDao: ChildFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val visitScheduleCoordinator: VisitScheduleCoordinator,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val visitScheduleSyncExecutor: VisitScheduleSyncExecutor,
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

    // CR-042 defect fix: fetched up front (not just inside advanceSessionAfterChildRegistered) so
    // deliveryFormFilledOn is available for this child's own schedule generation below, and so a
    // genuinely missing session row fails loudly via NoActiveDeliverySession instead of this
    // submission silently doing nothing to advance the session afterwards.
    val session = deliverySessionRepository.getBySessionUuid(localSessionUuid)
      ?: throw DeliveryChildRegistrationSubmissionException.NoActiveDeliverySession

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

    // CR-042 defect fix (part 1 of 2): this child previously had NO local beneficiary record at
    // all — invisible on My Beneficiaries, and with nothing for a schedule to anchor to but the
    // mother's own id. serverBeneficiaryId is reused as the local id too, same "known id, don't
    // mint or re-resolve one" convention this class's own doc already established for the
    // submission above — idempotent across a retry, and matches this child's real
    // beneficiary-service id from its very first local row.
    saveChildDraftLocally(
      localBeneficiaryId = serverBeneficiaryId,
      formVersionId = formVersionId,
      localSubmissionUuid = localSubmissionUuid,
      answers = answers,
      deliveryFormFilledOn = session.deliveryFormFilledOn,
    )

    // CR-042 defect fix (part 2 of 2): generate this child's own NN + INC schedule now, anchored
    // to ITS OWN local id — never the mother's (see VisitScheduleCoordinator.onDeliveryRecorded's
    // doc for why NN no longer generates there). Best-effort: a schedule can be regenerated later,
    // a lost registration cannot — same stance ChildEnrolmentScheduleTrigger already takes for the
    // standalone (non-delivery-linked) registration flow.
    generateChildSchedule(
      localBeneficiaryId = serverBeneficiaryId,
      answers = answers,
      deliveryFormFilledOn = session.deliveryFormFilledOn,
    )

    // This whole submit() already required backend connectivity to get this far, so push the
    // freshly generated NN/INC schedule up immediately too — without this it sits unsynced until
    // the Sakhi's next manual Data Upload even though nothing is stopping it from going now.
    // Mirrors RoomDynamicFormDraftRepository's/RoomDeliveryFormDraftRepository's CR-022/CR-042
    // pattern for MOTHER_REGISTRATION/DELIVERY_VISIT, which this coordinator never had.
    // Best-effort: any failure here leaves the schedule PENDING for the next Data Upload exactly
    // as before, and must never turn a successful child registration into a reported failure.
    runCatching { visitScheduleSyncExecutor.run() }

    advanceSessionAfterChildRegistered(session)
  }

  /** Persists this child's own local draft row + encrypted answers payload — the same hybrid split
   * [org.armman.sakhi.data.childregistration.RoomChildFormDraftRepository] uses for a standalone
   * registration, so [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource] (which
   * reads [ChildFormDraftDao] directly) surfaces this child on My Beneficiaries exactly like any
   * other. `syncStatus = SYNCED` because, unlike the standalone flow's save-then-sync split, the
   * submission above has already succeeded by the time this runs — there is no separate sync step
   * left to queue. [deliveryFormFilledOn] falls back to today only for a pre-migration session row
   * that predates that column ever being populated — see [DeliverySessionEntity] for why that
   * should not happen in practice. */
  private suspend fun saveChildDraftLocally(
    localBeneficiaryId: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    deliveryFormFilledOn: LocalDate?,
  ) {
    val payload = ChildFormDraftPayload(
      answers = answers,
      registrationDateIso = (deliveryFormFilledOn ?: LocalDate.now()).toString(),
    )
    secureStore.putString(childFormDraftPayloadKey(localBeneficiaryId), childFormDraftGson.toJson(payload))

    val now = Instant.now().toEpochMilli()
    childFormDraftDao.upsert(
      ChildFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = FORM_CODE_CHILD_REGISTRATION,
        formVersionId = formVersionId,
        localSubmissionUuid = localSubmissionUuid,
        syncStatus = EnrollmentSyncStatus.SYNCED,
        createdAtEpochMillis = now,
        lastAttemptAtEpochMillis = now,
        retryCount = 0,
        remoteBeneficiaryId = localBeneficiaryId,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
  }

  /** Generates NN + INC for this child, anchored to its own [localBeneficiaryId] — never the
   * mother's. No-op if [deliveryFormFilledOn] is null (pre-migration session row) or this child's
   * own date-of-birth answer is missing/unparseable: a malformed answer must not lose a
   * registration the Sakhi has already completed, same stance as
   * [org.armman.sakhi.data.schedule.ChildEnrolmentScheduleTrigger.generateFor]. Passes `dob` as
   * both [ScheduleContext.dob] and [ScheduleContext.deliveryDate] deliberately: for a newborn
   * registered through THIS delivery-linked flow the two are the same date, and setting
   * [ScheduleContext.deliveryDate] (alongside [deliveryFormFilledOn]) is what makes
   * [VisitScheduleCoordinator.onChildRegistered] generate NN at all — see that function's
   * `hasDeliveryDetails` gate. */
  private suspend fun generateChildSchedule(
    localBeneficiaryId: String,
    answers: FormAnswers,
    deliveryFormFilledOn: LocalDate?,
  ) {
    if (deliveryFormFilledOn == null) return
    val dob = answers.valueOf(ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT)
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
      ?: return

    runCatching {
      visitScheduleCoordinator.onChildRegistered(
        ScheduleContext(
          localBeneficiaryId = localBeneficiaryId,
          registrationDate = deliveryFormFilledOn,
          dob = dob,
          deliveryDate = dob,
          deliveryFormFilledOn = deliveryFormFilledOn,
        ),
      )

      // BUG FIX (found in QA 2026-08-21): VisitScheduleCoordinator's own backfillServerBeneficiaryId
      // only stamps serverBeneficiaryId onto freshly generated rows by copying it off an EXISTING
      // row for that same beneficiary that already has one — it assumes a beneficiary always syncs
      // to the backend as a separate, earlier step before any schedule is ever generated for them
      // (true for the mother, and for the standalone child-registration flow, both of which sync
      // via their own background executor first). This child has no earlier synced row to copy
      // from — its very first schedule rows are the ones just generated above — so without this
      // explicit attach, NN1/NN2/INC1 all save with serverBeneficiaryId = null and
      // VisitFormSubmissionCoordinator.submit rejects them with NotYetSynced even on a perfectly
      // good connection, since that check is a data-completeness gate, not a live connectivity
      // check. localBeneficiaryId IS the server id here (see this class's own doc on why they're
      // the same value), so this is not a network call — it's known immediately, unconditionally.
      visitScheduleRepository.attachServerBeneficiaryId(localBeneficiaryId, localBeneficiaryId)
    }
  }

  /** Advances [session]'s step now that one more child is registered — takes the already-fetched
   * row (see [submit]) rather than re-reading it, since a re-read here could race a concurrent
   * update and silently revert [nextChildIndexToRegister].
   *
   * Bug fix (2026-09-02): when every child is now registered, this used to jump straight to
   * [DeliverySessionStep.PP1] unconditionally — even when the Sakhi had already submitted PP1 out
   * of sequence, straight from "See Visits", before finishing child registration (see
   * [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationViewModel.findPp1ScheduleUuid]'s own
   * bug-fix doc for the full user-visible symptom this closes: PP1 auto-reopening as a blank form).
   * [DeliverySessionStep]'s own doc says steps are "strictly forward-moving" and never regress a
   * session to an earlier one — silently resetting an already-past-PP1 session back to PP1 broke
   * that invariant, and left the session stuck at PP1 forever afterwards (nothing will ever submit
   * that already-completed PP1 a second time, so
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.advanceDeliverySessionIfDue]
   * never gets a chance to move it on). [resolveStepAfterAllChildrenRegistered] now resolves the
   * step the same way a normally-ordered session would have: only PP1 if it is genuinely still
   * open; otherwise straight to NN/DONE, matching what [advanceDeliverySessionIfDue] itself would
   * have computed at PP1-submission time.
   */
  private suspend fun advanceSessionAfterChildRegistered(session: DeliverySessionEntity) {
    val totalChildren = listOfNotNull(
      session.child1BeneficiaryId,
      session.child2BeneficiaryId,
      session.child3BeneficiaryId,
    ).size
    val nextIndex = session.nextChildIndexToRegister + 1
    val allChildrenRegistered = nextIndex >= totalChildren
    val newStep = if (allChildrenRegistered) {
      resolveStepAfterAllChildrenRegistered(session)
    } else {
      DeliverySessionStep.CHILD_REGISTRATION
    }
    deliverySessionRepository.save(
      session.copy(
        step = newStep,
        nextChildIndexToRegister = nextIndex,
        updatedAtEpochMillis = Instant.now().toEpochMilli(),
      ),
    )
  }

  /** The true next step once every child is registered — [DeliverySessionStep.PP1] only if the
   * mother's PP1 visit is genuinely still open; [DeliverySessionStep.NN] if PP1 is already
   * COMPLETED (out-of-sequence submission) and one of this delivery's registered children has a
   * same-session NN visit still open ([sameSessionNnVisit] — the exact rule
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator]'s own PP1->NN transition
   * uses, applied per-child here since NN is anchored to each child's own local beneficiary id,
   * never the mother's — see [DeliveryChildRegistrationSubmissionCoordinator]'s own class doc);
   * else [DeliverySessionStep.DONE]. Falls back to DONE (never guesses NN) when
   * [DeliverySessionEntity.deliveryFormFilledOn] is null — same defensive stance
   * [advanceDeliverySessionIfDue] takes for a pre-migration session row. */
  private suspend fun resolveStepAfterAllChildrenRegistered(session: DeliverySessionEntity): DeliverySessionStep {
    val pp1AlreadyCompleted = visitScheduleRepository.getActiveForBeneficiary(session.localBeneficiaryId)
      .any { it.visitType == VisitCodeType.PP && it.sequenceNo == 1 && it.status == VisitScheduleStatus.COMPLETED }
    if (!pp1AlreadyCompleted) return DeliverySessionStep.PP1

    val deliveryFormFilledOn = session.deliveryFormFilledOn ?: return DeliverySessionStep.DONE
    val childBeneficiaryIds = listOfNotNull(
      session.child1BeneficiaryId,
      session.child2BeneficiaryId,
      session.child3BeneficiaryId,
    )
    val openNnVisits = childBeneficiaryIds.flatMap { visitScheduleRepository.getOpenByType(it, VisitCodeType.NN) }
    return if (sameSessionNnVisit(openNnVisits, deliveryFormFilledOn) != null) {
      DeliverySessionStep.NN
    } else {
      DeliverySessionStep.DONE
    }
  }

  private companion object {
    const val FORM_CODE_CHILD_REGISTRATION = "CHILD_REGISTRATION"
  }
}
