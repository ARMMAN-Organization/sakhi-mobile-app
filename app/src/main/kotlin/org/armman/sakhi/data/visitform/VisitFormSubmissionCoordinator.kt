package org.armman.sakhi.data.visitform

import android.util.Log
import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.forms.VisitCodeFormResolver
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.sameSessionNnVisit
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val CATEGORY_VISIT_STATUS = "VISIT_STATUS"
private const val VALUE_CODE_COMPLETED = "COMPLETED"

/** Temporary diagnostic tag for the online-enrollment-to-visit-submit chain (CR-026 debugging). */
private const val TAG = "SakhiSync"

/** PP1's [VisitScheduleEntity.sequenceNo] within the PP family — the only PP visit this
 * coordinator's delivery-session hook (see [VisitFormSubmissionCoordinator.advanceDeliverySessionIfDue])
 * cares about; PP2-PP5 belong to the regular tracker, not the CR-042 session. */
private const val PP1_SEQUENCE_NO = 1

/**
 * Everything that can stop [VisitFormSubmissionCoordinator.submit] from completing — mirrors
 * [org.armman.sakhi.data.forms.DynamicFormSubmissionException]'s shape (a sentence for the UI via
 * [userMessage], the diagnostic detail on [Exception.message] for logs), but for the visit variant
 * of the two-call submit rather than enrollment's.
 */
sealed class VisitFormSubmissionException(message: String) : Exception(message) {
  open val userMessage: String get() = SubmitErrorCopy.GENERIC

  data object NoActiveSession : VisitFormSubmissionException("No signed-in Sakhi session")

  data object ScheduleNotFound :
    VisitFormSubmissionException("No local visit_schedules row for this localScheduleUuid")

  /**
   * `POST /visits` needs the *server* schedule id and beneficiary id. The immediate online submit
   * path (button tap) still fails outright when this happens, exactly as before CR-026b, rather
   * than silently proceeding with a request the backend would 422 anyway ("scheduleId does not
   * reference an existing visit schedule"). CR-026b's background executor treats this same
   * exception as retryable instead (see [VisitFormSyncExecutor]) rather than a permanent failure,
   * since the beneficiary/schedule may simply not have finished syncing yet.
   */
  data class NotYetSynced(val scheduleSynced: Boolean, val beneficiarySynced: Boolean) :
    VisitFormSubmissionException(
      "Cannot submit: schedule synced=$scheduleSynced, beneficiary synced=$beneficiarySynced",
    ) {
    override val userMessage: String
      get() = "This beneficiary's data hasn't finished syncing yet. Connect to the internet, use Data Upload, then try again."
  }

  data object VisitStatusLookupUnavailable :
    VisitFormSubmissionException("VISIT_STATUS/COMPLETED lookup value not available")

  /**
   * CR-042 defect-fix defense in depth (2026-08-21): NN now only ever generates anchored to a
   * registered child's own local beneficiary id (see
   * [org.armman.sakhi.data.schedule.VisitScheduleCoordinator.onDeliveryRecorded]'s doc), so a
   * genuinely new NN schedule row should never reach this exception. It exists for a schedule row
   * that predates this fix shipping — created by the old code against the MOTHER's own id, with no
   * child ever registered behind it — so an app update does not silently let a stale, wrongly
   * anchored NN visit complete. Surfaced as a submission failure rather than silently skipped so
   * the Sakhi knows to complete Child Registration first, same as a fresh session would require.
   */
  data object NoRegisteredChildForNnVisit : VisitFormSubmissionException(
    "Cannot submit an NN visit for a beneficiary with no registered child behind it",
  ) {
    override val userMessage: String
      get() = "This infant hasn't been registered yet. Complete Child Registration before this visit."
  }

  data class VisitInstanceCreationFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
  ) : VisitFormSubmissionException("POST /visits failed: HTTP $httpCode — $body") {
    override val userMessage: String get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap())
  }

  data object NoVisitIdReturned :
    VisitFormSubmissionException("Visit instance created but no id was returned in the response")

  data class FormSubmissionFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
    val violations: List<String> = emptyList(),
    val formCode: String = "ANC_VISIT",
  ) : VisitFormSubmissionException("POST /forms/$formCode/submissions failed: HTTP $httpCode — $body") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }
}

/**
 * Orchestrates the online-only visit-submit slice: `POST /visits` (create the visit instance from
 * the beneficiary's local schedule row) → `POST /forms/:formCode/submissions` (formCode resolved
 * from the schedule row's VisitCodeType via [VisitCodeFormResolver] — CR-033/CR-034) using the
 * *returned* visit id, then flips the local schedule row to [VisitScheduleStatus.COMPLETED] so the
 * Beneficiary Profile's "See Visits" list reflects it immediately.
 *
 * Called two ways (CR-026b):
 *  - Directly from [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.onFinish] via
 *    [org.armman.sakhi.data.visitform.VisitFormDraftRepository.submitDraft]'s immediate-online
 *    attempt — the original, unchanged call shape ([existingVisitId]/[onVisitCreated] both
 *    default to their no-ops so this path behaves exactly as before offline support existed).
 *  - From [VisitFormSyncExecutor]'s background retry, which passes [existingVisitId] once a
 *    prior attempt's `POST /visits` succeeded but the form submission step failed — see
 *    [existingVisitId]'s doc for why that matters.
 *
 * Still requires connectivity and a beneficiary+schedule that have already synced
 * ([VisitFormSubmissionException.NotYetSynced]) — that has not changed. What changed is what the
 * *caller* does when that happens: the immediate online path still surfaces it as a failure right
 * away (same as before); the background executor instead leaves the draft queued and retries on
 * the next sync pass, since the beneficiary may simply not have finished syncing yet.
 *
 * ### CR-042 delivery-session step advancement
 * Every scheduled visit in the app — ANC, PP, NN, INC — submits through this one coordinator, so
 * it is also the single place that can advance a CR-042 Delivery Event Session past its
 * [DeliverySessionStep.PP1]/[DeliverySessionStep.NN] steps once the corresponding visit form is
 * actually submitted. [advanceDeliverySessionIfDue] runs only after the form submission above has
 * already succeeded, and only touches the session when it is sitting at *exactly* the step this
 * visit represents (PP1 while at [DeliverySessionStep.PP1], either NN1 or NN2 while at
 * [DeliverySessionStep.NN]) — a beneficiary with no active session, or one sitting at a different
 * step, is untouched, so every other visit in the app is unaffected.
 */
@Singleton
class VisitFormSubmissionCoordinator @Inject constructor(
  private val visitApi: VisitApi,
  private val formSubmissionApi: FormSubmissionApi,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val lookupRepository: LookupRepository,
  private val sessionStore: SessionStore,
  private val visitCodeFormResolver: VisitCodeFormResolver,
  private val formAuditRepository: FormAuditRepository,
  private val deliverySessionRepository: DeliverySessionRepository,
  private val childFormDraftDao: ChildFormDraftDao,
) {

  suspend fun submit(
    localScheduleUuid: String,
    formVersionId: String,
    answers: FormAnswers,
    visitDate: LocalDate,
    /**
     * The draft's [VisitFormDraftEntity.localSubmissionUuid] — minted once when the draft was
     * first saved (see [RoomVisitFormDraftRepository]) and passed in as-is here on every attempt,
     * including retries, so `POST /forms/:formCode/submissions` always replays the same
     * idempotency key instead of a fresh one per call. Required (no default), unlike
     * [existingVisitId]: this value is client-generated and always exists by the time [submit]
     * is called, so there is no "first attempt" case that needs a default to fall back on.
     */
    localSubmissionUuid: String,
    /**
     * Non-null only when the background executor is resuming a draft whose `POST /visits` call
     * already succeeded on a previous attempt (the id was captured via [onVisitCreated] then).
     * Skips step 1 entirely and reuses this id for step 2 — without it, a retry after a
     * step-2-only failure would call `POST /visits` again and create a second visit instance for
     * the same visit. The immediate online path (button tap) never has one of these yet, so it
     * always creates fresh, exactly as before this parameter existed.
     */
    existingVisitId: String? = null,
    /**
     * Fired the moment step 1 succeeds, with the server's visit id — before step 2 is even
     * attempted. The background executor uses this to persist that id onto the draft immediately,
     * so a crash/process-death between the two calls still leaves [existingVisitId] resumable on
     * the next pass. The immediate online path doesn't need this (a failure there is surfaced
     * right away, not retried later), so it passes nothing and gets the default no-op.
     */
    onVisitCreated: suspend (String) -> Unit = {},
  ): Result<Unit> = runCatching {
    val sakhiId = sessionStore.readSession()?.subjectId
      ?: throw VisitFormSubmissionException.NoActiveSession

    val schedule = visitScheduleRepository.getByLocalScheduleUuid(localScheduleUuid)
      ?: throw VisitFormSubmissionException.ScheduleNotFound
    val formCode = visitCodeFormResolver.resolve(schedule.visitType)

    // CR-042 defense in depth — see NoRegisteredChildForNnVisit's own doc: a fresh NN row can
    // never fail this check post-fix (it's only ever generated against a registered child's own
    // id), so this only ever fires against a stale pre-fix row.
    if (schedule.visitType == VisitCodeType.NN &&
      childFormDraftDao.getByLocalBeneficiaryId(schedule.localBeneficiaryId) == null
    ) {
      throw VisitFormSubmissionException.NoRegisteredChildForNnVisit
    }

    val serverScheduleId = schedule.serverScheduleId
    val serverBeneficiaryId = schedule.serverBeneficiaryId
    if (serverScheduleId == null || serverBeneficiaryId == null) {
      Log.w(
        TAG,
        "submit($localScheduleUuid): NotYetSynced — serverScheduleId=$serverScheduleId, " +
          "serverBeneficiaryId=$serverBeneficiaryId",
      )
      throw VisitFormSubmissionException.NotYetSynced(
        scheduleSynced = serverScheduleId != null,
        beneficiarySynced = serverBeneficiaryId != null,
      )
    }

    val visitId = existingVisitId ?: run {
      val completedStatusId = lookupRepository.findValue(CATEGORY_VISIT_STATUS, VALUE_CODE_COMPLETED)?.id
        ?: throw VisitFormSubmissionException.VisitStatusLookupUnavailable

      val visitInstanceRequest = CreateVisitInstanceRequestDto(
        scheduleId = serverScheduleId,
        beneficiaryId = serverBeneficiaryId,
        sakhiId = sakhiId,
        localVisitUuid = newLocalVisitUuid(),
        statusLookupValueId = completedStatusId,
        actualVisitDate = visitDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
        completedAt = Instant.now().toString(),
      )
      val visitInstanceResponse = visitApi.createVisitInstance(visitInstanceRequest)
      if (!visitInstanceResponse.isSuccessful) {
        val rawBody = visitInstanceResponse.errorBody()?.string()
        val apiError = ApiErrorParser.parse(rawBody)
        throw VisitFormSubmissionException.VisitInstanceCreationFailed(
          httpCode = visitInstanceResponse.code(),
          body = rawBody,
          apiMessage = apiError.message?.takeIf { it != rawBody },
        )
      }
      val newVisitId = visitInstanceResponse.body()?.data?.id
        ?: throw VisitFormSubmissionException.NoVisitIdReturned
      onVisitCreated(newVisitId)
      newVisitId
    }

    val submissionRequest = CreateSubmissionRequestDto(
      formVersionId = formVersionId,
      beneficiaryId = serverBeneficiaryId,
      visitId = visitId,
      localSubmissionUuid = localSubmissionUuid,
      formData = answers.singleValues + answers.multiValues,
    )
    val submissionResponse = formSubmissionApi.createSubmission(formCode, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val rawSubmissionBody = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawSubmissionBody)
      throw VisitFormSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = rawSubmissionBody,
        apiMessage = apiError.message?.takeIf { it != rawSubmissionBody },
        violations = apiError.violations,
        formCode = formCode,
      )
    }

    // CR-035: logged only on success, immediately after the submission call succeeds.
    formAuditRepository.recordSubmitted(localScheduleUuid, formCode)
    visitScheduleRepository.updateStatus(localScheduleUuid, VisitScheduleStatus.COMPLETED)

    // CR-042: only after the form submission above has actually succeeded — see this class's own
    // doc for why a generic hook here, rather than a PP/NN-specific coordinator, is correct.
    advanceDeliverySessionIfDue(schedule)
  }

  /**
   * Advances this beneficiary's active [DeliverySessionEntity], if any, when the just-submitted
   * [schedule] is exactly the visit its current step is waiting on. A no-op in every other case:
   * no active session, or an active session sitting at a step this visit doesn't represent (e.g.
   * an ANC visit while a session sits at [DeliverySessionStep.PP1], or a PP2 visit — PP1 is the
   * only PP visit this session cares about).
   *
   * - [DeliverySessionStep.PP1] + this visit is PP1 (`visitType == PP`, `sequenceNo == 1`):
   *   advances to [DeliverySessionStep.NN] if [sameSessionNnVisit] finds one among this
   *   beneficiary's still-open NN rows (measured against the session's own
   *   [DeliverySessionEntity.deliveryFormFilledOn]), else straight to [DeliverySessionStep.DONE].
   * - [DeliverySessionStep.NN] + this visit is NN (`visitType == NN` — NN1 or NN2, "either NN
   *   visit" per CR-042): advances to [DeliverySessionStep.DONE]. Deliberately does not
   *   re-verify which NN row this is — by the time a session reaches [DeliverySessionStep.NN],
   *   at most one NN row can still be open as *this* session's own visit (see
   *   [sameSessionNnVisit]'s doc), so any NN submission that lands while the session is still at
   *   this step is that one.
   * - [DeliverySessionEntity.deliveryFormFilledOn] is null (only possible on a session row that
   *   predates the v11 migration and never advanced past [DeliverySessionStep.DELIVERY_FORM] in
   *   the field): falls back to [DeliverySessionStep.DONE] rather than guessing, since there is no
   *   same-session NN visit this coordinator can safely resolve without it.
   */
  private suspend fun advanceDeliverySessionIfDue(schedule: VisitScheduleEntity) {
    val session = deliverySessionRepository.getActiveForBeneficiary(schedule.localBeneficiaryId)
      ?: return

    val newStep = when {
      session.step == DeliverySessionStep.PP1 &&
        schedule.visitType == VisitCodeType.PP &&
        schedule.sequenceNo == PP1_SEQUENCE_NO ->
        if (hasSameSessionNnVisit(session)) DeliverySessionStep.NN else DeliverySessionStep.DONE

      session.step == DeliverySessionStep.NN && schedule.visitType == VisitCodeType.NN ->
        DeliverySessionStep.DONE

      else -> return
    }

    deliverySessionRepository.save(
      session.copy(step = newStep, updatedAtEpochMillis = Instant.now().toEpochMilli()),
    )
  }

  private suspend fun hasSameSessionNnVisit(session: DeliverySessionEntity): Boolean {
    val deliveryFormFilledOn = session.deliveryFormFilledOn ?: return false
    val openNnVisits = visitScheduleRepository.getOpenByType(session.localBeneficiaryId, VisitCodeType.NN)
    return sameSessionNnVisit(openNnVisits, deliveryFormFilledOn) != null
  }
}

/** Fresh per-attempt, and correctly so — unlike [newLocalVisitSubmissionUuid] below, nothing about
 * `POST /visits` needs this local id to survive a retry (a resumed attempt skips step 1 entirely
 * via [VisitFormSubmissionCoordinator.submit]'s `existingVisitId`, so this function is never even
 * called on that path). */
fun newLocalVisitUuid(): String = UUID.randomUUID().toString()

/** Generates the once-per-draft, stable-across-retries id [VisitFormSubmissionCoordinator.submit]
 * needs for its `localSubmissionUuid` param — same once-per-draft contract as
 * [org.armman.sakhi.data.forms.newLocalSubmissionUuid]. [RoomVisitFormDraftRepository] is the only
 * caller: it mints this once when a draft is first saved and preserves it across every re-save, so
 * a retry after an ambiguous network failure replays the same idempotency key on
 * `POST /forms/:formCode/submissions` instead of risking a duplicate `form_submissions` row
 * server-side. */
fun newLocalVisitSubmissionUuid(): String = UUID.randomUUID().toString()
