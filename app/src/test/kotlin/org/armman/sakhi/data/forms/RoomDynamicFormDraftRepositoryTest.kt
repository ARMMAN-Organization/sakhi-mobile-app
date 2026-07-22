package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseData
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseDto
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class RoomDynamicFormDraftRepositoryTest {

  private lateinit var dao: FakeDynamicFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var syncScheduler: FakeDynamicFormSyncScheduler
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var enrollmentApi: FakeEnrollmentApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var syncExecutor: DynamicFormSyncExecutor
  private lateinit var repository: RoomDynamicFormDraftRepository

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
    dao = FakeDynamicFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    syncScheduler = FakeDynamicFormSyncScheduler()
    connectivityChecker = FakeConnectivityChecker(online = true)
    enrollmentApi = FakeEnrollmentApi()
    formSubmissionApi = FakeFormSubmissionApi()
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val mapper = DynamicFormSubmissionMapper(sessionStore, FakeLookupRepository())
    val coordinator = DynamicFormSubmissionCoordinator(enrollmentApi, formSubmissionApi, mapper)
    // Reuses the same dao/secureStore as the repository so runOne() sees the row submitDraft just
    // wrote — matching how the real Hilt graph wires a single instance of each.
    syncExecutor = DynamicFormSyncExecutor(dao, secureStore, coordinator)
    repository = RoomDynamicFormDraftRepository(dao, secureStore, syncScheduler, connectivityChecker, syncExecutor)
  }

  private val answers = FormAnswers(
    singleValues = mapOf(
      "did_we_receive_consent" to "yes",
      "lmp_date" to "2026-05-01",
      "gravida_total_number_of_pregnancies" to "1",
      "para_number_of_births_after_24_weeks" to "0",
      "living_children" to "1",
      "abortions_pregnancy_losses_before_24_weeks" to "0",
      "still_births" to "0",
      "beneficary_name_first_name_middle_name_last_name" to "Test Mother",
      "mobile_number" to "9876543210",
      "registrtion_date" to "2026-07-20",
    ),
  )

  private fun successfulBeneficiaryResponse() = Response.success(
    CreateBeneficiaryResponseDto(
      success = true,
      message = null,
      data = CreateBeneficiaryResponseData(id = "server-beneficiary-1"),
    ),
  )

  private fun successfulSubmissionResponse() = Response.success(
    CreateSubmissionResponseDto(success = true, message = null, data = SubmissionResponseData(id = "server-sub-1")),
  )

  @Test
  fun `saveDraft persists a PENDING metadata row and the encrypted payload`() = runTest {
    val result = repository.saveDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-uuid-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 7, 20),
    )

    assertTrue(result.isSuccess)
    val entity = dao.getByLocalBeneficiaryId("local-1")
    assertEquals(EnrollmentSyncStatus.PENDING, entity?.syncStatus)
    assertEquals("version-1", entity?.formVersionId)
    assertEquals("submission-uuid-1", entity?.localSubmissionUuid)
    assertEquals(0, entity?.retryCount)
  }

  @Test
  fun `saveDraft nudges the sync scheduler`() = runTest {
    repository.saveDraft(
      "local-1", "MOTHER_REGISTRATION", "version-1", "submission-uuid-1", answers, LocalDate.now(),
    )

    assertEquals(1, syncScheduler.syncNowCallCount)
  }

  @Test
  fun `payload round-trips through the encrypted store`() = runTest {
    repository.saveDraft(
      "local-1", "MOTHER_REGISTRATION", "version-1", "submission-uuid-1", answers, LocalDate.of(2026, 7, 20),
    )

    val json = secureStore.getString(dynamicFormDraftPayloadKey("local-1"))
    val payload = dynamicFormDraftGson.fromJson(json, DynamicFormDraftPayload::class.java)

    assertEquals(answers, payload.answers)
    assertEquals("2026-07-20", payload.registrationDateIso)
  }

  @Test
  fun `re-saving a SYNCED draft resets it to PENDING but keeps retryCount and remote ids`() = runTest {
    repository.saveDraft(
      "local-1", "MOTHER_REGISTRATION", "version-1", "submission-uuid-1", answers, LocalDate.now(),
    )
    dao.upsert(
      requireNotNull(dao.getByLocalBeneficiaryId("local-1")).copy(
        syncStatus = EnrollmentSyncStatus.SYNCED,
        remoteBeneficiaryId = "server-ben-1",
        remoteSubmissionId = "server-sub-1",
        retryCount = 3,
      ),
    )

    repository.saveDraft(
      "local-1", "MOTHER_REGISTRATION", "version-2", "submission-uuid-1", answers, LocalDate.now(),
    )

    val entity = requireNotNull(dao.getByLocalBeneficiaryId("local-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    assertEquals(3, entity.retryCount)
    assertEquals("server-ben-1", entity.remoteBeneficiaryId)
    assertEquals("version-2", entity.formVersionId) // updated form version is picked up
  }

  // --- submitDraft: the submit-then-navigate fix -----------------------------------------------

  private suspend fun submit() = repository.submitDraft(
    "local-1", "MOTHER_REGISTRATION", "version-1", "submission-uuid-1", answers, LocalDate.of(2026, 7, 20),
  )

  @Test
  fun `submitDraft online success returns Synced and marks the draft SYNCED`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submit()

    assertEquals(DynamicFormSubmitResult.Synced, result)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `submitDraft online validation failure returns Failed, not navigation`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = Response.error(
      400,
      "{\"message\":\"pii.villageId: Invalid uuid\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submit()

    assertTrue(result is DynamicFormSubmitResult.Failed)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `submitDraft online duplicate conflict returns DuplicateConflict`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = Response.error(
      409,
      "{\"message\":\"possible duplicate\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submit()

    assertTrue(result is DynamicFormSubmitResult.DuplicateConflict)
    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `submitDraft offline saves locally, queues sync, and returns QueuedOffline without calling either API`() = runTest {
    connectivityChecker.online = false

    val result = submit()

    assertEquals(DynamicFormSubmitResult.QueuedOffline, result)
    assertEquals(0, enrollmentApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
    assertEquals(1, syncScheduler.syncNowCallCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `submitDraft online but connectivity drops mid-call falls back to QueuedOffline`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.exceptionToThrow = IOException("no route to host")

    val result = submit()

    assertEquals(DynamicFormSubmitResult.QueuedOffline, result)
    assertEquals(1, syncScheduler.syncNowCallCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }
}
