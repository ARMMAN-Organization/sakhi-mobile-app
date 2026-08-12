package org.armman.sakhi.data.visitform

import android.util.Log
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.forms.CreateSubmissionRequestDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val FORM_CODE = "ANC_VISIT"
private const val CATEGORY_VISIT_STATUS = "VISIT_STATUS"
private const val VALUE_CODE_COMPLETED = "COMPLETED"

/** Temporary diagnostic tag for the online-enrollment-to-visit-submit chain (CR-026 debugging). */
private const val TAG = "SakhiSync"

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
  ) : VisitFormSubmissionException("POST /forms/$FORM_CODE/submissions failed: HTTP $httpCode — $body") {
    override val userMessage: String
      get() = SubmitErrorCopy.forApiError(apiMessage, emptyMap(), violations)
  }
}

/**
 * Orchestrates the online-only visit-submit slice: `POST /visits` (create the visit instance from
 * the beneficiary's local schedule row) → `POST /forms/ANC_VISIT/submissions` using the *returned*
 * visit id, then flips the local schedule row to [VisitScheduleStatus.COMPLETED] so the
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
 */
@Singleton
class VisitFormSubmissionCoordinator @Inject constructor(
  private val visitApi: VisitApi,
  private val formSubmissionApi: FormSubmissionApi,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val lookupRepository: LookupRepository,
  private val sessionStore: SessionStore,
) {

  suspend fun submit(
    localScheduleUuid: String,
    formVersionId: String,
    answers: FormAnswers,
    visitDate: LocalDate,
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
      localSubmissionUuid = newLocalVisitSubmissionUuid(),
      formData = answers.singleValues + answers.multiValues,
    )
    val submissionResponse = formSubmissionApi.createSubmission(FORM_CODE, submissionRequest)
    if (!submissionResponse.isSuccessful) {
      val rawSubmissionBody = submissionResponse.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawSubmissionBody)
      throw VisitFormSubmissionException.FormSubmissionFailed(
        httpCode = submissionResponse.code(),
        body = rawSubmissionBody,
        apiMessage = apiError.message?.takeIf { it != rawSubmissionBody },
        violations = apiError.violations,
      )
    }

    visitScheduleRepository.updateStatus(localScheduleUuid, VisitScheduleStatus.COMPLETED)
  }
}

/** Fresh per-attempt — unlike [org.armman.sakhi.data.forms.newLocalSubmissionUuid]'s once-per-draft
 * contract, nothing on [VisitFormDraftEntity] holds this across retries, so a fresh
 * value each call is correct, not a shortcut. */
fun newLocalVisitUuid(): String = UUID.randomUUID().toString()

fun newLocalVisitSubmissionUuid(): String = UUID.randomUUID().toString()
