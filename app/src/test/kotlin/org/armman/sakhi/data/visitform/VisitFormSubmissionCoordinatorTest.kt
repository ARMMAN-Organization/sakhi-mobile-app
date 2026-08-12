package org.armman.sakhi.data.visitform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
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
 * `POST /visits` then `POST /forms/ANC_VISIT/submissions`, using the exact request/response shapes
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
    coordinator = VisitFormSubmissionCoordinator(
      visitApi = visitApi,
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      lookupRepository = lookupRepository,
      sessionStore = sessionStore,
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
  fun `fails without calling either API when the schedule has not synced`() = runTest {
    scheduleRepository.saveGenerated(
      listOf(schedule("schedule-1", serverScheduleId = null, serverBeneficiaryId = null)),
    )

    val result = coordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
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
    )

    val result = loggedOutCoordinator.submit(
      localScheduleUuid = "schedule-1",
      formVersionId = "version-v1",
      answers = FormAnswers(),
      visitDate = LocalDate.of(2026, 8, 7),
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
    )

    assertTrue(result.exceptionOrNull() is VisitFormSubmissionException.FormSubmissionFailed)
    assertEquals(VisitScheduleStatus.GENERATED, scheduleRepository.getByLocalScheduleUuid("schedule-1")?.status)
  }
}
