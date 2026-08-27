package org.armman.sakhi.data.adhocform

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.closure.ClosureRepository
import org.armman.sakhi.data.closure.ClosureSubmissionException
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormDateRuleset
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.LocalDate
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

    submissionData.id
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
      closureRepository.submitClosure(
        localClosureUuid = localClosureUuid,
        beneficiaryId = serverBeneficiaryId,
        closureType = backendReasonCode.toClosureType(),
        closureReasonLookupValueId = reasonLookupValue.id,
        eventDate = eventDate,
        closureDate = closureDate,
        submittedByUserId = submittedByUserId,
        // Judgment call — flagged for product sanity-check: only a MIGRATION-reason closure is
        // supervisor-reviewed before it takes effect (per the backend contract's own note); every
        // other reason closes immediately, so supervisorStatus stays unset for those.
        supervisorStatus = if (backendReasonCode == CLOSURE_REASON_MIGRATION) SUPERVISOR_STATUS_PENDING else null,
        supervisorId = null,
        supervisorNotes = null,
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

    // closure_visit_date/date_of_event reuse FormDateRuleset's own constants rather than
    // duplicating the literals here. closure_reason has no existing shared constant (nothing else
    // in the codebase reads it yet) — confirmed as a real question code on both closure forms
    // against a real GET /forms/.../active-version payload, 2026-08-18 (see FormModels.kt's
    // DROPDOWN_SPELLINGS doc).
    const val QUESTION_CODE_CLOSURE_REASON = "closure_reason"

    const val LOOKUP_CATEGORY_CLOSURE_REASON = "CLOSURE_REASON"
    const val CLOSURE_REASON_MIGRATION = "MIGRATION"

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

    const val SUPERVISOR_STATUS_PENDING = "PENDING"
  }
}
