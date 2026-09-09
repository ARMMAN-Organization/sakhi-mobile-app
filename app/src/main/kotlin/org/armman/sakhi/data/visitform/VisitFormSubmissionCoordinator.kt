package org.armman.sakhi.data.visitform

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import org.armman.sakhi.data.lmpchange.LmpChangeCapture
import org.armman.sakhi.data.lmpchange.LmpChangeRepository
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.referral.CreateReferralOutcome
import org.armman.sakhi.data.referral.Referral
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.referral.ReferralLinkEntity
import org.armman.sakhi.data.referral.ReferralCapture
import org.armman.sakhi.data.referral.ReferralRepository
import com.google.gson.Gson
import org.armman.sakhi.data.riskassessment.RiskAssessmentDao
import org.armman.sakhi.data.riskassessment.RiskAssessmentEntity
import org.armman.sakhi.data.riskassessment.RiskFlagEntity
import org.armman.sakhi.data.rules.RuleSetIds
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.SameSessionNnVisitResolver
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val CATEGORY_VISIT_STATUS = "VISIT_STATUS"
private const val VALUE_CODE_COMPLETED = "COMPLETED"

/**
 * Retry policy for [VisitFormSubmissionCoordinator.createRiskAssessmentWithRetry] —
 * confirmed with backend 2026-08-27: grading is synchronous server-side, not queued, so a
 * failure here is permanent (nothing server-side will complete it later just because more
 * time passed) — a short, tight retry to catch a transient network blip is useful; a long
 * backoff is not, since there is nothing to "wait out". Product decision (Option A): silent,
 * no Sakhi-facing indicator either way — see that function's own doc.
 */
private const val RISK_ASSESSMENT_MAX_ATTEMPTS = 3
private const val RISK_ASSESSMENT_RETRY_DELAY_MILLIS = 15_000L

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
 * Domain-level result of [VisitFormSubmissionCoordinator.submit] beyond plain success/failure —
 * carries the CCV per-visit HR re-evaluation signal (CR-Closure-01 items #5/#6, backend contract
 * confirmed 2026-08-31) up through every layer between the coordinator and
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel], which is the only current reader.
 *
 * Both fields default to "no signal" — every visit form code except the LAST `CCV_VISIT` for a
 * child gets defaults here, since [org.armman.sakhi.data.forms.SubmissionResponseData]'s own two
 * backing fields are absent (`null`) on every response but that one boundary case. See that DTO's
 * doc for the full contract; this type exists only so the domain layers between here and the
 * ViewModel don't have to depend on the wire DTO directly.
 */
data class VisitSubmitOutcome(
  val closureDeferredForExtension: Boolean? = null,
  val extensionVisit: org.armman.sakhi.data.forms.ExtensionVisitWindowDto? = null,
)

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
  private val riskAssessmentApi: RiskAssessmentApi,
  private val referralRepository: ReferralRepository,
  private val referralLinkDao: ReferralLinkDao,
  private val riskAssessmentDao: RiskAssessmentDao,
  /** Task 2 (LMP/Reopen/Referral/Audit task list). */
  private val lmpChangeRepository: LmpChangeRepository,
  /** CR-Delivery-01: single source of truth for "which same-session NN visit is due" -- see
   * that class's own doc for why this replaced this coordinator's own former copy of the same
   * lookup, which queried the wrong (mother's) beneficiary id. */
  private val sameSessionNnVisitResolver: SameSessionNnVisitResolver,
) {

  private val riskAssessmentJsonMapper = Gson()

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
     * CR-Referral-01: whatever the Sakhi filled on the visit form's standalone Referral tab
     * (date/facility/type) — see [org.armman.sakhi.data.visitform.VisitFormDraftPayload.referralCapture]'s
     * doc. Null when she left that tab untouched, in which case [maybeCreateReferral] never fires
     * even if the risk assessment below comes back with a referral trigger — this app never
     * creates a referral the Sakhi didn't actually fill in facility/type for.
     */
    referralCapture: ReferralCapture? = null,
    /**
     * Task 2 (LMP/Reopen/Referral/Audit task list): whatever the Sakhi filled on ANC_VISIT's own
     * sonography-confirmation branch (`lmp_date_edit` + `upload_sonography_report_image`) — see
     * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.lmpChangeCaptureOrNull]'s doc. Null
     * when she left that branch untouched (sonography = No, or the branch is incomplete), in which
     * case [maybeCreateLmpChangeRequest] never fires. Only meaningful for ANC_VISIT — every other
     * caller of [submit] passes nothing and gets the default no-op, same as [referralCapture].
     */
    lmpChangeCapture: LmpChangeCapture? = null,
    /**
     * Fired the moment step 1 succeeds, with the server's visit id — before step 2 is even
     * attempted. The background executor uses this to persist that id onto the draft immediately,
     * so a crash/process-death between the two calls still leaves [existingVisitId] resumable on
     * the next pass. The immediate online path doesn't need this (a failure there is surfaced
     * right away, not retried later), so it passes nothing and gets the default no-op.
     */
    onVisitCreated: suspend (String) -> Unit = {},
  ): Result<VisitSubmitOutcome> = runCatching {
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
    val submissionData = submissionResponse.body()?.data
    val serverSubmissionId = submissionData?.id

    // CR-035: logged only on success, immediately after the submission call succeeds.
    formAuditRepository.recordSubmitted(localScheduleUuid, formCode)
    visitScheduleRepository.updateStatus(localScheduleUuid, VisitScheduleStatus.COMPLETED)

    // CR-042: only after the form submission above has actually succeeded — see this class's own
    // doc for why a generic hook here, rather than a PP/NN-specific coordinator, is correct.
    advanceDeliverySessionIfDue(schedule)

    // Task 2: only after the form submission above has actually succeeded, same rationale as
    // advanceDeliverySessionIfDue and triggerRiskAssessment below — see maybeCreateLmpChangeRequest's
    // own doc for why a failure here never fails this whole submit() call. Independent of the risk
    // assessment/referral chain below (an LMP correction doesn't need a risk-assessment response),
    // so it runs here rather than being threaded through triggerRiskAssessment.
    maybeCreateLmpChangeRequest(
      beneficiaryId = serverBeneficiaryId,
      capture = lmpChangeCapture,
    )

    // Phase 5 (CR — offline high-risk rule evaluation): only after the form submission above has
    // actually succeeded, same as advanceDeliverySessionIfDue — see triggerRiskAssessment's own
    // doc for why a failure here never fails this whole submit() call.
    triggerRiskAssessment(
      localScheduleUuid = localScheduleUuid,
      formCode = formCode,
      serverBeneficiaryId = serverBeneficiaryId,
      visitId = visitId,
      serverSubmissionId = serverSubmissionId,
      answers = answers,
      referralCapture = referralCapture,
    )

    // CR-Closure-01 items #5/#6: carries the CCV per-visit HR re-evaluation signal back to the
    // caller — see VisitSubmitOutcome's own doc. Both fields default to "no signal" for every
    // form code/visit but the one boundary case, so every existing call site of submit() (which
    // used to just get Unit back) keeps behaving exactly as before unless it explicitly reads this.
    VisitSubmitOutcome(
      closureDeferredForExtension = submissionData?.closureDeferredForExtension,
      extensionVisit = submissionData?.extensionVisit,
    )
  }

  /**
   * `POST /risk-assessments` (backend-confirmed 2026-08-24, closing item 4 of
   * `backend-prompt-risk-grading-ondevice.md`) — the same call `visit-form-service` already makes
   * server-side today after a visit-linked submission; this app also calls it directly so the
   * authoritative server-side grading happens the moment sync completes, not only whenever that
   * internal trigger runs. Idempotent by `submissionId` per backend, so calling this on every
   * `submit()` attempt (including retries) is safe — never re-evaluates twice for the same
   * submission.
   *
   * `formCode -> riskPhase`: confirmed by backend 2026-08-24 — `INFANT_VISIT` sends `"INC"`, not
   * a distinct/missing phase. Traced reason: per the seed file's own comment, `INFANT_VISIT` IS
   * `INC_VISIT` under its pre-rename name ("Same schema content as INFANT_VISIT... INC_VISIT is
   * the name new client code should move to") — same phase, not two related-but-distinct ones.
   * Functionally confirmed too: `riskPhase` is a server-side filter key
   * (`findConditionIdsByPhase(phase)` against `risk_conditions.phase`), and every infant-pack
   * condition is seeded with `phase: 'INC'` — none as `NN`, so sending `NN` (or `CCV`) for an
   * `INFANT_VISIT` submission would resolve zero condition ids and fail the assessment. `INC` is
   * also the only value inside backend's `NO_IMPROVEMENT_PHASES = {NN, INC, CCV}` set that
   * correctly preserves the "3 consecutive visits, no improvement" referral-escalation logic for
   * this form — any other value would silently disable it.
   *
   * Best-effort, not submission-blocking: by the time this runs, `POST /visits` and
   * `POST /forms/:formCode/submissions` have both already succeeded and the local schedule is
   * already marked COMPLETED — a failure here must not flip an otherwise-successful visit
   * submission to Failed/retryable.
   *
   * Retries via [createRiskAssessmentWithRetry] (added 2026-08-27, product decision: silent,
   * Option A — no Sakhi-facing indicator either way) before giving up. Confirmed with backend:
   * grading is synchronous, not queued server-side, so a failure here is permanent — nothing
   * completes it later just because time passed. A short, tight retry only helps catch a
   * transient failure in *this* request (a network blip); it is not "waiting for a queue to
   * drain", so the retry policy is short attempts close together, not a long backoff. If every
   * attempt fails, this beneficiary simply has no risk assessment for this visit until her next
   * visit happens to produce one — a known, accepted gap (no server-side self-healing exists),
   * not a bug in this retry logic.
   */
  private suspend fun triggerRiskAssessment(
    localScheduleUuid: String,
    formCode: String,
    serverBeneficiaryId: String,
    visitId: String,
    serverSubmissionId: String?,
    answers: FormAnswers,
    referralCapture: ReferralCapture?,
  ) {
    val riskPhase = riskPhaseFor(formCode) ?: return
    val ruleSetId = riskRuleSetIdFor(formCode) ?: return
    if (serverSubmissionId == null) {
      Log.w(TAG, "triggerRiskAssessment($formCode): no server submissionId returned, skipping")
      return
    }

    val request = CreateRiskAssessmentRequestDto(
      beneficiaryId = serverBeneficiaryId,
      visitId = visitId,
      submissionId = serverSubmissionId,
      ruleSetId = ruleSetId,
      riskPhase = riskPhase,
      answers = answers.singleValues + answers.multiValues,
    )
    val data = createRiskAssessmentWithRetry(request, formCode) ?: return

    cacheRiskAssessment(localScheduleUuid, data)

    maybeCreateReferral(
      localScheduleUuid = localScheduleUuid,
      visitId = visitId,
      beneficiaryId = serverBeneficiaryId,
      submissionId = serverSubmissionId,
      referralCapture = referralCapture,
      riskFlags = data.riskFlags,
    )
  }

  /**
   * Up to [RISK_ASSESSMENT_MAX_ATTEMPTS] attempts at `POST /risk-assessments`, [RISK_ASSESSMENT_RETRY_DELAY_MILLIS]
   * apart, before giving up and returning null. See [triggerRiskAssessment]'s doc for why this is
   * a short, tight retry rather than a long backoff.
   *
   * A non-2xx response in the 4xx range is treated as non-retryable and returns null immediately
   * without spending the remaining attempts — the request itself was rejected (bad data, an
   * unknown beneficiary/visit id, validation failure), and retrying the exact same payload will
   * never produce a different result. A 5xx response or a thrown exception (offline, timeout) is
   * treated as transient and retried.
   *
   * Every attempt (success or failure) is logged; the final give-up is logged once, distinctly,
   * so "we tried and gave up" is distinguishable in logs from "we tried once and stopped" without
   * needing to count earlier log lines.
   */
  private suspend fun createRiskAssessmentWithRetry(
    request: CreateRiskAssessmentRequestDto,
    formCode: String,
  ): RiskAssessmentResponseData? {
    repeat(RISK_ASSESSMENT_MAX_ATTEMPTS) { attempt ->
      val isLastAttempt = attempt == RISK_ASSESSMENT_MAX_ATTEMPTS - 1
      val outcome = runCatching { riskAssessmentApi.createRiskAssessment(request) }
      val response = outcome.getOrNull()

      if (response != null && response.isSuccessful) {
        return response.body()?.data
      }
      if (response != null && response.code() in 400..499) {
        Log.w(
          TAG,
          "triggerRiskAssessment($formCode): non-retryable HTTP ${response.code()} — " +
            "${response.errorBody()?.string()} — visit submission still succeeded",
        )
        return null
      }

      val reason = outcome.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
        ?: "HTTP ${response?.code()}"
      if (isLastAttempt) {
        Log.w(
          TAG,
          "triggerRiskAssessment($formCode): attempt ${attempt + 1}/$RISK_ASSESSMENT_MAX_ATTEMPTS " +
            "failed ($reason) — giving up silently, visit submission still succeeded",
        )
      } else {
        Log.w(
          TAG,
          "triggerRiskAssessment($formCode): attempt ${attempt + 1}/$RISK_ASSESSMENT_MAX_ATTEMPTS " +
            "failed ($reason), retrying",
        )
        delay(RISK_ASSESSMENT_RETRY_DELAY_MILLIS)
      }
    }
    return null
  }

  /**
   * Task 2 (LMP/Reopen/Referral/Audit task list): uploads the captured sonography photo and
   * submits `POST /lmp-change-requests`, once the ANC_VISIT submission above has already
   * succeeded. Deliberately does nothing when [capture] is null — same "she has to have actually
   * filled the branch" rule [maybeCreateReferral] applies to [ReferralCapture], not a decision this
   * function makes on its own.
   *
   * Best-effort and non-blocking, exactly like [maybeCreateReferral]: a failure at either step
   * (upload or the create call) must never flip an otherwise-successful visit submission to
   * Failed/retryable. Unlike referral evidence (queued offline via
   * [org.armman.sakhi.data.referral.ReferralEvidenceSyncExecutor] for later retry), a failure here
   * is NOT retried automatically — there is no established offline queue for LMP correction
   * submissions (mirrors [org.armman.sakhi.data.lmpchange.RemoteLmpChangeRepository]'s own
   * documented "rare, online-only write" rationale). A Sakhi whose LMP correction silently failed
   * to submit sees no different outcome than one who never attempted it — a known, accepted gap
   * flagged here rather than solved by inventing a queue this CR doesn't call for.
   *
   * A duplicate call for the same visit (e.g. a resumed background sync retrying a submission
   * whose LMP request already succeeded on a prior attempt) is safe: `POST /lmp-change-requests`
   * is idempotent on [LmpChangeCapture]'s own client-minted key, minted fresh per call here rather
   * than threaded through from the draft the way [localSubmissionUuid] is — see this function's own
   * "not retried automatically" note above for why that gap doesn't matter in practice today (the
   * immediate online path is the only caller that ever supplies a non-null [capture]).
   */
  private suspend fun maybeCreateLmpChangeRequest(
    beneficiaryId: String,
    capture: LmpChangeCapture?,
  ) {
    if (capture == null) return

    val outcome = try {
      val file = java.io.File(capture.sonographyImageFilePath)
      if (!file.exists()) {
        Result.failure(IllegalStateException("sonography image file missing: ${capture.sonographyImageFilePath}"))
      } else {
        lmpChangeRepository.uploadSonographyImage(file).mapCatching { assetId ->
          lmpChangeRepository.submitLmpChangeRequest(
            beneficiaryId = beneficiaryId,
            newLmpDate = capture.newLmpDate,
            sonographyImageAssetId = assetId,
            localRequestUuid = UUID.randomUUID().toString(),
          )
        }
      }
    } catch (e: CancellationException) {
      // Not a failure: the scope is going away. Rethrown so structured concurrency still holds.
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

    outcome.onFailure { error ->
      Log.w(TAG, "maybeCreateLmpChangeRequest(beneficiaryId=$beneficiaryId) failed — visit submission still succeeded", error)
    }.onSuccess {
      Log.i(TAG, "maybeCreateLmpChangeRequest(beneficiaryId=$beneficiaryId): submitted")
    }
  }

  /**
   * CR-Referral-01: creates exactly one referral for this visit, using the SERVER's own
   * authoritative `isReferralTrigger` flags from the just-completed `POST /risk-assessments` call
   * (not the on-device GoRules result — the server re-evaluates from raw answers independently,
   * same rationale [RiskAssessmentApi]'s own doc gives for why the two aren't assumed identical).
   *
   * Deliberately does nothing (no referral, no log-worthy warning) when [referralCapture] is null
   * — a risk-assessment response flagging a referral trigger does not, by itself, create a referral;
   * the Sakhi must have actually filled in the Referral tab's facility/type for this pass. That
   * product question (should the app proactively prompt her to fill it in when triggered but she
   * hasn't yet) is out of scope here — see CR-Referral-01's RTM.
   *
   * One referral per visit is enforced server-side (a DB-level unique constraint on `visitId`,
   * confirmed 2026-08-27) — [CreateReferralOutcome.AlreadyExists] is the expected, silent outcome
   * of a retried submission attempt (e.g. a resumed background sync) hitting a visit that already
   * got its referral created on a prior attempt; not logged as a warning, since it is not one.
   *
   * Best-effort and non-blocking, exactly like [triggerRiskAssessment] itself: a failure here must
   * never flip an otherwise-successful visit submission to Failed/retryable.
   */
  private suspend fun maybeCreateReferral(
    localScheduleUuid: String,
    visitId: String,
    beneficiaryId: String,
    submissionId: String?,
    referralCapture: ReferralCapture?,
    riskFlags: List<RiskAssessmentFlagDto>,
  ) {
    if (referralCapture == null || submissionId == null) return
    val triggeringConditionIds = riskFlags.filter { it.isReferralTrigger }.map { it.riskConditionId }
    if (triggeringConditionIds.isEmpty()) return

    // try/catch as well as relying on createReferral's own Result: the interface returns a Result,
    // and RemoteReferralRepository honours that by wrapping its whole body — but a THROWN exception
    // from any implementation (or from a dependency it doesn't wrap, e.g. lookupRepository in a
    // future refactor) would propagate straight out of this function and flip an already-succeeded
    // visit submission to Failed. That is exactly what this function's own doc promises can never
    // happen, so the guarantee is enforced here rather than assumed of every implementation.
    val creation = try {
      referralRepository.createReferral(
        visitId = visitId,
        beneficiaryId = beneficiaryId,
        sourceSubmissionId = submissionId,
        capture = referralCapture,
        triggeringConditionIds = triggeringConditionIds,
      )
    } catch (e: CancellationException) {
      // Not a failure: the scope is going away. Rethrown so structured concurrency still holds.
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

    creation.onFailure { error ->
      Log.w(TAG, "maybeCreateReferral(visitId=$visitId) failed — visit submission still succeeded", error)
    }.onSuccess { outcome ->
      when (outcome) {
        is CreateReferralOutcome.Created -> {
          Log.i(TAG, "maybeCreateReferral(visitId=$visitId): created referral ${outcome.referral.referralId}")
          cacheReferralLink(localScheduleUuid, beneficiaryId, outcome.referral, referralCapture.referralVisitName)
        }
        is CreateReferralOutcome.AlreadyExists -> {
          // Idempotent per backend's #197 fix: this visit already had a referral (e.g. a resumed
          // background sync retrying a submission whose referral was already created on a prior
          // attempt) — the existing one is returned untouched, not a new one, so this is the
          // one-referral-per-visit rule working correctly, not a warning-worthy condition.
          Log.i(TAG, "maybeCreateReferral(visitId=$visitId): referral already existed (${outcome.referral.referralId}), one-per-visit held")
          cacheReferralLink(localScheduleUuid, beneficiaryId, outcome.referral, referralCapture.referralVisitName)
        }
      }
    }
  }

  /**
   * Mirrors the just-accepted [Referral] into [referralLinkDao], keyed by [localScheduleUuid] —
   * see [ReferralLinkEntity]'s own doc for why that key, not the server [Referral.visitId]. Best
   * effort like everything else in this chain: a local-cache write failure must not flip an
   * otherwise-successful visit submission to Failed/retryable, so it is swallowed the same way
   * [maybeCreateReferral]'s own network call is.
   *
   * [beneficiaryId] is stamped onto the cached row (CR-Referral-01, in-visit autopopulation fix)
   * purely so [ReferralLinkDao.countByBeneficiaryId] can later count this beneficiary's referrals
   * for the in-visit capture step's own "RV{n+1}" auto-numbering — not used for anything else here.
   *
   * [referralVisitName] comes from the just-submitted [ReferralCapture], NOT [referral] itself —
   * `POST /referrals`'s response has no such field (it's a request-only, backend-unaware concept,
   * same as [ReferralCapture.referralVisitName]'s doc explains) — see [ReferralLinkEntity
   * .referralVisitName]'s own doc for who reads it back.
   */
  private suspend fun cacheReferralLink(
    localScheduleUuid: String,
    beneficiaryId: String,
    referral: Referral,
    referralVisitName: String?,
  ) {
    runCatching {
      referralLinkDao.upsert(
        ReferralLinkEntity(
          localScheduleUuid = localScheduleUuid,
          referralId = referral.referralId,
          visitId = referral.visitId ?: return@runCatching,
          status = referral.status.name,
          referralTypeLookupValueId = referral.referralTypeLookupValueId,
          validTill = referral.validTill,
          createdAtEpochMillis = System.currentTimeMillis(),
          facilityName = referral.facilityName,
          facilityType = referral.facilityType.name,
          referralVisitName = referralVisitName.orEmpty(),
          beneficiaryId = beneficiaryId,
        ),
      )
    }.onFailure { error ->
      Log.w(TAG, "cacheReferralLink(localScheduleUuid=$localScheduleUuid) failed — visit submission still succeeded", error)
    }
  }

  /**
   * Mirrors a just-accepted `POST /risk-assessments` response into [riskAssessmentDao] — punch-
   * list items 1/2 (2026-08-27): local persistence of `risk_assessments`/`risk_flags`, so a visit
   * card / risk badge can read the last-known grading offline instead of needing a network call.
   *
   * Best effort like everything else in this chain (mirrors [cacheReferralLink] exactly): a local
   * cache write failure must never flip an otherwise-successful visit submission to
   * Failed/retryable — [triggerRiskAssessment] has already returned useful data to the Sakhi
   * (the risk-assessment call itself succeeded) by the time this runs.
   */
  private suspend fun cacheRiskAssessment(localScheduleUuid: String, data: RiskAssessmentResponseData) {
    runCatching {
      val flags = data.riskFlags.map { flag ->
        RiskFlagEntity(
          localScheduleUuid = localScheduleUuid,
          serverFlagId = flag.id,
          riskConditionId = flag.riskConditionId,
          riskGradeLookupValueId = flag.riskGradeLookupValueId,
          observedValueJson = flag.observedValueJson?.let { riskAssessmentJsonMapper.toJson(it) },
          isReferralTrigger = flag.isReferralTrigger,
          isEducationTrigger = flag.isEducationTrigger,
          isHrVisitTrigger = flag.isHrVisitTrigger,
        )
      }
      riskAssessmentDao.upsertAssessmentWithFlags(
        assessment = RiskAssessmentEntity(
          localScheduleUuid = localScheduleUuid,
          serverAssessmentId = data.id,
          beneficiaryId = data.beneficiaryId,
          visitId = data.visitId,
          submissionId = data.submissionId,
          ruleVersionId = data.ruleVersionId,
          evaluatedAt = data.evaluatedAt,
          overallRiskCategory = data.overallRiskCategory,
          overallHighRiskFlag = data.overallHighRiskFlag,
          hrDetectedFlag = data.hrDetectedFlag,
          createdAtEpochMillis = System.currentTimeMillis(),
        ),
        flags = flags,
      )
    }.onFailure { error ->
      Log.w(TAG, "cacheRiskAssessment(localScheduleUuid=$localScheduleUuid) failed — visit submission still succeeded", error)
    }
  }

  /** See [triggerRiskAssessment]'s doc for why `INFANT_VISIT` returns null here rather than a
   * guessed value. */
  private fun riskPhaseFor(formCode: String): String? = when (formCode) {
    "ANC_VISIT" -> "ANC"
    "NEONATAL_VISIT" -> "NN"
    // INFANT_VISIT IS INC_VISIT under its pre-rename name (confirmed by backend 2026-08-24) —
    // same phase, same condition set, not a guess. See this function's doc.
    "INC_VISIT", "INFANT_VISIT" -> "INC"
    "CCV_VISIT" -> "CCV"
    else -> null
  }

  /** Rule-SET id (not a published-version id) for whichever risk pack grades [formCode] — mirrors
   * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.evaluateGoRulesRisk]'s own
   * formCode -> pack dispatch, kept as its own small function here since that ViewModel function
   * isn't reachable from this coordinator. */
  private fun riskRuleSetIdFor(formCode: String): String? = when (formCode) {
    "ANC_VISIT" -> RuleSetIds.RISK_ANC
    "NEONATAL_VISIT", "INC_VISIT", "INFANT_VISIT", "CCV_VISIT" -> RuleSetIds.RISK_INFANT
    else -> null
  }

  /**
   * Advances this beneficiary's active [DeliverySessionEntity], if any, when the just-submitted
   * [schedule] is exactly the visit its current step is waiting on. A no-op in every other case:
   * no active session, or an active session sitting at a step this visit doesn't represent (e.g.
   * an ANC visit while a session sits at [DeliverySessionStep.PP1], or a PP2 visit — PP1 is the
   * only PP visit this session cares about).
   *
   * - [DeliverySessionStep.PP1] + this visit is PP1 (`visitType == PP`, `sequenceNo == 1`):
   *   advances to [DeliverySessionStep.NN] if [SameSessionNnVisitResolver] finds one among the
   *   registered children's still-open NN rows (measured against the session's own
   *   [DeliverySessionEntity.deliveryFormFilledOn]), else straight to [DeliverySessionStep.DONE].
   *   CR-Delivery-01: this used to look up NN rows under this beneficiary's own id, which is the
   *   MOTHER's id — NN is generated under the CHILD's, so it never found anything and this step
   *   always fell through to DONE. See [SameSessionNnVisitResolver]'s own doc for the full story.
   * - [DeliverySessionStep.NN] + this visit is NN (`visitType == NN` — NN1 or NN2, "either NN
   *   visit" per CR-042): advances to [DeliverySessionStep.DONE]. Deliberately does not
   *   re-verify which NN row this is — by the time a session reaches [DeliverySessionStep.NN],
   *   at most one NN row can still be open as *this* session's own visit (see
   *   [SameSessionNnVisitResolver]'s doc), so any NN submission that lands while the session is
   *   still at this step is that one.
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

  private suspend fun hasSameSessionNnVisit(session: DeliverySessionEntity): Boolean =
    sameSessionNnVisitResolver.hasMatch(session)
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
