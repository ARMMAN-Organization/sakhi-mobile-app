package org.armman.sakhi.data.visitform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.delivery.FakeDeliverySessionDao
import org.armman.sakhi.data.delivery.RoomDeliverySessionRepository
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FakeFormsApi
import org.armman.sakhi.data.forms.VisitCodeFormResolver
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

/**
 * Covers the online-only visit-submit sequence at the [VisitFormSubmissionCoordinator] level:
 * `POST /visits` then `POST /forms/:formCode/submissions` (formCode resolved via CR-033/CR-034's
 * VisitCodeFormResolver), using the exact request/response shapes
 * the live backend expects/returns (see the ANC Visit Form storage API reference).
 */
class VisitFormSubmissionCoordinatorTest {

  private class FakeVisitApi : VisitApi {
    var response: Response<CreateVisitInstanceResponseDto>? = null
    var lastRequest: CreateVisitInstanceRequestDto? = null
    var callCount = 0

    override suspend fun createVisitInstance(
      request: CreateVisitInstanceRequestDto,
    ): Response<CreateVisitInstanceResponseDto> {
      callCount++
      lastRequest = request
      return response!!
    }
  }

  private lateinit var visitApi: FakeVisitApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var childFormDraftDao: FakeChildFormDraftDao
  private lateinit var coordinator: VisitFormSubmissionCoordinator

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
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
    childFormDraftDao = FakeChildFormDraftDao()
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
      formAuditRepository = formAuditRepository,
      deliverySessionRepository = deliverySessionRepository,
      childFormDraftDao = childFormDraftDao,
    )
  }

  /** CR-042 defense-in-depth gate: seeds a registered-child draft so an NN submission for
   * [localBeneficiaryId] passes [VisitFormSubmissionCoordinator]'s
   * NoRegisteredChildForNnVisit check — the real-world equivalent of Child Registration having
   * actually been completed for this child before its NN visit is submitted. */
  private suspend fun seedRegisteredChild(localBeneficiaryId: String = "ben-1") {
    childFormDraftDao.upsert(
      ChildFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = "CHILD_REGISTRATION",
        formVersionId = "child-reg-version-1",
        localSubmissionUuid = "child-reg-submission-$localBeneficiaryId",
        syncStatus = EnrollmentSyncStatus.SYNCED,
        createdAtEpochMillis = 1_000L,
        lastAttemptAtEpochMillis = 1_000L,
        retryCount = 0,
        remoteBeneficiaryId = localBeneficiaryId,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
  }

  private suspend fun syncedSchedule(localScheduleUuid: String = "schedule-1") {
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

  private fun errorResponse(code: Int, body: String) =
    Response.error<CreateVisitInstanceResponseDto>(code, body.toResponseBody("application/json".toMediaType()))

  @Test
  fun `happy path calls both APIs in order and marks the schedule completed`() = runTest {
    syncedSchedule()
    visitApi.response = successfulVisitResponse(id = "server-visit-42")
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(singleValues = mapOf("weight_kg" to "58")),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.isSuccess)
    assertEquals(1, visitApi.callCount)
    assertEquals(1, formSubmissionApi.callCount)
    assertEquals("server-schedule-1", visitApi.lastRequest?.scheduleId)
    assertEquals("server-beneficiary-1", visitApi.lastRequest?.beneficiaryId)
    assertEquals("sakhi-uuid-1", visitApi.lastRequest?.sakhiId)
    assertEquals("lookup-visit-status-completed", visitApi.lastRequest?.statusLookupValueId)
    // The submissions call must carry the id POST /visits returned, not the schedule id.
    assertEquals("server-visit-42", formSubmissionApi.lastRequest?.visitId)
    assertEquals("server-beneficiary-1", formSubmissionApi.lastRequest?.beneficiaryId)
    assertEquals("version-v1", formSubmissionApi.lastRequest?.formVersionId)

    val updated = scheduleRepository.getByLocalScheduleUuid("schedule-1")
    assertEquals(VisitScheduleStatus.COMPLETED, updated?.status)
  }

  @Test
  fun `happy path records a SUBMITTED audit event`() = runTest {
    syncedSchedule()
    visitApi.response = successfulVisitResponse(id = "server-visit-42")
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      listOf(FormAuditEventType.SUBMITTED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("schedule-1", formAuditRepository.recordedEvents.single().subjectId)
    assertEquals("ANC_VISIT", formAuditRepository.recordedEvents.single().formCode)
  }

  @Test
  fun `form submission failure does not record a SUBMITTED audit event`() = runTest {
    syncedSchedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = Response.error(
      422,
      "{\"success\":false,\"message\":\"Submission failed form validation.\"}"
        .toResponseBody("application/json".toMediaType()),
    )

    coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(formAuditRepository.recordedEvents.isEmpty())
  }

  @Test
  fun `fails without calling either API when the schedule has not synced`() = runTest {
    scheduleRepository.saveGenerated(
      listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = null)),
    )

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.NotYetSynced)
    assertEquals(0, visitApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `fails when the beneficiary has synced but the schedule itself has not`() = runTest {
    scheduleRepository.saveGenerated(
      listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = "server-beneficiary-1")),
    )

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    val error = result.exceptionOrNull()
    assertTrue(error is VisitFormSubmissionException.NotYetSynced)
    assertEquals(false, (error as VisitFormSubmissionException.NotYetSynced).scheduleSynced)
    assertEquals(true, error.beneficiarySynced)
  }

  @Test
  fun `fails with ScheduleNotFound for an unknown localScheduleUuid`() = runTest {
    val result = coordinator.submit(
      localScheduleUuid = "does-not-exist",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.ScheduleNotFound)
  }

  @Test
  fun `fails with VisitStatusLookupUnavailable when VISIT_STATUS has no COMPLETED value`() = runTest {
    syncedSchedule()
    lookupRepository.valuesByCategory = mutableMapOf("VISIT_STATUS" to emptyList())

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.VisitStatusLookupUnavailable)
    assertEquals(0, visitApi.callCount)
  }

  @Test
  fun `fails with NoActiveSession when nothing is signed in`() = runTest {
    syncedSchedule()
    val loggedOutSessionStore = SessionStore(FakeSecureKeyValueStore())
    val loggedOutCoordinator = VisitFormSubmissionCoordinator(
      visitApi = visitApi,
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      lookupRepository = lookupRepository,
      sessionStore = loggedOutSessionStore,
      visitCodeFormResolver = VisitCodeFormResolver(FakeFormsApi(), FakeSecureKeyValueStore()),
      formAuditRepository = FakeFormAuditRepository(),
      deliverySessionRepository = deliverySessionRepository,
      childFormDraftDao = childFormDraftDao,
    )

    val result = loggedOutCoordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.NoActiveSession)
  }

  @Test
  fun `does not call the submissions endpoint when POST visits fails`() = runTest {
    syncedSchedule()
    visitApi.response = errorResponse(500, "{\"success\":false,\"message\":\"boom\"}")

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.VisitInstanceCreationFailed)
    assertEquals(1, visitApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
    // The schedule must not flip to COMPLETED on a failed submit.
    assertEquals(VisitScheduleStatus.GENERATED, scheduleRepository.getByLocalScheduleUuid("schedule-1")?.status)
  }

  @Test
  fun `does not mark the schedule completed when the form submission fails`() = runTest {
    syncedSchedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = Response.error(
      422,
      "{\"success\":false,\"message\":\"Submission failed form validation.\"}"
        .toResponseBody("application/json".toMediaType()),
    )

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.FormSubmissionFailed)
    assertEquals(VisitScheduleStatus.GENERATED, scheduleRepository.getByLocalScheduleUuid("schedule-1")?.status)
  }

  @Test
  fun `submit() failure before reaching form submission (NotYetSynced or VisitInstanceCreationFailed) does NOT write a SUBMITTED event`() =
    runTest {
      // NotYetSynced — fails before POST /visits is even attempted.
      scheduleRepository.saveGenerated(
        listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = null)),
      )
      val notYetSyncedResult = coordinator.submit(
        localScheduleUuid = "schedule-1",
        formVersionId = "version-v1",
        answers = FormAnswers(),
        visitDate = LocalDate.of(2026, 8, 7),
        localSubmissionUuid = "test-submission-uuid",
      )
      assertTrue(notYetSyncedResult.exceptionOrNull() is VisitFormSubmissionException.NotYetSynced)
      assertTrue(formAuditRepository.recordedEvents.isEmpty())

      // VisitInstanceCreationFailed — POST /visits itself fails, so form submission is never reached.
      syncedSchedule()
      visitApi.response = errorResponse(500, "{\"success\":false,\"message\":\"boom\"}")
      val visitCreationFailedResult = coordinator.submit(
        localScheduleUuid = "schedule-1",
        formVersionId = "version-v1",
        answers = FormAnswers(),
        visitDate = LocalDate.of(2026, 8, 7),
        localSubmissionUuid = "test-submission-uuid",
      )
      assertTrue(visitCreationFailedResult.exceptionOrNull() is VisitFormSubmissionException.VisitInstanceCreationFailed)
      assertTrue(formAuditRepository.recordedEvents.isEmpty())
    }

  // --- CR-042 delivery-session step advancement -----------------------------------------------

  private val deliveryFormFilledOn = LocalDate.of(2026, 8, 7)

  private suspend fun seedDeliverySession(
    localSessionUuid: String = "delivery-session-1",
    localBeneficiaryId: String = "ben-1",
    step: DeliverySessionStep,
    deliveryFormFilledOnDate: LocalDate? = deliveryFormFilledOn,
  ) {
    deliverySessionRepository.save(
      DeliverySessionEntity(
        localSessionUuid = localSessionUuid,
        localBeneficiaryId = localBeneficiaryId,
        step = step,
        deliverySubmissionLocalUuid = "delivery-sub-1",
        deliveryFormFilledOn = deliveryFormFilledOnDate,
        createdAtEpochMillis = 1_755_000_000_000L,
        updatedAtEpochMillis = 1_755_000_000_000L,
      ),
    )
  }

  private suspend fun seedPp1Schedule(
    localScheduleUuid: String = "pp1-schedule",
    localBeneficiaryId: String = "ben-1",
  ) {
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          localScheduleUuid,
          localBeneficiaryId = localBeneficiaryId,
          visitCode = "PP1",
          visitType = VisitCodeType.PP,
          sequenceNo = 1,
          serverScheduleId = "server-$localScheduleUuid",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  private suspend fun seedNnSchedule(
    localScheduleUuid: String,
    sequenceNo: Int,
    localBeneficiaryId: String = "ben-1",
    scheduledDate: LocalDate = deliveryFormFilledOn,
  ) {
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          localScheduleUuid,
          localBeneficiaryId = localBeneficiaryId,
          visitCode = "NN$sequenceNo",
          visitType = VisitCodeType.NN,
          sequenceNo = sequenceNo,
          scheduledDate = scheduledDate,
          serverScheduleId = "server-$localScheduleUuid",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  @Test
  fun `submit() advances a PP1 delivery session to NN when a same-session NN visit exists`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.PP1)
    seedPp1Schedule()
    // The same-session NN1 row — GENERATED, scheduled on the delivery form's own fill date, not
    // yet synced (it doesn't need to be: sameSessionNnVisit only cares about the schedule, and
    // this PP1 submission never touches this row directly).
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "nn1-schedule",
          localBeneficiaryId = "ben-1",
          visitCode = "NN1",
          visitType = VisitCodeType.NN,
          sequenceNo = 1,
          scheduledDate = deliveryFormFilledOn,
        ),
      ),
    )
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = coordinator.submit(
      localScheduleUuid = "pp1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.isSuccess)
    assertEquals(
      DeliverySessionStep.NN,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() advances a PP1 delivery session straight to DONE when no same-session NN visit exists`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.PP1)
    seedPp1Schedule()
    // No NN schedule row at all — the neonatal window had already closed (SR-NN-01's "after Day
    // 28" case) by the time the delivery form was filed.
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "pp1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      DeliverySessionStep.DONE,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() ignores an NN row that is not this session's own same-session visit`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.PP1)
    seedPp1Schedule()
    // Scenario A: NN1 opens same-session, but NN2 (Day 15) belongs to the regular tracker, not
    // this delivery session. Only NN1's own scheduledDate matches deliveryFormFilledOn.
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "nn2-schedule",
          localBeneficiaryId = "ben-1",
          visitCode = "NN2",
          visitType = VisitCodeType.NN,
          sequenceNo = 2,
          scheduledDate = deliveryFormFilledOn.plusDays(15),
        ),
      ),
    )
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "pp1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      DeliverySessionStep.DONE,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() advances an NN delivery session to DONE when NN1 is submitted`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.NN)
    seedNnSchedule("nn1-schedule", sequenceNo = 1)
    seedRegisteredChild()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "nn1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      DeliverySessionStep.DONE,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() advances an NN delivery session to DONE when NN2 is submitted instead`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.NN)
    seedNnSchedule("nn2-schedule", sequenceNo = 2)
    seedRegisteredChild()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "nn2-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      DeliverySessionStep.DONE,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() rejects an NN visit with no registered child behind it`() = runTest {
    // CR-042 defense in depth: an NN schedule row with no ChildFormDraftEntity behind it can only
    // be a stale row from before the CR-042 defect fix shipped (wrongly anchored to the mother's
    // own id by the old code) — this must never be allowed to complete.
    seedDeliverySession(step = DeliverySessionStep.NN)
    seedNnSchedule("nn1-schedule", sequenceNo = 1)
    // Deliberately no seedRegisteredChild() call.
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = coordinator.submit(
      localScheduleUuid = "nn1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.NoRegisteredChildForNnVisit)
    assertEquals(0, visitApi.callCount)
  }

  @Test
  fun `submit() does not touch a delivery session sitting at a different step`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.CHILD_REGISTRATION)
    seedPp1Schedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "pp1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      DeliverySessionStep.CHILD_REGISTRATION,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() is a no-op when the beneficiary has no active delivery session`() = runTest {
    // No seedDeliverySession() call at all.
    seedPp1Schedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = coordinator.submit(
      localScheduleUuid = "pp1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.isSuccess)
    assertNull(deliverySessionRepository.getActiveForBeneficiary("ben-1"))
  }

  @Test
  fun `submit() ignores a regular ANC visit even while a delivery session is active`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.PP1)
    syncedSchedule("schedule-1") // default ANC1, localBeneficiaryId "ben-1"
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertEquals(
      DeliverySessionStep.PP1,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `submit() falls back to DONE from PP1 when deliveryFormFilledOn is null on a legacy session row`() = runTest {
    seedDeliverySession(step = DeliverySessionStep.PP1, deliveryFormFilledOnDate = null)
    seedPp1Schedule()
    // Even with a matching NN row present, a null deliveryFormFilledOn means sameSessionNnVisit
    // cannot be resolved, so this must not throw and must not advance to NN.
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "nn1-schedule",
          localBeneficiaryId = "ben-1",
          visitCode = "NN1",
          visitType = VisitCodeType.NN,
          sequenceNo = 1,
          scheduledDate = deliveryFormFilledOn,
        ),
      ),
    )
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = coordinator.submit(
      localScheduleUuid = "pp1-schedule",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = deliveryFormFilledOn,
      localSubmissionUuid = "test-submission-uuid",
    )

    assertTrue(result.isSuccess)
    assertEquals(
      DeliverySessionStep.DONE,
      deliverySessionRepository.getBySessionUuid("delivery-session-1")?.step,
    )
  }

  @Test
  fun `passes the caller-supplied localSubmissionUuid straight through to the submission request`() = runTest {
    syncedSchedule()
    visitApi.response = successfulVisitResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
      localSubmissionUuid = "caller-minted-uuid-1",
    )

    assertEquals("caller-minted-uuid-1", formSubmissionApi.lastRequest?.localSubmissionUuid)
  }
}
