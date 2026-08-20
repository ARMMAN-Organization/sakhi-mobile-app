package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.CcvScheduleGenerator
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.IncScheduleGenerator
import org.armman.sakhi.data.schedule.NnScheduleGenerator
import org.armman.sakhi.data.schedule.PpScheduleGenerator
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import retrofit2.Response

/**
 * Covers [DeliveryFormSubmissionCoordinator] — CR-042 Chunk A. Exercises the real
 * [VisitScheduleCoordinator] (Hardcoded-rules path, same as [org.armman.sakhi.data.schedule
 * .VisitScheduleCoordinatorTest]'s non-GoRules cases) rather than a fake, so a passing test here
 * also proves the [DeliverySessionEntity] write and the `onDeliveryRecorded` call actually compose
 * correctly, not just that they were called.
 */
class DeliveryFormSubmissionCoordinatorTest {

  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var visitScheduleCoordinator: VisitScheduleCoordinator
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var coordinator: DeliveryFormSubmissionCoordinator

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

  private val deliveryDate = LocalDate.of(2026, 8, 1)
  private val answers = FormAnswers(singleValues = mapOf("delivery_outcome" to "live_birth"))

  @Before
  fun setUp() {
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    val rules = HardcodedRuleSource()
    visitScheduleCoordinator = VisitScheduleCoordinator(
      repository = scheduleRepository,
      ancGenerator = AncScheduleGenerator(rules),
      ppGenerator = PpScheduleGenerator(rules),
      nnGenerator = NnScheduleGenerator(rules),
      incGenerator = IncScheduleGenerator(rules),
      ccvGenerator = CcvScheduleGenerator(rules),
    )
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    coordinator = DeliveryFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      visitScheduleCoordinator = visitScheduleCoordinator,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
    )
  }

  private suspend fun seedSyncedMother(localBeneficiaryId: String = "mother-1") {
    // An ANC schedule row already synced — this is what NotYetSynced/serverBeneficiaryId
    // resolution reuses, same as AdHocFormSubmissionCoordinatorTest's seedSyncedBeneficiary.
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "anc-schedule-$localBeneficiaryId",
          localBeneficiaryId = localBeneficiaryId,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  private fun successResponse(childIds: List<String>? = null) = Response.success(
    CreateSubmissionResponseDto(
      success = true,
      message = "OK",
      data = SubmissionResponseData(id = "server-sub-1", childBeneficiaryIds = childIds),
    ),
  )

  private suspend fun submit(
    localBeneficiaryId: String = "mother-1",
    localSessionUuid: String = "session-1",
    localSubmissionUuid: String = "submission-1",
  ) = coordinator.submit(
    localSubmissionUuid = localSubmissionUuid,
    localSessionUuid = localSessionUuid,
    localBeneficiaryId = localBeneficiaryId,
    formVersionId = "version-1",
    answers = answers,
    deliveryDate = deliveryDate,
    deliveryFormFilledOn = deliveryDate,
  )

  @Test
  fun `submit() with two live children advances the session to CHILD_REGISTRATION and stores both ids`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(listOf("child-a", "child-b"))

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals(listOf("child-a", "child-b"), result.getOrNull())

    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertNotNull(sessionRow)
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, sessionRow!!.step)
    assertEquals("child-a", sessionRow.child1BeneficiaryId)
    assertEquals("child-b", sessionRow.child2BeneficiaryId)
    assertNull(sessionRow.child3BeneficiaryId)
    assertEquals("submission-1", sessionRow.deliverySubmissionLocalUuid)
  }

  @Test
  fun `submit() persists deliveryFormFilledOn onto the session row`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    submit()

    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertEquals(deliveryDate, sessionRow?.deliveryFormFilledOn)
  }

  @Test
  fun `submit() with null childBeneficiaryIds (no live birth) advances straight to PP1`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())

    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertEquals(DeliverySessionStep.PP1, sessionRow!!.step)
    assertNull(sessionRow.child1BeneficiaryId)
  }

  @Test
  fun `submit() with an empty (non-null) childBeneficiaryIds list is treated the same as null`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = emptyList())

    val result = submit()

    assertTrue(result.isSuccess)
    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertEquals(DeliverySessionStep.PP1, sessionRow!!.step)
  }

  @Test
  fun `submit() success generates the PP series via onDeliveryRecorded`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    submit()

    val ppRows = scheduleRepository.getForBeneficiary("mother-1")
      .filter { it.visitType == org.armman.sakhi.data.schedule.VisitCodeType.PP }
    assertTrue("expected a PP series to be generated", ppRows.isNotEmpty())
  }

  @Test
  fun `submit() success records a SUBMITTED audit event`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    submit(localSubmissionUuid = "submission-audit-1")

    assertTrue(
      formAuditRepository.recordedEvents.any {
        it.subjectId == "submission-audit-1" &&
          it.formCode == "DELIVERY_VISIT" &&
          it.eventType == org.armman.sakhi.data.audit.FormAuditEventType.SUBMITTED
      },
    )
  }

  @Test
  fun `submit() fails with NotYetSynced when the mother has no server beneficiary id yet`() = runTest {
    // No seedSyncedMother() — no schedule row exists for this beneficiary at all.
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NotYetSynced)
    assertNull(deliverySessionRepository.getBySessionUuid("session-1"))
  }

  @Test
  fun `submit() fails with FormSubmissionFailed on a non-2xx response and writes no session row`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submit()

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(error is DeliveryFormSubmissionException.FormSubmissionFailed)
    assertEquals(422, (error as DeliveryFormSubmissionException.FormSubmissionFailed).httpCode)
    assertNull(deliverySessionRepository.getBySessionUuid("session-1"))
  }

  @Test
  fun `submit() fails with NoSubmissionIdReturned when the response body has no data`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = Response.success(
      CreateSubmissionResponseDto(success = true, message = "OK", data = null),
    )

    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NoSubmissionIdReturned)
  }

  @Test
  fun `submit() fails with NoActiveSession when no Sakhi is signed in`() = runTest {
    val loggedOutSessionStore = SessionStore(FakeSecureKeyValueStore())
    val loggedOutCoordinator = DeliveryFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      visitScheduleCoordinator = visitScheduleCoordinator,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = loggedOutSessionStore,
      formAuditRepository = formAuditRepository,
    )
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    val result = loggedOutCoordinator.submit(
      localSubmissionUuid = "submission-1",
      localSessionUuid = "session-1",
      localBeneficiaryId = "mother-1",
      formVersionId = "version-1",
      answers = answers,
      deliveryDate = deliveryDate,
      deliveryFormFilledOn = deliveryDate,
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NoActiveSession)
  }

  @Test
  fun `a resumed retry on the same session preserves createdAtEpochMillis`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = listOf("child-a"))
    submit(localSessionUuid = "session-1", localSubmissionUuid = "submission-1")
    val firstRow = deliverySessionRepository.getBySessionUuid("session-1")!!

    // Simulate a later retry of the same submission (e.g. a background sync re-attempt after the
    // session row already exists from an earlier partial success).
    submit(localSessionUuid = "session-1", localSubmissionUuid = "submission-1")
    val secondRow = deliverySessionRepository.getBySessionUuid("session-1")!!

    assertEquals(firstRow.createdAtEpochMillis, secondRow.createdAtEpochMillis)
  }
}
