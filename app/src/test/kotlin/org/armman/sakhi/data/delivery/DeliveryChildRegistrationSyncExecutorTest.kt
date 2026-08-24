package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** Covers [DeliveryChildRegistrationSyncExecutor] — the CR-042 delivery-session twin of
 * [org.armman.sakhi.data.childregistration.ChildFormSyncExecutorTest]'s IOException/retry
 * coverage, applied to this queue's own draft/coordinator pair. */
class DeliveryChildRegistrationSyncExecutorTest {

  private lateinit var dao: FakeDeliveryChildRegistrationDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var coordinator: DeliveryChildRegistrationSubmissionCoordinator
  private lateinit var executor: DeliveryChildRegistrationSyncExecutor

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
    dao = FakeDeliveryChildRegistrationDraftDao()
    secureStore = FakeSecureKeyValueStore()
    formSubmissionApi = FakeFormSubmissionApi()
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)

    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val visitScheduleRepositoryForChildRegistration = org.armman.sakhi.data.schedule.RoomVisitScheduleRepository(
      org.armman.sakhi.data.schedule.FakeVisitScheduleDao(),
    )
    val visitScheduleCoordinatorForChildRegistration = org.armman.sakhi.data.schedule.VisitScheduleCoordinator(
      repository = visitScheduleRepositoryForChildRegistration,
      ancGenerator = org.armman.sakhi.data.schedule.AncScheduleGenerator(org.armman.sakhi.data.schedule.HardcodedRuleSource()),
      ppGenerator = org.armman.sakhi.data.schedule.PpScheduleGenerator(org.armman.sakhi.data.schedule.HardcodedRuleSource()),
      nnGenerator = org.armman.sakhi.data.schedule.NnScheduleGenerator(org.armman.sakhi.data.schedule.HardcodedRuleSource()),
      incGenerator = org.armman.sakhi.data.schedule.IncScheduleGenerator(org.armman.sakhi.data.schedule.HardcodedRuleSource()),
      ccvGenerator = org.armman.sakhi.data.schedule.CcvScheduleGenerator(org.armman.sakhi.data.schedule.HardcodedRuleSource()),
    )
    val visitScheduleSyncExecutorForChildRegistration = org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor(
      visitScheduleRepositoryForChildRegistration,
      org.armman.sakhi.data.schedule.FakeVisitScheduleApi(),
      org.armman.sakhi.data.visitform.FakeVisitFormSyncScheduler(),
    )
    coordinator = DeliveryChildRegistrationSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = sessionStore,
      formAuditRepository = FakeFormAuditRepository(),
      childFormDraftDao = org.armman.sakhi.data.childregistration.FakeChildFormDraftDao(),
      secureStore = secureStore,
      visitScheduleCoordinator = visitScheduleCoordinatorForChildRegistration,
      visitScheduleRepository = visitScheduleRepositoryForChildRegistration,
      visitScheduleSyncExecutor = visitScheduleSyncExecutorForChildRegistration,
    )
    executor = DeliveryChildRegistrationSyncExecutor(dao, secureStore, coordinator)
  }

  private suspend fun seedSession(localSessionUuid: String = "session-1") {
    deliverySessionRepository.save(
      DeliverySessionEntity(
        localSessionUuid = localSessionUuid,
        localBeneficiaryId = "mother-1",
        step = DeliverySessionStep.CHILD_REGISTRATION,
        deliverySubmissionLocalUuid = "delivery-submission-1",
        child1BeneficiaryId = "child-a",
        child2BeneficiaryId = null,
        child3BeneficiaryId = null,
        nextChildIndexToRegister = 0,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
      ),
    )
  }

  private suspend fun seedPendingDraft(
    localSubmissionUuid: String = "child-reg-submission-1",
    localSessionUuid: String = "session-1",
    syncStatus: EnrollmentSyncStatus = EnrollmentSyncStatus.PENDING,
  ) {
    secureStore.putString(
      deliveryChildRegistrationDraftPayloadKey(localSubmissionUuid),
      deliveryChildRegistrationDraftGson.toJson(
        DeliveryChildRegistrationDraftPayload(
          answers = FormAnswers(singleValues = mapOf("name_of_the_child" to "Test Baby")),
        ),
      ),
    )
    dao.upsert(
      DeliveryChildRegistrationDraftEntity(
        localSubmissionUuid = localSubmissionUuid,
        localSessionUuid = localSessionUuid,
        serverBeneficiaryId = "child-a",
        formVersionId = "child-reg-version-1",
        syncStatus = syncStatus,
        createdAtEpochMillis = 1_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        lastErrorMessage = null,
      ),
    )
  }

  private fun successResponse() = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = "server-sub-1")),
  )

  @Test
  fun `run() with no pending drafts completes without touching the network`() = runTest {
    val outcome = executor.run()
    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `run() syncs a pending draft and advances the session`() = runTest {
    seedSession()
    seedPendingDraft()
    formSubmissionApi.response = successResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalSubmissionUuid("child-reg-submission-1")?.syncStatus)
    assertEquals(DeliverySessionStep.PP1, deliverySessionRepository.getBySessionUuid("session-1")!!.step)
  }

  @Test
  fun `run() reclaims a draft stuck in SYNCING before processing it`() = runTest {
    seedSession()
    seedPendingDraft(syncStatus = EnrollmentSyncStatus.SYNCING)
    formSubmissionApi.response = successResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalSubmissionUuid("child-reg-submission-1")?.syncStatus)
  }

  @Test
  fun `runOne() short-circuits to Synced without a network call when already SYNCED`() = runTest {
    seedSession()
    seedPendingDraft(syncStatus = EnrollmentSyncStatus.SYNCED)

    val result = executor.runOne("child-reg-submission-1")

    assertEquals(DeliveryChildRegistrationSyncItemResult.Synced, result)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne() returns null when no draft exists for the given id`() = runTest {
    val result = executor.runOne("no-such-draft")
    assertNull(result)
  }

  @Test
  fun `an IOException leaves the draft PENDING and reports Retryable`() = runTest {
    seedSession()
    seedPendingDraft()
    formSubmissionApi.exceptionToThrow = IOException("no route to host")

    val result = executor.runOne("child-reg-submission-1")

    assertTrue(result is DeliveryChildRegistrationSyncItemResult.Retryable)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalSubmissionUuid("child-reg-submission-1")?.syncStatus)
  }

  @Test
  fun `run() reports RETRYABLE_FAILURE when any draft hits a transient error`() = runTest {
    seedSession()
    seedPendingDraft()
    formSubmissionApi.exceptionToThrow = IOException("no route to host")

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
  }

  @Test
  fun `a non-2xx response marks the draft FAILED with an incremented retry count`() = runTest {
    seedSession()
    seedPendingDraft()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = executor.runOne("child-reg-submission-1")

    assertTrue(result is DeliveryChildRegistrationSyncItemResult.Failed)
    val draft = dao.getByLocalSubmissionUuid("child-reg-submission-1")
    assertEquals(EnrollmentSyncStatus.FAILED, draft?.syncStatus)
    assertEquals(1, draft?.retryCount)
  }

  @Test
  fun `runOne() fails when the local payload is missing`() = runTest {
    seedSession()
    // Upsert the Room row directly without ever writing a payload to the secure store.
    dao.upsert(
      DeliveryChildRegistrationDraftEntity(
        localSubmissionUuid = "no-payload-1",
        localSessionUuid = "session-1",
        serverBeneficiaryId = "child-a",
        formVersionId = "child-reg-version-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 1_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        lastErrorMessage = null,
      ),
    )

    val result = executor.runOne("no-payload-1")

    assertTrue(result is DeliveryChildRegistrationSyncItemResult.Failed)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalSubmissionUuid("no-payload-1")?.syncStatus)
  }
}
