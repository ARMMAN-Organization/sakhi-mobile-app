package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
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
 * Covers [RoomDeliveryChildRegistrationDraftRepository] — the offline-save-then-online-attempt
 * contract every queue in this app shares, exercised here against the real
 * [DeliveryChildRegistrationSubmissionCoordinator]/[DeliveryChildRegistrationSyncExecutor] rather
 * than a fake, so a passing test also proves the whole chain composes (draft save -> immediate
 * sync attempt -> session advance), not just that each piece was called.
 */
class RoomDeliveryChildRegistrationDraftRepositoryTest {

  private lateinit var dao: FakeDeliveryChildRegistrationDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var repository: RoomDeliveryChildRegistrationDraftRepository

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
    dao = FakeDeliveryChildRegistrationDraftDao()
    secureStore = FakeSecureKeyValueStore()
    connectivityChecker = FakeConnectivityChecker(online = true)
    formSubmissionApi = FakeFormSubmissionApi()
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)

    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val formAuditRepository = FakeFormAuditRepository()
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
    val coordinator = DeliveryChildRegistrationSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
      childFormDraftDao = org.armman.sakhi.data.childregistration.FakeChildFormDraftDao(),
      secureStore = secureStore,
      visitScheduleCoordinator = visitScheduleCoordinatorForChildRegistration,
      visitScheduleRepository = visitScheduleRepositoryForChildRegistration,
      visitScheduleSyncExecutor = visitScheduleSyncExecutorForChildRegistration,
    )
    val syncExecutor = DeliveryChildRegistrationSyncExecutor(dao, secureStore, coordinator)
    repository = RoomDeliveryChildRegistrationDraftRepository(
      dao = dao,
      secureStore = secureStore,
      connectivityChecker = connectivityChecker,
      syncExecutor = syncExecutor,
      formAuditRepository = formAuditRepository,
    )
  }

  private suspend fun seedSession(localSessionUuid: String = "session-1", child1: String = "child-a") {
    deliverySessionRepository.save(
      DeliverySessionEntity(
        localSessionUuid = localSessionUuid,
        localBeneficiaryId = "mother-1",
        step = DeliverySessionStep.CHILD_REGISTRATION,
        deliverySubmissionLocalUuid = "delivery-submission-1",
        child1BeneficiaryId = child1,
        child2BeneficiaryId = null,
        child3BeneficiaryId = null,
        nextChildIndexToRegister = 0,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
      ),
    )
  }

  private fun successResponse() = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = "server-sub-1")),
  )

  private suspend fun submitDraft(
    localSubmissionUuid: String = "child-reg-submission-1",
    localSessionUuid: String = "session-1",
    serverBeneficiaryId: String = "child-a",
  ) = repository.submitDraft(
    localSubmissionUuid = localSubmissionUuid,
    localSessionUuid = localSessionUuid,
    serverBeneficiaryId = serverBeneficiaryId,
    formVersionId = "child-reg-version-1",
    answers = answers,
  )

  @Test
  fun `submitDraft() while offline saves locally and returns QueuedOffline without hitting the network`() = runTest {
    connectivityChecker.online = false
    seedSession()

    val result = submitDraft()

    assertEquals(DeliveryChildRegistrationSubmitResult.QueuedOffline, result)
    assertEquals(0, formSubmissionApi.callCount)
    val draft = dao.getByLocalSubmissionUuid("child-reg-submission-1")
    assertEquals(EnrollmentSyncStatus.PENDING, draft?.syncStatus)
    // The session is untouched until a sync actually succeeds.
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, deliverySessionRepository.getBySessionUuid("session-1")!!.step)
  }

  @Test
  fun `submitDraft() while online attempts the real submission and returns Synced on success`() = runTest {
    seedSession()
    formSubmissionApi.response = successResponse()

    val result = submitDraft()

    assertEquals(DeliveryChildRegistrationSubmitResult.Synced, result)
    val draft = dao.getByLocalSubmissionUuid("child-reg-submission-1")
    assertEquals(EnrollmentSyncStatus.SYNCED, draft?.syncStatus)
    assertEquals(DeliverySessionStep.PP1, deliverySessionRepository.getBySessionUuid("session-1")!!.step)
  }

  @Test
  fun `submitDraft() while online returns Failed and leaves the draft queued on a non-2xx response`() = runTest {
    seedSession()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submitDraft()

    assertTrue(result is DeliveryChildRegistrationSubmitResult.Failed)
    val draft = dao.getByLocalSubmissionUuid("child-reg-submission-1")
    assertEquals(EnrollmentSyncStatus.FAILED, draft?.syncStatus)
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, deliverySessionRepository.getBySessionUuid("session-1")!!.step)
  }

  @Test
  fun `a re-submit of the same localSubmissionUuid resets the draft to PENDING before the retry`() = runTest {
    seedSession()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )
    submitDraft()
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalSubmissionUuid("child-reg-submission-1")?.syncStatus)

    formSubmissionApi.response = successResponse()
    val result = submitDraft()

    assertEquals(DeliveryChildRegistrationSubmitResult.Synced, result)
  }

  @Test
  fun `submitDraft() stores the answers payload retrievable by the sync executor`() = runTest {
    connectivityChecker.online = false
    seedSession()

    submitDraft(localSubmissionUuid = "payload-check-1")

    val stored = secureStore.getString(deliveryChildRegistrationDraftPayloadKey("payload-check-1"))
    assertTrue(stored != null && stored.contains("Test Baby"))
  }
}
