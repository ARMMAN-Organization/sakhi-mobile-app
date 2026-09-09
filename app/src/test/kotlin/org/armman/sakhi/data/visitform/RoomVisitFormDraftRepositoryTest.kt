package org.armman.sakhi.data.visitform

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FakeFormsApi
import org.armman.sakhi.data.forms.VisitCodeFormResolver
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.riskassessment.FakeRiskAssessmentDao
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/**
 * Covers [RoomVisitFormDraftRepository] — the CR-026b offline-first entry point
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.onFinish] now calls instead of talking
 * to [VisitFormSubmissionCoordinator] directly. The core contract under test: the immediate-online
 * happy path must behave exactly as it did before this feature existed, and everything new
 * (offline queueing, retry-without-duplicating-the-visit) must only add behavior on the
 * failure/offline paths.
 */
class RoomVisitFormDraftRepositoryTest {

  private class FakeVisitApi : VisitApi {
    var response: Response<CreateVisitInstanceResponseDto>? = null
    var exceptionToThrow: Throwable? = null
    var callCount = 0

    override suspend fun createVisitInstance(
      request: CreateVisitInstanceRequestDto,
    ): Response<CreateVisitInstanceResponseDto> {
      callCount++
      exceptionToThrow?.let { throw it }
      return response!!
    }
  }

  private lateinit var dao: FakeVisitFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var visitApi: FakeVisitApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var syncExecutor: VisitFormSyncExecutor
  private lateinit var repository: RoomVisitFormDraftRepository
  private lateinit var formAuditRepository: FakeFormAuditRepository

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
    connectivityChecker = FakeConnectivityChecker(online = true)
    visitApi = FakeVisitApi()
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    val lookupRepository = FakeLookupRepository(
      valuesByCategory = mutableMapOf(
        "VISIT_STATUS" to listOf(
          LookupValue(id = "lookup-visit-status-completed", valueCode = "COMPLETED", valueLabel = "Completed"),
        ),
      ),
    )
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val coordinator = VisitFormSubmissionCoordinator(
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
      sameSessionNnVisitResolver = org.armman.sakhi.data.schedule.SameSessionNnVisitResolver(scheduleRepository),
    )
    // Reuses the same dao/secureStore as the repository so runOne() sees the row submitDraft just
    // wrote — matching how the real Hilt graph wires a single instance of each.
    syncExecutor = VisitFormSyncExecutor(dao, secureStore, coordinator)
    formAuditRepository = FakeFormAuditRepository()
    repository = RoomVisitFormDraftRepository(dao, secureStore, connectivityChecker, syncExecutor, formAuditRepository)
  }

  private val answers = FormAnswers(singleValues = mapOf("weight_kg" to "58"))

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

  private fun successfulVisitResponse(id: String = "server-visit-1") = Response.success(
    CreateVisitInstanceResponseDto(success = true, message = "OK", data = VisitInstanceResponseData(id = id)),
  )

  private fun successfulSubmissionResponse(id: String = "server-sub-1") = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = id)),
  )

  private suspend fun submit(localScheduleUuid: String = "schedule-1") = repository.submitDraft(
    localScheduleUuid = localScheduleUuid,
    formCode = "ANC_VISIT",
    formVersionId = "version-1",
    answers = answers,
    visitDate = LocalDate.of(2026, 8, 7),
  )

  @Test
  fun `submitDraft online success returns Synced and marks the draft SYNCED, exactly as before offline support existed`() =
    runTest {
      seedSyncedSchedule()
      visitApi.response = successfulVisitResponse(id = "server-visit-42")
      formSubmissionApi.response = successfulSubmissionResponse()

      val result = submit()

      assertEquals(VisitFormSubmitResult.Synced(), result)
      assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
      assertEquals("server-visit-42", dao.getByLocalScheduleUuid("schedule-1")?.serverVisitId)
      // Same visible side effect as the pre-CR-026b direct-coordinator call: the schedule flips to
      // COMPLETED so the Beneficiary Profile's "See Visits" list reflects it immediately.
      assertEquals(
        VisitScheduleStatus.COMPLETED,
        scheduleRepository.getByLocalScheduleUuid("schedule-1")?.status,
      )
    }

  @Test
  fun `submitDraft offline saves locally and returns QueuedOffline without calling either API`() = runTest {
    seedSyncedSchedule()
    connectivityChecker.online = false

    val result = submit()

    assertEquals(VisitFormSubmitResult.QueuedOffline, result)
    assertEquals(0, visitApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
    // The payload must actually be there for the background worker to pick up later.
    val json = secureStore.getString(visitFormDraftPayloadKey("schedule-1"))
    assertTrue(json != null && json.isNotBlank())
  }

  @Test
  fun `submitDraft online failure still leaves the draft queued for the next Data Upload`() = runTest {
    // Before CR-026b, a failed immediate attempt was simply lost — nothing kept it around to
    // retry. Now it must have been saved locally before the attempt ran, so it stays queued
    // (PENDING or FAILED) regardless of the immediate outcome.
    seedSyncedSchedule()
    visitApi.response = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    val result = submit()

    assertTrue(result is VisitFormSubmitResult.Failed)
    val entity = requireNotNull(dao.getByLocalScheduleUuid("schedule-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    // Still resumable on the next Data Upload tap.
    val json = secureStore.getString(visitFormDraftPayloadKey("schedule-1"))
    assertTrue(json != null && json.isNotBlank())
  }

  @Test
  fun `submitDraft surfaces NotYetSynced as an immediate Failed but keeps the draft PENDING for later`() = runTest {
    // Beneficiary/schedule haven't synced yet — same hard, user-visible toast failure as before
    // CR-026b (unchanged immediate behavior), but now the draft quietly retries once they do.
    scheduleRepository.saveGenerated(listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = null)))

    val result = submit()

    assertTrue(result is VisitFormSubmitResult.Failed)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
  }

  @Test
  fun `submitDraft online but connectivity drops mid-call is reported Failed and left PENDING for retry`() = runTest {
    seedSyncedSchedule()
    visitApi.exceptionToThrow = IOException("no route to host")

    val result = submit()

    assertTrue(result is VisitFormSubmitResult.Failed)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
  }

  @Test
  fun `re-saving preserves an existing serverVisitId from a prior partial attempt`() = runTest {
    seedSyncedSchedule()
    visitApi.response = successfulVisitResponse(id = "server-visit-7")
    formSubmissionApi.response = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    // First attempt: POST /visits succeeds, form submission fails.
    submit()
    assertEquals("server-visit-7", dao.getByLocalScheduleUuid("schedule-1")?.serverVisitId)

    // A second submit (e.g. the Sakhi retries from the same screen) must not lose that id, or the
    // retry would create a second visit instance for the same visit.
    visitApi.response = successfulVisitResponse(id = "server-visit-should-not-be-used")
    formSubmissionApi.response = successfulSubmissionResponse()
    val callsBefore = visitApi.callCount
    submit()

    // POST /visits must not have been called again.
    assertEquals(callsBefore, visitApi.callCount)
    assertEquals("server-visit-7", dao.getByLocalScheduleUuid("schedule-1")?.serverVisitId)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalScheduleUuid("schedule-1")?.syncStatus)
  }

  @Test
  fun `re-saving preserves an existing localSubmissionUuid across a retry, and it reaches the submission request`() =
    runTest {
      seedSyncedSchedule()
      visitApi.response = successfulVisitResponse(id = "server-visit-7")
      formSubmissionApi.response = Response.error(
        500,
        "server error".toResponseBody("text/plain".toMediaType()),
      )

      // First attempt: POST /visits succeeds, form submission fails. localSubmissionUuid is
      // minted here, on the very first save.
      submit()
      val firstSubmissionUuid =
        requireNotNull(dao.getByLocalScheduleUuid("schedule-1")?.localSubmissionUuid)
      assertTrue(firstSubmissionUuid.isNotBlank())
      assertEquals(firstSubmissionUuid, formSubmissionApi.lastRequest?.localSubmissionUuid)

      // A second submit (e.g. the Sakhi retries from the same screen) must replay the exact same
      // localSubmissionUuid, not mint a fresh one — otherwise a retry of a request the server may
      // have already partially processed reads as a brand-new submission.
      formSubmissionApi.response = successfulSubmissionResponse()
      submit()

      assertEquals(firstSubmissionUuid, dao.getByLocalScheduleUuid("schedule-1")?.localSubmissionUuid)
      assertEquals(firstSubmissionUuid, formSubmissionApi.lastRequest?.localSubmissionUuid)
    }

  @Test
  fun `submitDraft persists a PENDING metadata row and the encrypted answers payload before attempting anything`() =
    runTest {
      seedSyncedSchedule()
      visitApi.exceptionToThrow = IOException("offline mid-call")

      submit()

      val json = requireNotNull(secureStore.getString(visitFormDraftPayloadKey("schedule-1")))
      val payload = visitFormDraftGson.fromJson(json, VisitFormDraftPayload::class.java)
      assertEquals(answers, payload.answers)
    }

  // --- CR-035 audit trail --------------------------------------------------------------------

  @Test
  fun `saveLocally (via submitDraft) writes a SAVED audit event before attempting submission`() = runTest {
    // A submission-step failure (or an offline device) must not stop the SAVED event from having
    // already been written — saveLocally() runs, and records it, before submitDraft() even checks
    // connectivity or calls either API.
    seedSyncedSchedule()
    visitApi.exceptionToThrow = IOException("no route to host")

    submit()

    assertEquals(
      listOf(FormAuditEventType.SAVED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("schedule-1", formAuditRepository.recordedEvents.single().subjectId)
    assertEquals("ANC_VISIT", formAuditRepository.recordedEvents.single().formCode)
  }

  @Test
  fun `SAVED event is written in both the online-success and offline-queued paths`() = runTest {
    seedSyncedSchedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val onlineResult = submit()

    assertEquals(VisitFormSubmitResult.Synced(), onlineResult)
    assertEquals(
      listOf(FormAuditEventType.SAVED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )

    // A second, independent draft that never gets a connection.
    seedSyncedSchedule("schedule-2")
    connectivityChecker.online = false
    val offlineResult = repository.submitDraft(
      localScheduleUuid = "schedule-2",
      formCode = "ANC_VISIT",
      formVersionId = "version-1",
      answers = answers,
      visitDate = LocalDate.of(2026, 8, 7),
    )

    assertEquals(VisitFormSubmitResult.QueuedOffline, offlineResult)
    assertEquals(
      listOf(FormAuditEventType.SAVED, FormAuditEventType.SAVED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals(
      setOf("schedule-1", "schedule-2"),
      formAuditRepository.recordedEvents.map { it.subjectId }.toSet(),
    )
  }

  // --- getUploadRecords / observeUploadRecords: Home screen "Forms Uploaded" sync-status modal ---

  @Test
  fun `getUploadRecords is empty when there are no drafts`() = runTest {
    assertTrue(repository.getUploadRecords().isEmpty())
  }

  @Test
  fun `getUploadRecords maps the draft's localScheduleUuid, formCode, status and createdAt`() = runTest {
    seedSyncedSchedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    submit()

    val record = repository.getUploadRecords().single()
    assertEquals("schedule-1", record.localBeneficiaryId)
    assertEquals("ANC_VISIT", record.formCode)
    assertEquals(EnrollmentSyncStatus.SYNCED, record.syncStatus)
    // Visit submissions have no duplicate-detection concept.
    assertEquals(null, record.pendingNewPregnancyBeneficiaryId)
  }

  @Test
  fun `observeUploadRecords emits the current drafts and re-emits when a status changes`() = runTest {
    seedSyncedSchedule()

    assertTrue(repository.observeUploadRecords().first().isEmpty())

    connectivityChecker.online = false
    submit()

    assertEquals(EnrollmentSyncStatus.PENDING, repository.observeUploadRecords().first().single().syncStatus)

    connectivityChecker.online = true
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()
    submit()

    assertEquals(EnrollmentSyncStatus.SYNCED, repository.observeUploadRecords().first().single().syncStatus)
  }

  // --- getAnswers: carries a visit's own answers forward to a later visit's computed fields -----
  // (bug fix 2026-09-04 -- see StaticVisitFormRepository.firstAncVisitWeightKg's doc for the
  // reported bug this method exists to fix: ANC_VISIT's Gestational Weight Gain always showing
  // "Auto-calculated" because there was no way to read a beneficiary's first ANC visit's own
  // weight answer back out.)

  @Test
  fun `getAnswers returns null when no draft was ever saved for this schedule`() = runTest {
    assertEquals(null, repository.getAnswers("never-saved-schedule"))
  }

  @Test
  fun `getAnswers returns the saved answers after an online-synced submit`() = runTest {
    seedSyncedSchedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    submit()

    assertEquals(answers, repository.getAnswers("schedule-1"))
  }

  @Test
  fun `getAnswers returns the saved answers for a draft still queued offline`() = runTest {
    seedSyncedSchedule()
    connectivityChecker.online = false

    submit()

    // The whole point: a later visit must be able to read this back even before it ever syncs.
    assertEquals(answers, repository.getAnswers("schedule-1"))
  }
}
