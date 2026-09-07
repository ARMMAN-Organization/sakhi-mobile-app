package org.armman.sakhi.data.visitform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FakeFormsApi
import org.armman.sakhi.data.forms.VisitCodeFormResolver
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.riskassessment.FakeRiskAssessmentDao
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
 * Covers [VisitFormSyncExecutor] — the CR-026b background/immediate sync layer sitting on top of
 * [VisitFormSubmissionCoordinator]. The one thing no other queue's executor test has to cover: the
 * two-call submit can fail having *partially* succeeded, and a resumed attempt must not re-create
 * the visit instance (`serverVisitId` round-trips via [VisitFormDraftEntity.serverVisitId]).
 */
class VisitFormSyncExecutorTest {

  private class FakeVisitApi : VisitApi {
    var response: Response<CreateVisitInstanceResponseDto>? = null
    var exceptionToThrow: Throwable? = null
    var lastRequest: CreateVisitInstanceRequestDto? = null
    var callCount = 0

    override suspend fun createVisitInstance(
      request: CreateVisitInstanceRequestDto,
    ): Response<CreateVisitInstanceResponseDto> {
      callCount++
      lastRequest = request
      exceptionToThrow?.let { throw it }
      return response!!
    }
  }

  private lateinit var dao: FakeVisitFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var visitApi: FakeVisitApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var coordinator: VisitFormSubmissionCoordinator
  private lateinit var executor: VisitFormSyncExecutor

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
    dao = FakeVisitFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    visitApi = FakeVisitApi()
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    lookupRepository = FakeLookupRepository(
      valuesByCategory = mutableMapOf(
        "VISIT_STATUS" to listOf(
          LookupValue(id = "lookup-visit-status-completed", valueCode = "COMPLETED", valueLabel = "Completed"),
        ),
      ),
    )
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    coordinator = VisitFormSubmissionCoordinator(
      visitApi = visitApi,
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      lookupRepository = lookupRepository,
      sessionStore = sessionStore,
      // CR-033/CR-034: defaults to a 404 map response, so the resolver falls back to its own
      // hardcoded map — VisitCodeType.ANC still resolves to "ANC_VISIT", matching this test's
      // pre-CR-033 behaviour exactly.
      visitCodeFormResolver = VisitCodeFormResolver(FakeFormsApi(), FakeSecureKeyValueStore()),
      formAuditRepository = FakeFormAuditRepository(),
      deliverySessionRepository = org.armman.sakhi.data.delivery.RoomDeliverySessionRepository(
        org.armman.sakhi.data.delivery.FakeDeliverySessionDao(),
      ),
      childFormDraftDao = org.armman.sakhi.data.childregistration.FakeChildFormDraftDao(),
      riskAssessmentApi = FakeRiskAssessmentApi(),
      referralRepository = FakeReferralRepository(),
      referralLinkDao = FakeReferralLinkDao(),
      riskAssessmentDao = FakeRiskAssessmentDao(),
      lmpChangeRepository = org.armman.sakhi.data.lmpchange.FakeLmpChangeRepository(),
    )
    executor = VisitFormSyncExecutor(dao, secureStore, coordinator)
  }

  private fun answers() = FormAnswers(singleValues = mapOf("weight_kg" to "58"))

  private suspend fun seedSyncedSchedule(localScheduleUuid: String = "schedule-1") {
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          localScheduleUuid,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = "server-beneficiary-1",
        ),
      ),
    )
  }

  private suspend fun seedPendingDraft(
    localScheduleUuid: String = "schedule-1",
    serverVisitId: String? = null,
    syncStatus: EnrollmentSyncStatus = EnrollmentSyncStatus.PENDING,
    localSubmissionUuid: String = "submission-uuid-1",
  ) {
    secureStore.putString(
      visitFormDraftPayloadKey(localScheduleUuid),
      visitFormDraftGson.toJson(VisitFormDraftPayload(answers())),
    )
    dao.upsert(
      VisitFormDraftEntity(
        localScheduleUuid = localScheduleUuid,
        formCode = "ANC_VISIT",
        formVersionId = "version-1",
        localSubmissionUuid = localSubmissionUuid,
        visitDateIso = "2026-08-07",
        syncStatus = syncStatus,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        serverVisitId = serverVisitId,
        lastErrorMessage = null,
      ),
    )
  }

  private fun successfulVisitResponse(id: String = "server-visit-1") = Response.success(
    CreateVisitInstanceResponseDto(success = true, message = "OK", data = VisitInstanceResponseData(id = id)),
  )

  private fun successfulSubmissionResponse(id: String = "server-sub-1") = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = id)),
  )

  @Test
  fun `no pending drafts completes without calling either API`() = runTest {
    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, visitApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `happy path syncs a pending draft and marks it SYNCED with the server visit id`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft()
    visitApi.response = successfulVisitResponse(id = "server-visit-42")
    formSubmissionApi.response = successfulSubmissionResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.SYNCED, entity.syncStatus)
    assertEquals("server-visit-42", entity.serverVisitId)
    assertEquals(1, visitApi.callCount)
    assertEquals(1, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne on an already-SYNCED draft returns Synced without calling either API`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft(serverVisitId = "server-visit-1", syncStatus = EnrollmentSyncStatus.SYNCED)

    val result = executor.runOne("schedule-1")

    assertEquals(VisitFormSyncItemResult.Synced(), result)
    assertEquals(0, visitApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne on an unknown draft returns null`() = runTest {
    assertNull(executor.runOne("does-not-exist"))
  }

  // --- Resume-from-serverVisitId: the two-call submit's own failure mode -----------------------

  @Test
  fun `a draft resuming from a prior serverVisitId skips POST visits and only retries the submission`() = runTest {
    // Simulates a prior attempt where POST /visits succeeded (captured via onVisitCreated) but the
    // form-submission call then failed — exactly the state a process death between the two calls,
    // or a permanent submission failure, would leave behind.
    seedSyncedSchedule()
    seedPendingDraft(serverVisitId = "server-visit-1", syncStatus = EnrollmentSyncStatus.FAILED)
    formSubmissionApi.response = successfulSubmissionResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    // POST /visits must never be called again — a second call would create a duplicate visit
    // instance for the same visit.
    assertEquals(0, visitApi.callCount)
    assertEquals(1, formSubmissionApi.callCount)
    assertEquals("server-visit-1", formSubmissionApi.lastRequest?.visitId)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.SYNCED, entity.syncStatus)
    assertEquals("server-visit-1", entity.serverVisitId)
  }

  @Test
  fun `a resumed submission replays the draft's persisted localSubmissionUuid, not a fresh one`() = runTest {
    // Simulates the same partial-success state as the test above, but this one is about the
    // *other* half of CR-026b's resume contract: the retried POST /forms/.../submissions call
    // must carry the exact same localSubmissionUuid the first (failed) attempt used, or the
    // server sees it as a brand-new submission rather than a retry of the same one.
    seedSyncedSchedule()
    seedPendingDraft(
      serverVisitId = "server-visit-1",
      syncStatus = EnrollmentSyncStatus.FAILED,
      localSubmissionUuid = "submission-uuid-retry-1",
    )
    formSubmissionApi.response = successfulSubmissionResponse()

    executor.run()

    assertEquals("submission-uuid-retry-1", formSubmissionApi.lastRequest?.localSubmissionUuid)
  }

  @Test
  fun `a fresh attempt with no serverVisitId does call POST visits`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft(serverVisitId = null)
    visitApi.response = successfulVisitResponse(id = "server-visit-99")
    formSubmissionApi.response = successfulSubmissionResponse()

    executor.run()

    assertEquals(1, visitApi.callCount)
    assertEquals("server-visit-99", formSubmissionApi.lastRequest?.visitId)
  }

  @Test
  fun `a step-1-only success persists the server visit id even if step 2 then fails`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft(serverVisitId = null)
    visitApi.response = successfulVisitResponse(id = "server-visit-7")
    formSubmissionApi.response = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    // The visit id from the successful first call must survive onto the draft so a retry resumes
    // rather than re-creating the visit instance.
    assertEquals("server-visit-7", entity.serverVisitId)
    assertEquals(1, entity.retryCount)
  }

  // --- NotYetSynced: retryable, never a hard failure --------------------------------------------

  @Test
  fun `NotYetSynced is treated as retryable and leaves the draft PENDING, not FAILED`() = runTest {
    // Beneficiary/schedule simply have not synced yet — same treatment VisitScheduleSyncExecutor
    // already gives this exact condition.
    scheduleRepository.saveGenerated(listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = null)))
    seedPendingDraft()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    assertEquals(0, entity.retryCount)
    assertEquals(0, visitApi.callCount)
  }

  @Test
  fun `runOne on NotYetSynced returns Retryable`() = runTest {
    scheduleRepository.saveGenerated(listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = null)))
    seedPendingDraft()

    val result = executor.runOne("schedule-1")

    assertTrue(result is VisitFormSyncItemResult.Retryable)
  }

  // --- Transient IOException ---------------------------------------------------------------------

  @Test
  fun `a transient IOException is retryable and leaves the draft PENDING`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft()
    visitApi.exceptionToThrow = IOException("no route to host")

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    assertEquals(0, entity.retryCount)
  }

  // --- Permanent failures --------------------------------------------------------------------

  @Test
  fun `a validation failure from POST visits marks the draft FAILED and increments retryCount`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft()
    visitApi.response = Response.error(
      400,
      "{\"success\":false,\"message\":\"boom\"}".toResponseBody("application/json".toMediaType()),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    assertEquals(1, entity.retryCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `missing encrypted payload marks the draft FAILED without calling either API`() = runTest {
    seedSyncedSchedule()
    // Metadata row with no matching secureStore payload — the orphan case.
    dao.upsert(
      VisitFormDraftEntity(
        localScheduleUuid = "schedule-1",
        formCode = "ANC_VISIT",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-uuid-1",
        visitDateIso = "2026-08-07",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        serverVisitId = null,
        lastErrorMessage = null,
      ),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, visitApi.callCount)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
  }

  // --- reclaimStaleSyncing ---------------------------------------------------------------------

  @Test
  fun `a draft orphaned in SYNCING is reclaimed and uploaded by the next run`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft()
    dao.upsert(requireNotNull(dao.getByLocalScheduleUuid("schedule-1")).copy(syncStatus = EnrollmentSyncStatus.SYNCING))
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(1, visitApi.callCount)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
  }

  @Test
  fun `reclaiming SYNCING leaves a SYNCED draft untouched`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft(serverVisitId = "server-visit-1", syncStatus = EnrollmentSyncStatus.SYNCED)

    executor.run()

    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
    assertEquals(0, visitApi.callCount)
  }

  @Test
  fun `submits the formVersionId recorded at save time`() = runTest {
    seedSyncedSchedule()
    seedPendingDraft()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    executor.run()

    assertEquals("version-1", formSubmissionApi.lastRequest?.formVersionId)
    assertEquals("server-beneficiary-1", formSubmissionApi.lastRequest?.beneficiaryId)
  }
}
