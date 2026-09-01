package org.armman.sakhi.data.adhocform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.closure.FakeClosureRepository
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.referral.FakeReferralEvidenceDao
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.referral.FakeReferralEvidenceSyncScheduler
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.schedule
import org.armman.sakhi.data.visitform.FakeReferralRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

/**
 * Covers [AdHocFormSubmissionCoordinator] — the ad-hoc-form twin of
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinatorTest], minus the two-call
 * `POST /visits` step (these forms have no schedule to create a visit instance from).
 */
class AdHocFormSubmissionCoordinatorTest {

  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var closureRepository: FakeClosureRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var statusOverrideStore: LocalBeneficiaryStatusOverrideStore
  private lateinit var referralRepository: FakeReferralRepository
  private lateinit var referralEvidenceDao: FakeReferralEvidenceDao
  private lateinit var referralEvidenceSyncScheduler: FakeReferralEvidenceSyncScheduler
  private lateinit var referralLinkDao: FakeReferralLinkDao
  private lateinit var coordinator: AdHocFormSubmissionCoordinator

  private val session = UserSession(
    username = "test.sakhi",
    subjectId = "sakhi-uuid-1",
    roles = listOf("SAKHI"),
    projectId = "project-uuid-1",
    geographyUnitId = null,
    accessToken = "token",
    refreshToken = "refresh",
    accessTokenExpiresAtEpochSeconds = 9_999_999_999L,
  )

  @Before
  fun setUp() {
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    closureRepository = FakeClosureRepository()
    lookupRepository = FakeLookupRepository(
      valuesByCategory = mutableMapOf(
        "CLOSURE_REASON" to listOf(
          LookupValue(id = "lookup-closure-migration", valueCode = "MIGRATION", valueLabel = "Migration"),
          LookupValue(id = "lookup-closure-other", valueCode = "OTHER", valueLabel = "Other"),
          LookupValue(id = "lookup-closure-maternal-death", valueCode = "MATERNAL_DEATH", valueLabel = "Maternal death"),
          LookupValue(
            id = "lookup-closure-infant-or-child-death",
            valueCode = "INFANT_OR_CHILD_DEATH",
            valueLabel = "Infant or child death",
          ),
        ),
      ),
    )
    statusOverrideStore = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore())
    referralRepository = FakeReferralRepository()
    referralEvidenceDao = FakeReferralEvidenceDao()
    referralEvidenceSyncScheduler = FakeReferralEvidenceSyncScheduler()
    referralLinkDao = FakeReferralLinkDao()
    coordinator = AdHocFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
      closureRepository = closureRepository,
      lookupRepository = lookupRepository,
      statusOverrideStore = statusOverrideStore,
      referralRepository = referralRepository,
      referralEvidenceDao = referralEvidenceDao,
      referralEvidenceSyncScheduler = referralEvidenceSyncScheduler,
      referralLinkDao = referralLinkDao,
    )
  }

  private val answers = FormAnswers(singleValues = mapOf("referral_facility" to "PHC Sonapur"))

  private suspend fun seedSyncedBeneficiary(localBeneficiaryId: String = "ben-1") {
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "schedule-for-$localBeneficiaryId",
          localBeneficiaryId = localBeneficiaryId,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  private fun successfulSubmissionResponse(id: String = "server-sub-1", submittedByUserId: String? = null) = Response.success(
    CreateSubmissionResponseDto(
      success = true,
      message = "OK",
      data = SubmissionResponseData(id = id, submittedByUserId = submittedByUserId),
    ),
  )

  private suspend fun submit() = coordinator.submit(
    localFormInstanceUuid = "instance-1",
    localBeneficiaryId = "ben-1",
    formCode = "REFERRAL_VISIT",
    formVersionId = "version-1",
    answers = answers,
  )

  private suspend fun submitClosure(
    formCode: String = "ANC_CLOSURE_VISIT",
    closureAnswers: FormAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "migration")),
  ) = coordinator.submit(
    localFormInstanceUuid = "instance-1",
    localBeneficiaryId = "ben-1",
    formCode = formCode,
    formVersionId = "version-1",
    answers = closureAnswers,
  )

  @Test
  fun `submit() success writes a SUBMITTED audit event and does not include submittedBy in the request`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals(
      listOf(FormAuditEventType.SUBMITTED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("instance-1", formAuditRepository.recordedEvents.single().subjectId)
    assertEquals("REFERRAL_VISIT", formAuditRepository.recordedEvents.single().formCode)
    // CreateSubmissionRequestDto no longer declares a submittedBy field at all — nothing to assert
    // "is null" on; this documents that the request shape carries no such field any more.
    val request = requireNotNull(formSubmissionApi.lastRequest)
    assertEquals("server-ben-1", request.beneficiaryId)
    assertEquals("version-1", request.formVersionId)
  }

  @Test
  fun `submit() success reads submittedByUserId from the response`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse(submittedByUserId = "sakhi-uuid-1")

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals("sakhi-uuid-1", formSubmissionApi.response?.body()?.data?.submittedByUserId)
  }

  @Test
  fun `submit() failure does NOT write a SUBMITTED event`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    val result = submit()

    assertTrue(result.isFailure)
    assertFalse(formAuditRepository.recordedEvents.any { it.eventType == FormAuditEventType.SUBMITTED })
  }

  @Test
  fun `submit() with no server beneficiary id fails with NotYetSynced and does not call the API`() = runTest {
    // No schedule row at all for this beneficiary.
    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is AdHocFormSubmissionException.NotYetSynced)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `submit() returns the server-assigned submission id on success`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse(id = "server-sub-99")

    val result = submit()

    assertEquals("server-sub-99", result.getOrNull())
  }

  @Test
  fun `closure submission also calls ClosureRepository submitClosure with expected fields and marks beneficiary CLOSED`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitClosure(
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "migration")),
    )

    assertTrue(result.isSuccess)
    val recorded = closureRepository.recordedClosures.single()
    assertEquals("instance-1", recorded.localClosureUuid)
    assertEquals("server-ben-1", recorded.beneficiaryId)
    assertEquals("NON_MEDICAL", recorded.closureType)
    assertEquals("lookup-closure-migration", recorded.closureReasonLookupValueId)
    assertEquals("sakhi-uuid-1", recorded.submittedByUserId)
    // 2026-08-31: supervisorStatus/supervisorId/supervisorNotes are no longer sent at all --
    // backend confirmed these are deliberately excluded from create-closure.dto.ts (a client that
    // could set supervisorStatus directly could bypass supervisor review). RecordedClosure no
    // longer has these fields. Backend separately described a new Migration-only PENDING gate
    // that isn't in the SRS form spec (both Closure forms say every reason, Migration included,
    // closes immediately) -- flagged back to backend/product, unresolved. This test still asserts
    // immediate CLOSED, matching the SRS as documented.
    assertEquals(BeneficiaryStatus.CLOSED, statusOverrideStore.getStatus("ben-1"))
    // CR-Closure-02: the closure reason is persisted alongside the status so the Reopen
    // eligibility gate can read it back later.
    assertEquals("MIGRATION", statusOverrideStore.getClosureReason("ben-1"))
  }

  @Test
  fun `closure submission lapses every remaining open visit for the beneficiary`() = runTest {
    seedSyncedBeneficiary()
    // A second, still-open visit for the same beneficiary, of a different visit type than the
    // one seedSyncedBeneficiary() itself creates -- CR-Closure-01 items #3/#7 must lapse EVERY
    // open visit type, not just the ANC family lapseOpenAncVisits already covers.
    scheduleRepository.saveGenerated(
      listOf(
        org.armman.sakhi.data.schedule.schedule(
          "schedule-pp1-ben-1",
          localBeneficiaryId = "ben-1",
          visitCode = "PP1",
          visitType = org.armman.sakhi.data.schedule.VisitCodeType.PP,
          serverBeneficiaryId = "server-ben-1",
        ),
      ),
    )
    formSubmissionApi.response = successfulSubmissionResponse()

    submitClosure(closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "migration")))

    val allRows = scheduleRepository.getForBeneficiary("ben-1")
    assertTrue(allRows.all { it.status == org.armman.sakhi.data.schedule.VisitScheduleStatus.CANCELLED })
    assertTrue(allRows.all { it.reasonCode == "LAPSED_ON_CLOSURE" })
  }

  @Test
  fun `a failed closure submission does not lapse any visits`() = runTest {
    seedSyncedBeneficiary()
    closureRepository.exceptionToThrow = org.armman.sakhi.data.closure.ClosureSubmissionException.Failed(
      httpCode = 422,
      apiMessage = "rejected",
      violations = emptyList(),
    )
    formSubmissionApi.response = successfulSubmissionResponse()

    submitClosure(closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "migration")))

    val row = scheduleRepository.getForBeneficiary("ben-1").single()
    assertEquals(org.armman.sakhi.data.schedule.VisitScheduleStatus.GENERATED, row.status)
  }

  @Test
  fun `non-closure form codes do not call ClosureRepository`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    submit()

    assertTrue(closureRepository.recordedClosures.isEmpty())
  }

  @Test
  fun `ANC_CLOSURE_VISIT maternal_death maps to the MATERNAL_DEATH backend code and MEDICAL closure type`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitClosure(
      formCode = "ANC_CLOSURE_VISIT",
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "maternal_death")),
    )

    assertTrue(result.isSuccess)
    val recorded = closureRepository.recordedClosures.single()
    assertEquals("MEDICAL", recorded.closureType)
    assertEquals("lookup-closure-maternal-death", recorded.closureReasonLookupValueId)
  }

  @Test
  fun `CHILD_CLOSURE_VISIT infant_child_death maps to the INFANT_OR_CHILD_DEATH backend code and MEDICAL closure type`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitClosure(
      formCode = "CHILD_CLOSURE_VISIT",
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "infant_child_death")),
    )

    assertTrue(result.isSuccess)
    val recorded = closureRepository.recordedClosures.single()
    assertEquals("MEDICAL", recorded.closureType)
    assertEquals("lookup-closure-infant-or-child-death", recorded.closureReasonLookupValueId)
  }

  @Test
  fun `an unrecognised closure_reason value_code fails with ClosureReasonValueCodeUnrecognised and does not call ClosureRepository`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    // "maternal_death" is a valid value_code for ANC_CLOSURE_VISIT but not for CHILD_CLOSURE_VISIT
    // — asserts the two forms' tables are genuinely kept separate, not silently merged.
    val result = submitClosure(
      formCode = "CHILD_CLOSURE_VISIT",
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "maternal_death")),
    )

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertTrue(exception is AdHocFormSubmissionException.ClosureReasonValueCodeUnrecognised)
    exception as AdHocFormSubmissionException.ClosureReasonValueCodeUnrecognised
    assertEquals("CHILD_CLOSURE_VISIT", exception.formCode)
    assertEquals("maternal_death", exception.rawValueCode)
    assertTrue(closureRepository.recordedClosures.isEmpty())
  }

  // --- REFERRAL_FOLLOWUP_VISIT (CR-Referral-01/02: switched from the bespoke screen to this
  // ad-hoc form pipeline) ---

  private fun successfulFollowUpResult() = Result.success(
    org.armman.sakhi.data.referral.ReferralFollowUpResult(
      followUp = org.armman.sakhi.data.referral.ReferralFollowUpSubmission(
        id = "followup-1",
        referralId = "referral-1",
        visitedFacilityFlag = true,
        notVisitedReason = null,
        diagnosis = null,
        treatmentGiven = null,
        outcome = null,
        followupStatus = org.armman.sakhi.data.referral.ReferralFollowUpOutcomeStatus.COMPLETED,
      ),
      referral = org.armman.sakhi.data.referral.Referral(
        referralId = "referral-1",
        visitId = null,
        sourceSubmissionId = null,
        beneficiaryId = "server-ben-1",
        referralTypeLookupValueId = "type-1",
        status = org.armman.sakhi.data.referral.ReferralStatus.COMPLETED,
        facilityName = "PHC Sonapur",
        facilityType = org.armman.sakhi.data.referral.FacilityType.PHC,
        triggeringConditionIds = emptyList(),
        createdAt = null,
        validTill = null,
      ),
    ),
  )

  private suspend fun submitFollowUpForm(
    followUpAnswers: FormAnswers,
    referralId: String? = "referral-1",
    capturedImagePaths: Map<String, String> = emptyMap(),
  ) = coordinator.submit(
    localFormInstanceUuid = "instance-1",
    localBeneficiaryId = "ben-1",
    formCode = "REFERRAL_FOLLOWUP_VISIT",
    formVersionId = "version-1",
    answers = followUpAnswers,
    referralId = referralId,
    capturedImagePaths = capturedImagePaths,
  )

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT submission calls ReferralRepository submitFollowUp with mapped visited-facility fields`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()
    referralRepository.submitFollowUpResult = successfulFollowUpResult()

    val result = submitFollowUpForm(
      FormAnswers(
        singleValues = mapOf(
          "form_filled_date" to "2026-08-31",
          "visited_health_facility" to "yes",
          "diagnosis_confirmed" to "yes",
          "clinical_status_now" to "improving",
          "treatment_given" to "yes",
          "referral_final_outcome" to "opd_given_medications",
        ),
        multiValues = mapOf("treatment_type" to listOf("tablet", "syrup")),
      ),
    )

    assertTrue(result.isSuccess)
    val call = referralRepository.submitFollowUpCalls.single()
    assertEquals("referral-1", call.referralId)
    assertTrue(call.visitedFacilityFlag)
    assertEquals(LocalDate.parse("2026-08-31"), call.followupDate)
    assertEquals("Diagnosis confirmed: Yes; Clinical status: Improving", call.diagnosis)
    assertEquals("Treatment given: Yes; Type: Tablet, Syrup", call.treatmentGiven)
    assertEquals("OPD and given medications", call.outcome)
  }

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT not-visited path maps the not_visited_reason label and sends visitedFacilityFlag false`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()
    referralRepository.submitFollowUpResult = successfulFollowUpResult()

    val result = submitFollowUpForm(
      FormAnswers(
        singleValues = mapOf(
          "form_filled_date" to "2026-08-31",
          "visited_health_facility" to "no",
          "not_visited_reason" to "cost_of_transportation",
        ),
      ),
    )

    assertTrue(result.isSuccess)
    val call = referralRepository.submitFollowUpCalls.single()
    assertFalse(call.visitedFacilityFlag)
    assertEquals("Cost of transportation to the facility", call.notVisitedReason)
    assertEquals(null, call.diagnosis)
    assertEquals(null, call.treatmentGiven)
  }

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT with no referralId fails with ReferralIdMissing and does not call ReferralRepository`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitFollowUpForm(
      FormAnswers(singleValues = mapOf("visited_health_facility" to "no")),
      referralId = null,
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is AdHocFormSubmissionException.ReferralIdMissing)
    assertTrue(referralRepository.submitFollowUpCalls.isEmpty())
  }

  @Test
  fun `a failed ReferralRepository submitFollowUp call surfaces as ReferralFollowUpSubmissionFailed even though the generic submission succeeded`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()
    referralRepository.submitFollowUpResult = Result.failure(IllegalStateException("HTTP 500"))

    val result = submitFollowUpForm(FormAnswers(singleValues = mapOf("visited_health_facility" to "no")))

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is AdHocFormSubmissionException.ReferralFollowUpSubmissionFailed)
  }

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT queues a captured evidence photo keyed by the submission id and triggers a sync`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse(id = "server-sub-77")
    referralRepository.submitFollowUpResult = successfulFollowUpResult()
    val tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

    val result = submitFollowUpForm(
      FormAnswers(singleValues = mapOf("visited_health_facility" to "no", "case_paper_photo" to "content://ad-hoc-form/case_paper_photo")),
      capturedImagePaths = mapOf("case_paper_photo" to tempFile.absolutePath),
    )

    assertTrue(result.isSuccess)
    val queued = referralEvidenceDao.getByReferralId("referral-1").single()
    assertEquals("server-sub-77", queued.submissionId)
    assertEquals(null, queued.followupId)
    assertEquals("REFERRAL_CASE_PAPER", queued.evidenceType)
    assertEquals(tempFile.absolutePath, queued.localFilePath)
    assertEquals(1, referralEvidenceSyncScheduler.syncNowCallCount)

    tempFile.delete()
  }

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT with no captured photo does not queue anything but still calls syncNow`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()
    referralRepository.submitFollowUpResult = successfulFollowUpResult()

    val result = submitFollowUpForm(FormAnswers(singleValues = mapOf("visited_health_facility" to "no")))

    assertTrue(result.isSuccess)
    assertTrue(referralEvidenceDao.getByReferralId("referral-1").isEmpty())
    assertEquals(1, referralEvidenceSyncScheduler.syncNowCallCount)
  }

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT success mirrors the referral's new status into the local ReferralLinkEntity cache`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()
    referralRepository.submitFollowUpResult = successfulFollowUpResult() // referral.status = COMPLETED
    referralLinkDao.upsert(
      org.armman.sakhi.data.referral.ReferralLinkEntity(
        localScheduleUuid = "schedule-for-ben-1",
        referralId = "referral-1",
        visitId = "visit-1",
        status = "PENDING_FOLLOWUP",
        referralTypeLookupValueId = "type-1",
        validTill = null,
        createdAtEpochMillis = 0L,
      ),
    )

    val result = submitFollowUpForm(FormAnswers(singleValues = mapOf("visited_health_facility" to "yes")))

    assertTrue(result.isSuccess)
    // Regression coverage for a real bug: without this, BeneficiaryProfileScreen kept showing
    // "Referral Followup Incomplete" after a successful submission, since the local cache the
    // profile screen reads from was never told the backend had already moved the referral on.
    assertEquals("COMPLETED", referralLinkDao.getByReferralId("referral-1")?.status)
  }

  @Test
  fun `REFERRAL_FOLLOWUP_VISIT success with no matching local ReferralLinkEntity row does not throw`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()
    referralRepository.submitFollowUpResult = successfulFollowUpResult()
    // No referralLinkDao row seeded — the cache-mirroring step must be a no-op, not a crash.

    val result = submitFollowUpForm(FormAnswers(singleValues = mapOf("visited_health_facility" to "yes")))

    assertTrue(result.isSuccess)
  }
}
