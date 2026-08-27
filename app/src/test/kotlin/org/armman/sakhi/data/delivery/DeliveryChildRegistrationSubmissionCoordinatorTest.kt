package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Covers [DeliveryChildRegistrationSubmissionCoordinator] — CR-042's CHILD_REGISTRATION-for-an-
 * already-existing-child path. Deliberately does NOT exercise
 * [org.armman.sakhi.data.childregistration.ChildRegistrationSubmissionCoordinator] (the standalone
 * flow) at all — this coordinator never calls `POST /beneficiaries`, which is the entire point.
 */
class DeliveryChildRegistrationSubmissionCoordinatorTest {

  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var coordinator: DeliveryChildRegistrationSubmissionCoordinator

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

  private val answers = FormAnswers(singleValues = mapOf("name_of_the_child" to "Test Baby"))

  @Before
  fun setUp() {
    formSubmissionApi = FakeFormSubmissionApi()
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    coordinator = DeliveryChildRegistrationSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
    )
  }

  private suspend fun seedSession(
    localSessionUuid: String = "session-1",
    child1: String? = "child-a",
    child2: String? = null,
    child3: String? = null,
    nextChildIndexToRegister: Int = 0,
  ): DeliverySessionEntity {
    val entity = DeliverySessionEntity(
      localSessionUuid = localSessionUuid,
      localBeneficiaryId = "mother-1",
      step = DeliverySessionStep.CHILD_REGISTRATION,
      deliverySubmissionLocalUuid = "delivery-submission-1",
      child1BeneficiaryId = child1,
      child2BeneficiaryId = child2,
      child3BeneficiaryId = child3,
      nextChildIndexToRegister = nextChildIndexToRegister,
      createdAtEpochMillis = 1_000L,
      updatedAtEpochMillis = 1_000L,
    )
    deliverySessionRepository.save(entity)
    return entity
  }

  private fun successResponse() = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = "server-sub-1")),
  )

  private suspend fun submit(
    localSessionUuid: String = "session-1",
    serverBeneficiaryId: String = "child-a",
    localSubmissionUuid: String = "child-reg-submission-1",
  ) = coordinator.submit(
    localSessionUuid = localSessionUuid,
    serverBeneficiaryId = serverBeneficiaryId,
    localSubmissionUuid = localSubmissionUuid,
    formVersionId = "child-reg-version-1",
    answers = answers,
  )

  @Test
  fun `submit() never calls a beneficiary-creation endpoint - only the form submission API`() = runTest {
    seedSession()
    formSubmissionApi.response = successResponse()

    val result = submit()

    assertTrue(result.isSuccess)
    // FakeFormSubmissionApi only models createSubmission - there is no beneficiary-creation call
    // site anywhere in this coordinator to accidentally invoke, which is the whole point of this
    // class existing separately from the standalone ChildRegistrationSubmissionCoordinator.
    assertEquals("child-reg-version-1", formSubmissionApi.lastRequest?.formVersionId)
    assertEquals("child-a", formSubmissionApi.lastRequest?.beneficiaryId)
  }

  @Test
  fun `submit() for the only child advances the session straight to PP1`() = runTest {
    seedSession(child1 = "child-a", child2 = null, child3 = null)
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-a")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.PP1, row.step)
    assertEquals(1, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() for the first of twins stays at CHILD_REGISTRATION for the second`() = runTest {
    seedSession(child1 = "child-a", child2 = "child-b", nextChildIndexToRegister = 0)
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-a")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, row.step)
    assertEquals(1, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() for the second of twins advances to PP1`() = runTest {
    seedSession(child1 = "child-a", child2 = "child-b", nextChildIndexToRegister = 1)
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-b")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.PP1, row.step)
    assertEquals(2, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() success records a SUBMITTED audit event`() = runTest {
    seedSession()
    formSubmissionApi.response = successResponse()

    submit(localSubmissionUuid = "audit-submission-1")

    assertTrue(
      formAuditRepository.recordedEvents.any {
        it.subjectId == "audit-submission-1" &&
          it.formCode == "CHILD_REGISTRATION" &&
          it.eventType == FormAuditEventType.SUBMITTED
      },
    )
  }

  @Test
  fun `submit() fails with FormSubmissionFailed on a non-2xx response and does not advance the session`() = runTest {
    seedSession()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submit()

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(error is DeliveryChildRegistrationSubmissionException.FormSubmissionFailed)
    assertEquals(422, (error as DeliveryChildRegistrationSubmissionException.FormSubmissionFailed).httpCode)
    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, row.step)
    assertEquals(0, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() fails with NoActiveSession when no Sakhi is signed in`() = runTest {
    val loggedOutSessionStore = SessionStore(FakeSecureKeyValueStore())
    val loggedOutCoordinator = DeliveryChildRegistrationSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = loggedOutSessionStore,
      formAuditRepository = formAuditRepository,
    )
    seedSession()
    formSubmissionApi.response = successResponse()

    val result = loggedOutCoordinator.submit(
      localSessionUuid = "session-1",
      serverBeneficiaryId = "child-a",
      localSubmissionUuid = "child-reg-submission-1",
      formVersionId = "child-reg-version-1",
      answers = answers,
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryChildRegistrationSubmissionException.NoActiveSession)
  }

  @Test
  fun `submit() is a no-op on the session row when no session exists for localSessionUuid`() = runTest {
    // Deliberately no seedSession() call.
    formSubmissionApi.response = successResponse()

    val result = submit(localSessionUuid = "missing-session")

    assertTrue(result.isSuccess)
    assertEquals(null, deliverySessionRepository.getBySessionUuid("missing-session"))
  }
}
