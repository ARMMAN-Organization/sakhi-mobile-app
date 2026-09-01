package org.armman.sakhi.data.adhocform

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.closure.ClosureRepository
import org.armman.sakhi.data.closure.ClosureSubmissionException
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormDateRuleset
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.referral.ReferralEvidenceDao
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.referral.ReferralEvidenceMediaEntity
import org.armman.sakhi.data.referral.ReferralEvidenceSyncScheduler
import org.armman.sakhi.data.referral.ReferralEvidenceType
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that can stop [AdHocFormSubmissionCoordinator.submit] from completing — mirrors
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionException]'s shape.
 */
sealed class AdHocFormSubmissionException(message: String) : Exception(message) {
  open val userMessage: String get() = SubmitErrorCopy.GENERIC

  data object NoActiveSession : AdHocFormSubmissionException("No signed-in Sakhi session")

  /**
   * These forms are opened directly from a beneficiary's profile — there is no schedule row to
   * resolve a server beneficiary id from (unlike [org.armman.sakhi.data.visitform
   * .VisitFormSubmissionCoordinator]'s `POST /visits` path). Instead this reuses whatever server
   * beneficiary id any of the beneficiary's own [VisitScheduleRepository] rows already carries
   * (attached once the beneficiary itself syncs — see
   * [VisitScheduleRepository.attachServerBeneficiaryId]). If the beneficiary hasn't synced yet,
   * none of her schedule rows carry one, and submission can't proceed — same retryable-not-fatal
   * treatment as the visit-form flow's own `NotYetSynced`.
   */
  data object NotYetSynced : AdHocFormSubmissionException(
    "Cannot submit: no server beneficiary id known yet for this beneficiary",
  ) {
    override val userMessage: String
      get() = "This beneficiary's data hasn't finished syncing yet. Connect to the internet, use Data Upload, then try again."
  }

  data class FormSubmissionFailed(
    val httpCode: Int,
    val body: String?,
    val apiMessage: String? = null,
    val violations: List<String> = emptyList(),
    val formCode: String = "",
  ) : AdHocFormSubmissionException("POST /forms/$formCode/submissions failed: HTTP $httpCode — $body") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }

  data object NoSubmissionIdReturned :
    AdHocFormSubmissionException("Form submitted but no id was returned in the response")

  /** [org.armman.sakhi.data.closure.ClosureRepository.submitClosure] failed for an
   * ANC_CLOSURE_VISIT/CHILD_CLOSURE_VISIT submission — the generic form-answer submission above
   * still went through, but the beneficiary-status-changing `POST /closures` call did not. Kept as
   * its own case (not [FormSubmissionFailed]) so the message doesn't wrongly say "POST
   * /forms/.../submissions failed" for a call that actually succeeded. */
  data class ClosureSubmissionFailed(val httpCode: Int, val apiMessage: String?, val violations: List<String>) :
    AdHocFormSubmissionException("POST /closures failed: HTTP $httpCode — $apiMessage") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }

  /** [ReferralRepository.submitFollowUp] failed for a `REFERRAL_FOLLOWUP_VISIT` submission — the
   * generic form-answer submission above still went through, but the referral-status-transition
   * `POST /referrals/{referralId}/follow-up` call did not. Kept as its own case for the same
   * reason [ClosureSubmissionFailed] is. */
  data class ReferralFollowUpSubmissionFailed(val apiMessage: String) :
    AdHocFormSubmissionException("POST /referrals/{referralId}/follow-up failed: $apiMessage") {
    override val userMessage: String get() = apiMessage
  }

  /** [submit] was called for `REFERRAL_FOLLOWUP_VISIT` with a null [referralId] — the caller
   * (ultimately [org.armman.sakhi.ui.adhocform.AdHocFormViewModel]'s nav arg, persisted on
   * [org.armman.sakhi.data.adhocform.AdHocFormDraftEntity.referralId]) must always supply one for
   * this form code; every entry point does today (the profile screen only ever opens this form
   * from a referral-incomplete visit, which always carries a referralId). Defensive only. */
  data object ReferralIdMissing : AdHocFormSubmissionException(
    "REFERRAL_FOLLOWUP_VISIT submitted with no referralId",
  )

  /** The loaded `closure_reason` answer is a `value_code` string (e.g. `"MIGRATION"`) that could
   * not be resolved to a `CLOSURE_REASON` lookup value id — either the field was left unanswered
   * (should never happen given [isReadyToSubmit]'s required-field gate) or the `CLOSURE_REASON`
   * category hasn't loaded/been seeded. Retryable: a later attempt with the lookup category warm
   * may succeed. */
  data object ClosureReasonNotResolvable : AdHocFormSubmissionException(
    "Could not resolve the closure_reason answer to a CLOSURE_REASON lookup value id",
  )

  /** [mapClosureReasonToBackendCode] was given a `closure_reason` form `value_code` (snake_case,
   * straight off the form schema, e.g. `"maternal_death"`) that isn't in the known
   * form-value_code -> backend-SCREAMING_CASE-code table for the given [formCode]. Distinct from
   * [ClosureReasonNotResolvable] (which is about the *backend* lookup category missing an entry
   * for an already-translated code) — this one fires when the *translation table itself* doesn't
   * recognise the form's own raw value, e.g. because the backend seeds added a new closure_reason
   * option to one of the two closure forms that this table hasn't been updated for yet. */
  data class ClosureReasonValueCodeUnrecognised(val formCode: String, val rawValueCode: String) :
    AdHocFormSubmissionException(
      "Unrecognised closure_reason value_code \"$rawValueCode\" for form $formCode — " +
        "mapClosureReasonToBackendCode() has no backend-code mapping for it",
    )
}

/**
 * Orchestrates the single-call ad-hoc-form submit: `POST /forms/{formCode}/submissions` for
 * whichever of the five ad-hoc form codes (`REFERRAL_VISIT`, `REFERRAL_FOLLOWUP_VISIT`,
 * `ANC_CLOSURE_VISIT`, `CHILD_CLOSURE_VISIT`, `BENEFICIARY_REOPEN_VISIT`) the caller passes —
 * unlike [org.armman.sakhi.data.forms.DynamicFormSubmissionCoordinator] and
 * [org.armman.sakhi.data.childregistration.ChildRegistrationSubmissionCoordinator], [formCode] is
 * a real per-call parameter here, not hardcoded, since one coordinator serves all five forms.
 *
 * No `POST /visits`-equivalent first call: these forms aren't tied to a schedule, so there's
 * nothing to create before submitting — just the one call, using whatever server beneficiary id
 * the beneficiary's own schedule rows already carry (see [AdHocFormSubmissionException
 * .NotYetSynced]'s doc).
 */
@Singleton
class AdHocFormSubmissionCoordinator @Inject constructor(
  private val formSubmissionApi: FormSubmissionApi,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val sessionStore: SessionStore,
  private val formAuditRepository: FormAuditRepository,
  private val closureRepository: ClosureRepository,
  private val lookupRepository: LookupRepository,
  private val statusOverrideStore: LocalBeneficiaryStatusOverrideStore,
  private val referralRepository: ReferralRepository,
  private val referralEvidenceDao: ReferralEvidenceDao,
  private val referralEvidenceSyncScheduler: ReferralEvidenceSyncScheduler,
  private val referralLinkDao: ReferralLinkDao,
) {

  /** Returns the server-assigned submission id on success. [formAuditRepository.recordSubmitted]
   * is called only on success, keyed by [localFormInstanceUuid] — the SAME id
   * [org.armman.sakhi.ui.adhocform.AdHocFormViewModel] used for `recordOpened`/`recordSaved`, so
   * the audit trail for one draft is traceable across its whole lifecycle. */
  suspend fun submit(
    localFormInstanceUuid: String,
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    referralId: String? = null,
    /** REFERRAL_FOLLOWUP_VISIT only — question_code -> absolute on-disk file path for every
     * captured `image` field, as recorded by [org.armman.sakhi.ui.adhocform.AdHocFormScreen] at
     * capture time (this coordinator has no UI `Context` to resolve a `content://` URI itself —
     * see [queueReferralFollowUpEvidence]'s doc). Empty for every other ad-hoc form. */
    capturedImagePaths: Map<String, String> = emptyMap(),
  ): Result<String> = runCatching {
    sessionStore.readSession() ?: throw AdHocFormSubmissionException.NoActiveSession

    // No schedule of our own to resolve a server beneficiary id from (see NotYetSynced's doc) —
    // reuse whatever the beneficiary's own visit-schedule rows already carry, the same id
    // VisitFormSubmissionCoordinator's step 1 resolves for the exact same beneficiary.
    val serverBeneficiaryId = visitScheduleRepository.getForBeneficiary(localBeneficiaryId)
      .firstNotNullOfOrNull { it.serverBeneficiaryId }
      ?: throw AdHocFormSubmissionException.NotYetSynced

    val submissionRequest = CreateSubmissionRequestDto(
      formVersionId = formVersionId,
      beneficiaryId = serverBeneficiaryId,
      visitId = null,
      localSubmissionUuid = localFormInstanceUuid,
      formData = answers.singleValues + answers.multiValues,
    )
    val submissionResponse = formSubmissionApi.createSubmission(formCode, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val rawBody = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw AdHocFormSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = rawBody,
        apiMessage = apiError.message?.takeIf { it != rawBody },
        violations = apiError.violations,
        formCode = formCode,
      )
    }
    val submissionData = submissionResponse.body()?.data
      ?: throw AdHocFormSubmissionException.NoSubmissionIdReturned
    // submissionData.submittedByUserId: server-derived attribution — no current consumer reads it
    // (see SubmissionResponseData's own doc); a future audit/debug view would read it from here.

    formAuditRepository.recordSubmitted(localFormInstanceUuid, formCode)

    // The backend team stood up a SEPARATE beneficiary-status-changing call for the two closure
    // form codes, alongside (not instead of) the generic form-answer submission above — see
    // ClosureRepository's own doc. Reuses localFormInstanceUuid as the closure's own
    // localClosureUuid idempotency key rather than minting/persisting a second uuid on the draft
    // row — a judgment call (flagged in the implementation report) made to avoid a
    // AdHocFormDraftEntity/Room schema change for this pass; both ids are freshly minted once per
    // form opening either way, so reusing one for both purposes doesn't weaken either's
    // idempotency guarantee.
    if (formCode == FORM_CODE_ANC_CLOSURE || formCode == FORM_CODE_CHILD_CLOSURE) {
      submitClosure(
        formCode = formCode,
        localClosureUuid = localFormInstanceUuid,
        serverBeneficiaryId = serverBeneficiaryId,
        localBeneficiaryId = localBeneficiaryId,
        answers = answers,
      )
    }

    // CR-Referral-01/02: Referral Follow-up switched from a bespoke screen driving
    // POST /referrals/{referralId}/follow-up directly to this schema-driven ad-hoc form — the
    // status-transition call now runs as a paired side effect here, same shape as submitClosure()
    // above. Evidence photos (case_paper_photo/further_investigation_photo, native `image`
    // fields) are queued for upload separately, keyed by this submission's own id.
    if (formCode == FORM_CODE_REFERRAL_FOLLOWUP) {
      submitReferralFollowUp(referralId ?: throw AdHocFormSubmissionException.ReferralIdMissing, answers)
      queueReferralFollowUpEvidence(referralId, submissionData.id, answers, capturedImagePaths)
    }

    submissionData.id
  }

  /**
   * Maps the dynamic Referral Follow-up form's own answers to `POST /referrals/{referralId}/follow-up`'s
   * body and submits it — the call that actually moves [org.armman.sakhi.data.referral.ReferralStatus]
   * (COMPLETED when the beneficiary visited, unchanged/PENDING_FOLLOWUP otherwise; see
   * [org.armman.sakhi.data.referral.ReferralRepository.submitFollowUp]'s doc). Runs after the
   * generic form-answer submission already succeeded (see [submit]).
   *
   * The DTO's free-text [org.armman.sakhi.data.referral.SubmitReferralFollowUpRequestDto.diagnosis]/
   * `treatmentGiven`/`outcome` fields don't map 1:1 onto this schema's categorical
   * `diagnosis_confirmed`/`treatment_given`/`treatment_type`/`clinical_status_now`/
   * `referral_final_outcome` questions — by product decision, this concatenates the selected
   * option labels into readable free text (rather than leaving them null) so the referral record
   * itself carries a human-readable summary, not just the generic submission's raw `formData`.
   */
  private suspend fun submitReferralFollowUp(referralId: String, answers: FormAnswers) {
    val visited = answers.valueOf(QUESTION_CODE_VISITED_HEALTH_FACILITY) == VALUE_YES
    val followupDate = answers.valueOf(FormDateRuleset.FOLLOWUP_FORM_FILLED_DATE_QUESTION_CODE)
      ?: LocalDate.now().toString()

    val notVisitedReason = answers.valueOf(QUESTION_CODE_NOT_VISITED_REASON)
      ?.let { NOT_VISITED_REASON_LABELS[it] ?: it }

    val diagnosis = if (visited) {
      val confirmed = answers.valueOf(QUESTION_CODE_DIAGNOSIS_CONFIRMED)?.let { yesNoLabel(it) }
      val clinicalStatus = answers.valueOf(QUESTION_CODE_CLINICAL_STATUS_NOW)
        ?.let { CLINICAL_STATUS_LABELS[it] ?: it }
      listOfNotNull(
        confirmed?.let { "Diagnosis confirmed: $it" },
        clinicalStatus?.let { "Clinical status: $it" },
      ).joinToString("; ").ifBlank { null }
    } else {
      null
    }

    val treatmentGiven = if (visited) {
      val given = answers.valueOf(QUESTION_CODE_TREATMENT_GIVEN)?.let { yesNoLabel(it) }
      val types = answers.multiValueOf(QUESTION_CODE_TREATMENT_TYPE)
        .map { TREATMENT_TYPE_LABELS[it] ?: it }
      listOfNotNull(
        given?.let { "Treatment given: $it" },
        types.takeIf { it.isNotEmpty() }?.let { "Type: " + it.joinToString(", ") },
      ).joinToString("; ").ifBlank { null }
    } else {
      null
    }

    val outcome = answers.valueOf(QUESTION_CODE_REFERRAL_FINAL_OUTCOME)
      ?.let { REFERRAL_OUTCOME_LABELS[it] ?: it }

    val result = referralRepository.submitFollowUp(
      referralId = referralId,
      visitedFacilityFlag = visited,
      followupDate = LocalDate.parse(followupDate),
      notVisitedReason = notVisitedReason,
      diagnosis = diagnosis,
      treatmentGiven = treatmentGiven,
      outcome = outcome,
    )
    result.onFailure { error ->
      throw AdHocFormSubmissionException.ReferralFollowUpSubmissionFailed(
        error.message ?: "unknown error",
      )
    }
    result.onSuccess { followUpResult ->
      // Mirror the referral's new status into the local ReferralLinkEntity cache immediately —
      // same fix the retired bespoke screen's submit() applied, now keyed by referralId (the
      // ad-hoc form route has no localScheduleUuid to look this row up by — see
      // ReferralLinkDao.getByReferralId's doc). Without this, BeneficiaryProfileScreen keeps
      // showing "Referral Followup Incomplete" until the next full server reconcile, even though
      // the backend already moved the referral to COMPLETED.
      referralLinkDao.getByReferralId(referralId)?.let { existing ->
        referralLinkDao.upsert(existing.copy(status = followUpResult.referral.status.name))
      }
    }
  }

  private fun yesNoLabel(valueCode: String): String = if (valueCode == VALUE_YES) "Yes" else "No"

  /**
   * Queues [QUESTION_CODE_CASE_PAPER_PHOTO]/[QUESTION_CODE_FURTHER_INVESTIGATION_PHOTO] (if
   * captured) for the existing offline-safe evidence-upload queue
   * ([org.armman.sakhi.data.referral.ReferralEvidenceSyncExecutor]), keyed by [submissionId]
   * rather than a `followupId` — unlike the retired bespoke screen's capture-then-stamp two-phase
   * flow, [submissionId] is already known here, so every row is inserted already eligible for
   * upload (see [ReferralEvidenceMediaEntity.submissionId]'s doc). Best-effort: a queuing failure
   * here must never fail the follow-up submission itself, which has already succeeded — mirrors
   * [org.armman.sakhi.data.referral.ReferralEvidenceSyncExecutor]'s own "never a hard user-facing
   * error" rule for the upload step itself.
   *
   * [capturedImagePaths] carries the real on-disk file path per question code (see [submit]'s own
   * doc) — this coordinator never resolves a `content://` URI itself, keeping it Context-free and
   * unit-testable, same as every other coordinator/executor in this app.
   */
  private suspend fun queueReferralFollowUpEvidence(
    referralId: String,
    submissionId: String,
    answers: FormAnswers,
    capturedImagePaths: Map<String, String>,
  ) {
    for ((questionCode, evidenceType) in EVIDENCE_QUESTION_CODES) {
      if (answers.valueOf(questionCode).isNullOrBlank()) continue
      val filePath = capturedImagePaths[questionCode] ?: continue
      val file = File(filePath)
      if (!file.exists()) continue
      runCatching {
        referralEvidenceDao.upsert(
          ReferralEvidenceMediaEntity(
            localMediaUuid = UUID.randomUUID().toString(),
            referralId = referralId,
            evidenceType = evidenceType.name,
            localFilePath = file.absolutePath,
            syncStatus = EnrollmentSyncStatus.PENDING,
            createdAtEpochMillis = Instant.now().toEpochMilli(),
            lastAttemptAtEpochMillis = null,
            retryCount = 0,
            followupId = null,
            submissionId = submissionId,
            remoteMediaId = null,
            lastErrorMessage = null,
          ),
        )
      }
    }
    referralEvidenceSyncScheduler.syncNow()
  }

  /**
   * Maps the closure form's own answers to `POST /closures`'s body and submits it. Runs after the
   * generic form-answer submission already succeeded (see [submit]); on success, optimistically
   * marks the beneficiary CLOSED locally so the Closed tab reflects it immediately, even before
   * the next `GET /beneficiaries` pull confirms it server-side (mirrors
   * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository]'s own reconcile-on-pull shape).
   */
  private suspend fun submitClosure(
    formCode: String,
    localClosureUuid: String,
    serverBeneficiaryId: String,
    localBeneficiaryId: String,
    answers: FormAnswers,
  ) {
    val closureDate = answers.valueOf(FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE) ?: LocalDate.now().toString()
    val eventDate = answers.valueOf(FormDateRuleset.DATE_OF_EVENT_QUESTION_CODE)
    val rawReasonValueCode = answers.valueOf(QUESTION_CODE_CLOSURE_REASON)
      ?: throw AdHocFormSubmissionException.ClosureReasonNotResolvable
    // rawReasonValueCode is the form's own snake_case value_code (e.g. "maternal_death") — the
    // CLOSURE_REASON lookup category on the backend uses a different SCREAMING_CASE vocabulary
    // (e.g. "MATERNAL_DEATH"), so it must be translated before it's used for anything backend-facing,
    // both the lookup below AND toClosureType()'s own SCREAMING_CASE-keyed when().
    val backendReasonCode = mapClosureReasonToBackendCode(formCode, rawReasonValueCode)
    val reasonLookupValue = lookupRepository.findValue(LOOKUP_CATEGORY_CLOSURE_REASON, backendReasonCode)
      ?: throw AdHocFormSubmissionException.ClosureReasonNotResolvable

    val submittedByUserId = sessionStore.readSession()?.subjectId
      ?: throw AdHocFormSubmissionException.NoActiveSession

    try {
      // 2026-08-31: supervisorStatus/supervisorId/supervisorNotes are NOT sent -- backend
      // confirmed these are deliberately excluded from create-closure.dto.ts (a client-settable
      // supervisorStatus would let a SAKHI bypass supervisor review) and are always server-derived
      // now. Backend also described a new Migration-only PENDING-until-approved gate that isn't
      // in the SRS form spec (both Closure forms say every reason, Migration included, closes
      // IMMEDIATELY on submit -- only Reopen's form spec has a supervisor-approval step). That
      // conflict is flagged back to backend/product, unresolved -- this client still marks CLOSED
      // + lapses visits immediately for every reason below, matching the SRS as documented, not
      // backend's new server-side behavior. See ClosureRequestDto's doc for the full history.
      closureRepository.submitClosure(
        localClosureUuid = localClosureUuid,
        beneficiaryId = serverBeneficiaryId,
        closureType = backendReasonCode.toClosureType(),
        closureReasonLookupValueId = reasonLookupValue.id,
        eventDate = eventDate,
        closureDate = closureDate,
        submittedByUserId = submittedByUserId,
      )
    } catch (e: ClosureSubmissionException.Failed) {
      throw AdHocFormSubmissionException.ClosureSubmissionFailed(
        httpCode = e.httpCode,
        apiMessage = e.apiMessage,
        violations = e.violations,
      )
    } catch (e: ClosureSubmissionException.NoIdReturned) {
      throw AdHocFormSubmissionException.ClosureSubmissionFailed(httpCode = 0, apiMessage = e.message, violations = emptyList())
    }

    statusOverrideStore.setStatus(localBeneficiaryId, BeneficiaryStatus.CLOSED)
    // CR-Closure-02: recorded so the Reopen eligibility gate (BeneficiaryProfileViewModel) can
    // tell a death/miscarriage/abortion closure apart from a migration/mistake one -- see
    // LocalBeneficiaryStatusOverrideStore.setClosureReason's doc for the "only this device knows"
    // caveat.
    statusOverrideStore.setClosureReason(localBeneficiaryId, backendReasonCode)

    // CR-Closure-01 items #3/#7: every remaining open visit stops being actionable once this
    // beneficiary is closed -- fires unconditionally, including for a MIGRATION-reason closure
    // still awaiting Supervisor review (supervisorStatus PENDING above), same "write optimistically"
    // treatment the CLOSED status override itself already gets a few lines up. Best-effort: a
    // lapse-sweep failure must not undo an already-successful closure submission -- the visits
    // would just stay open a little longer than intended, recoverable on a later profile load
    // rather than something worth failing this whole submit() call over.
    runCatching { visitScheduleRepository.lapseAllOpenVisits(localBeneficiaryId) }
  }

  /**
   * Translates the closure form's own `closure_reason` `value_code` (snake_case, straight off the
   * form schema — e.g. `"maternal_death"`) to the `CLOSURE_REASON` lookup category's backend
   * SCREAMING_CASE `valueCode` vocabulary (e.g. `"MATERNAL_DEATH"`). The two closure forms
   * (`ANC_CLOSURE_VISIT` / `CHILD_CLOSURE_VISIT`) have overlapping-but-not-identical vocabularies
   * for this question code, so [formCode] selects which table applies. Confirmed against both
   * forms' live schemas, 2026-08-18 — see this class's own top-level doc.
   */
  private fun mapClosureReasonToBackendCode(formCode: String, valueCode: String): String {
    val backendCode = when (formCode) {
      FORM_CODE_ANC_CLOSURE -> ANC_CLOSURE_REASON_TO_BACKEND_CODE[valueCode]
      FORM_CODE_CHILD_CLOSURE -> CHILD_CLOSURE_REASON_TO_BACKEND_CODE[valueCode]
      else -> null
    }
    return backendCode ?: throw AdHocFormSubmissionException.ClosureReasonValueCodeUnrecognised(
      formCode = formCode,
      rawValueCode = valueCode,
    )
  }

  /**
   * `closure_reason`'s `value_code` (from the `CLOSURE_REASON` lookup category) to `POST
   * /closures`'s `closureType` — no existing mapping table in the codebase or design docs for
   * this, so this is a judgment call added here and flagged in the implementation report for
   * product sanity-check, not something confirmed against the SRS/PRD:
   *  - MISCARRIAGE / ABORTION / MATERNAL_DEATH / INFANT_OR_CHILD_DEATH -> MEDICAL
   *  - MIGRATION / WITHDRAWAL -> NON_MEDICAL
   *  - PROGRAM_CYCLE_COMPLETED -> PROGRAM_COMPLETION
   *  - OTHER (and anything unrecognised) -> NON_MEDICAL, the least-specific bucket
   */
  private fun String.toClosureType(): String = when (this) {
    "MISCARRIAGE", "ABORTION", "MATERNAL_DEATH", "INFANT_OR_CHILD_DEATH" -> CLOSURE_TYPE_MEDICAL
    "MIGRATION", "WITHDRAWAL" -> CLOSURE_TYPE_NON_MEDICAL
    "PROGRAM_CYCLE_COMPLETED" -> CLOSURE_TYPE_PROGRAM_COMPLETION
    else -> CLOSURE_TYPE_NON_MEDICAL
  }

  private companion object {
    const val FORM_CODE_ANC_CLOSURE = "ANC_CLOSURE_VISIT"
    const val FORM_CODE_CHILD_CLOSURE = "CHILD_CLOSURE_VISIT"
    const val FORM_CODE_REFERRAL_FOLLOWUP = "REFERRAL_FOLLOWUP_VISIT"

    // Referral Follow-up (REFERRAL_FOLLOWUP_VISIT) question codes — confirmed against a live
    // GET /forms/REFERRAL_FOLLOWUP_VISIT/active-version payload, 2026-08-31 (formDefinitionId
    // 9da3e972-62f1-4c12-bca5-1c39b8b72ad3). Kept file-local rather than shared constants, same
    // convention QUESTION_CODE_CLOSURE_REASON above already follows.
    const val QUESTION_CODE_VISITED_HEALTH_FACILITY = "visited_health_facility"
    const val QUESTION_CODE_NOT_VISITED_REASON = "not_visited_reason"
    const val QUESTION_CODE_DIAGNOSIS_CONFIRMED = "diagnosis_confirmed"
    const val QUESTION_CODE_TREATMENT_GIVEN = "treatment_given"
    const val QUESTION_CODE_TREATMENT_TYPE = "treatment_type"
    const val QUESTION_CODE_CLINICAL_STATUS_NOW = "clinical_status_now"
    const val QUESTION_CODE_REFERRAL_FINAL_OUTCOME = "referral_final_outcome"
    const val QUESTION_CODE_CASE_PAPER_PHOTO = "case_paper_photo"
    const val QUESTION_CODE_FURTHER_INVESTIGATION_PHOTO = "further_investigation_photo"
    const val VALUE_YES = "yes"

    // question_code -> ReferralEvidenceType this evidence file is finalized as. case_paper_photo
    // bundles case paper/discharge summary/health facility/Sakhi photo into ONE schema field
    // (confirmed 2026-08-31 backend scope decision — see this app's CR-Referral-02 docs), so it
    // maps to the closest single existing assetType rather than the 4-way split the retired
    // bespoke screen offered; further_investigation_photo maps 1:1 to its own type.
    val EVIDENCE_QUESTION_CODES: Map<String, org.armman.sakhi.data.referral.ReferralEvidenceType> = mapOf(
      QUESTION_CODE_CASE_PAPER_PHOTO to org.armman.sakhi.data.referral.ReferralEvidenceType.REFERRAL_CASE_PAPER,
      QUESTION_CODE_FURTHER_INVESTIGATION_PHOTO to org.armman.sakhi.data.referral.ReferralEvidenceType.REFERRAL_INVESTIGATION_REPORT,
    )

    // value_code -> readable label, transcribed from the schema's own `options[].label` — see
    // submitReferralFollowUp's doc for why these are concatenated into the follow-up DTO's
    // free-text fields rather than left null.
    val NOT_VISITED_REASON_LABELS: Map<String, String> = mapOf(
      "condition_not_serious_enough" to "Belief that the condition is not serious enough to require referral",
      "family_opposition" to "Family opposition to visit health facility",
      "cost_of_transportation" to "Cost of transportation to the facility",
      "fear_of_procedures_and_cost" to "Fear of unnecessary procedures and cost of treatment",
      "preference_for_home_remedies" to "Preference for home remedies or traditional healers",
      "cultural_norms_restricting_mobility" to "Cultural norms restricting women's mobility",
      "woman_migrating" to "Woman migrating",
      "other" to "Other",
    )
    val REFERRAL_OUTCOME_LABELS: Map<String, String> = mapOf(
      "ipd_still_hospitalized" to "IPD: Still Hospitalized for management",
      "ipd_delivered" to "IPD: Delivered",
      "ipd_discharged_with_management" to "IPD: Discharged with management",
      "opd_given_medications" to "OPD and given medications",
      "further_referral_advised" to "Further referral advised",
      "addressed_no_high_risk_sent_back" to "Addressed as no high risk and sent back",
      "health_center_closed" to "Health center closed",
      "no_staff_available" to "No staff available at the health centre",
    )
    val TREATMENT_TYPE_LABELS: Map<String, String> = mapOf(
      "injection" to "Injection",
      "tablet" to "Tablet",
      "iron_sucrose_injection" to "Iron sucrose injection",
      "saline_injection" to "Saline injection",
      "syrup" to "Syrup",
      "blood_transfusion" to "Blood transfusion",
      "supplementary_feeding_mother" to "Supplementary feeding to the Mother (shatavari/protein/multivitamin etc.)",
      "supplementary_feeding_child" to "Supplementary feeding to the Child (formula milk, etc.)",
      "further_investigations_advised" to "Further investigations advised",
      "other" to "Other",
    )
    val CLINICAL_STATUS_LABELS: Map<String, String> = mapOf(
      "resolved" to "Resolved",
      "improving" to "Improving",
      "same" to "Same",
      "worsened" to "Worsened",
    )

    // closure_visit_date/date_of_event reuse FormDateRuleset's own constants rather than
    // duplicating the literals here. closure_reason has no existing shared constant (nothing else
    // in the codebase reads it yet) — confirmed as a real question code on both closure forms
    // against a real GET /forms/.../active-version payload, 2026-08-18 (see FormModels.kt's
    // DROPDOWN_SPELLINGS doc).
    const val QUESTION_CODE_CLOSURE_REASON = "closure_reason"

    const val LOOKUP_CATEGORY_CLOSURE_REASON = "CLOSURE_REASON"

    // closure_reason value_code (snake_case, off the form schema) -> CLOSURE_REASON lookup
    // category valueCode (SCREAMING_CASE, off the backend) — confirmed against both closure
    // forms' live GET /forms/.../active-version schemas, 2026-08-18. The two forms' vocabularies
    // overlap but are NOT identical (e.g. only ANC_CLOSURE_VISIT has miscarriage/abortion/
    // maternal_death; only CHILD_CLOSURE_VISIT has infant_child_death) — kept as two separate
    // tables rather than one merged map so an unrecognised value_code for the WRONG form still
    // fails loudly instead of silently borrowing the other form's mapping.
    val ANC_CLOSURE_REASON_TO_BACKEND_CODE: Map<String, String> = mapOf(
      "withdrawal_of_consent" to "WITHDRAWAL",
      "miscarriage" to "MISCARRIAGE",
      "abortion_spontaneous_induced_mtp" to "ABORTION",
      "migration" to "MIGRATION",
      "program_cycle_completed" to "PROGRAM_CYCLE_COMPLETED",
      "maternal_death" to "MATERNAL_DEATH",
    )
    val CHILD_CLOSURE_REASON_TO_BACKEND_CODE: Map<String, String> = mapOf(
      "withdrawal_of_consent" to "WITHDRAWAL",
      "migration" to "MIGRATION",
      "program_cycle_completed" to "PROGRAM_CYCLE_COMPLETED",
      "infant_child_death" to "INFANT_OR_CHILD_DEATH",
    )

    const val CLOSURE_TYPE_MEDICAL = "MEDICAL"
    const val CLOSURE_TYPE_NON_MEDICAL = "NON_MEDICAL"
    const val CLOSURE_TYPE_PROGRAM_COMPLETION = "PROGRAM_COMPLETION"
  }
}
