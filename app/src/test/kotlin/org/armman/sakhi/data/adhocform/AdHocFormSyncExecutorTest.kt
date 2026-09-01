package org.armman.sakhi.data.adhocform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.closure.FakeClosureRepository
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.referral.FakeReferralEvidenceDao
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.referral.FakeReferralEvidenceSyncScheduler
import org.armman.sakhi.data.visitform.FakeReferralRepository
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

/**
 * Covers [AdHocFormSyncExecutor] — mirrors [org.armman.sakhi.data.visitform.VisitFormSyncExecutorTest]'s
 * core cases. Unlike the visit-form executor there is no partial two-call submit to resume from —
 * one failed attempt just retries the same single call.
 */
class AdHocFormSyncExecutorTest {

  private lateinit var dao: FakeAdHocFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var coordinator: AdHocFormSubmissionCoordinator
  private lateinit var executor: AdHocFormSyncExecutor

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
    dao = FakeAdHocFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    coordinator = AdHocFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      sessionStore = sessionStore,
      formAuditRepository = FakeFormAuditRepository(),
      closureRepository = FakeClosureRepository(),
      lookupRepository = FakeLookupRepository(),
      statusOverrideStore = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore()),
      referralRepository = FakeReferralRepository(),
      referralEvidenceDao = FakeReferralEvidenceDao(),
      referralEvidenceSyncScheduler = FakeReferralEvidenceSyncScheduler(),
      referralLinkDao = FakeReferralLinkDao(),
    )
    executor = AdHocFormSyncExecutor(dao, secureStore, coordinator)
  }

  private fun answers() = FormAnswers(singleValues = mapOf("referral_facility" to "PHC Sonapur"))

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

  private suspend fun seedPendingDraft(
    localFormInstanceUuid: String = "instance-1",
    localBeneficiaryId: String = "ben-1",
    formCode: String = "REFERRAL_VISIT",
    syncStatus: EnrollmentSyncStatus = EnrollmentSyncStatus.PENDING,
  ) {
    secureStore.putString(
      adHocFormDraftPayloadKey(localFormInstanceUuid),
      adHocFormDraftGson.toJson(AdHocFormDraftPayload(answers())),
    )
    dao.upsert(
      AdHocFormDraftEntity(
        localFormInstanceUuid = localFormInstanceUuid,
        localBeneficiaryId = localBeneficiaryId,
        formCode = formCode,
        formVersionId = "version-1",
        syncStatus = syncStatus,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        serverSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
  }

  private fun successfulSubmissionResponse(id: String = "server-sub-1") = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = id)),
  )

  @Test
  fun `no pending drafts completes without calling the API`() = runTest {
    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `happy path syncs a pending draft and marks it SYNCED with the server submission id`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft()
    formSubmissionApi.response = successfulSubmissionResponse(id = "server-sub-42")

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-1"))
    assertEquals(EnrollmentSyncStatus.SYNCED, entity.syncStatus)
    assertEquals("server-sub-42", entity.serverSubmissionId)
    assertEquals(1, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne on an already-SYNCED draft returns Synced without calling the API`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft(syncStatus = EnrollmentSyncStatus.SYNCED)

    val result = executor.runOne("instance-1")

    assertEquals(AdHocFormSyncItemResult.Synced, result)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne on an unknown draft returns null`() = runTest {
    assertNull(executor.runOne("does-not-exist"))
  }

  // --- reclaimStaleSyncing ---------------------------------------------------------------------

  @Test
  fun `a draft orphaned in SYNCING is reclaimed and uploaded by the next run`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft()
    dao.upsert(requireNotNull(dao.getByLocalFormInstanceUuid("instance-1")).copy(syncStatus = EnrollmentSyncStatus.SYNCING))
    formSubmissionApi.response = successfulSubmissionResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(1, formSubmissionApi.callCount)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalFormInstanceUuid("instance-1")?.syncStatus)
  }

  @Test
  fun `reclaiming SYNCING leaves a SYNCED draft untouched`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft(syncStatus = EnrollmentSyncStatus.SYNCED)

    executor.run()

    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalFormInstanceUuid("instance-1")?.syncStatus)
    assertEquals(0, formSubmissionApi.callCount)
  }

  // --- NotYetSynced: retryable, never a hard failure --------------------------------------------

  @Test
  fun `NotYetSynced is treated as retryable and leaves the draft PENDING, not FAILED`() = runTest {
    // No schedule row at all for this beneficiary — no server beneficiary id known yet.
    seedPendingDraft()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    assertEquals(0, entity.retryCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne on NotYetSynced returns Retryable`() = runTest {
    seedPendingDraft()

    val result = executor.runOne("instance-1")

    assertTrue(result is AdHocFormSyncItemResult.Retryable)
  }

  // --- Transient IOException ---------------------------------------------------------------------

  /** Test-only [org.armman.sakhi.data.forms.FormSubmissionApi] that always throws — the shared
   * `FakeFormSubmissionApi` has no fault-injection hook, and adding one would be an out-of-scope
   * touch to a file shared by other forms' tests, so this stays local to this one test. */
  private class ThrowingFormSubmissionApi(
    private val exception: Throwable,
  ) : org.armman.sakhi.data.forms.FormSubmissionApi {
    override suspend fun createSubmission(
      formCode: String,
      request: org.armman.sakhi.data.forms.CreateSubmissionRequestDto,
    ): Response<CreateSubmissionResponseDto> {
      throw exception
    }
  }

  @Test
  fun `a transient IOException is retryable and leaves the draft PENDING`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft()
    val faultyCoordinator = AdHocFormSubmissionCoordinator(
      formSubmissionApi = ThrowingFormSubmissionApi(IOException("no route to host")),
      visitScheduleRepository = scheduleRepository,
      sessionStore = SessionStore(FakeSecureKeyValueStore()).apply { saveSession(session) },
      formAuditRepository = FakeFormAuditRepository(),
      closureRepository = FakeClosureRepository(),
      lookupRepository = FakeLookupRepository(),
      statusOverrideStore = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore()),
      referralRepository = FakeReferralRepository(),
      referralEvidenceDao = FakeReferralEvidenceDao(),
      referralEvidenceSyncScheduler = FakeReferralEvidenceSyncScheduler(),
      referralLinkDao = FakeReferralLinkDao(),
    )
    val faultyExecutor = AdHocFormSyncExecutor(dao, secureStore, faultyCoordinator)

    val outcome = faultyExecutor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    assertEquals(0, entity.retryCount)
  }

  // --- Permanent failures --------------------------------------------------------------------

  @Test
  fun `a validation failure from the submissions endpoint marks the draft FAILED and increments retryCount`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft()
    formSubmissionApi.response = Response.error(
      400,
      "{\"success\":false,\"message\":\"boom\"}".toResponseBody("application/json".toMediaType()),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    assertEquals(1, entity.retryCount)
  }

  @Test
  fun `missing encrypted payload marks the draft FAILED without calling the API`() = runTest {
    seedSyncedBeneficiary()
    // Metadata row with no matching secureStore payload — the orphan case.
    dao.upsert(
      AdHocFormDraftEntity(
        localFormInstanceUuid = "instance-1",
        localBeneficiaryId = "ben-1",
        formCode = "REFERRAL_VISIT",
        formVersionId = "version-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        serverSubmissionId = null,
        lastErrorMessage = null,
      ),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, formSubmissionApi.callCount)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalFormInstanceUuid("instance-1")?.syncStatus)
  }

  @Test
  fun `submits the formVersionId and server beneficiary id recorded at save time`() = runTest {
    seedSyncedBeneficiary()
    seedPendingDraft()
    formSubmissionApi.response = successfulSubmissionResponse()

    executor.run()

    assertEquals("version-1", formSubmissionApi.lastRequest?.formVersionId)
    assertEquals("server-ben-1", formSubmissionApi.lastRequest?.beneficiaryId)
  }
}
