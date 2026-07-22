package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseData
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseDto
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

class DynamicFormSyncExecutorTest {

  private lateinit var dao: FakeDynamicFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var enrollmentApi: FakeEnrollmentApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var executor: DynamicFormSyncExecutor

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
    enrollmentApi = FakeEnrollmentApi()
    formSubmissionApi = FakeFormSubmissionApi()

    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val mapper = DynamicFormSubmissionMapper(sessionStore, FakeLookupRepository())
    val coordinator = DynamicFormSubmissionCoordinator(enrollmentApi, formSubmissionApi, mapper)
    executor = DynamicFormSyncExecutor(dao, secureStore, coordinator)
  }

  private fun validAnswers() = FormAnswers(
    singleValues = mapOf(
      "did_we_receive_consent" to "yes",
      "lmp_date" to "2026-05-01",
      "gravida_total_number_of_pregnancies" to "1",
      "para_number_of_births_after_24_weeks" to "0",
      "living_children" to "1",
      "abortions_pregnancy_losses_before_24_weeks" to "0",
      "still_births" to "0",
      "first_name" to "Test",
      "last_name" to "Mother",
      "mobile_number" to "9876543210",
      "date_of_birth" to "1996-01-01",
      "registrtion_date" to "2026-07-20",
    ),
  )

  private suspend fun seedPendingDraft(localBeneficiaryId: String = "local-1") {
    secureStore.putString(
      dynamicFormDraftPayloadKey(localBeneficiaryId),
      dynamicFormDraftGson.toJson(DynamicFormDraftPayload(validAnswers(), "2026-07-20")),
    )
    dao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-uuid-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
  }

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
  fun `no pending drafts completes without calling either API`() = runTest {
    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, enrollmentApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `successful two-call submission marks the draft SYNCED`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `409 on beneficiary creation marks the draft DUPLICATE_CONFLICT, not retryable`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = Response.error(
      409,
      "{\"message\":\"possible duplicate\"}".toResponseBody("application/json".toMediaType()),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalBeneficiaryId("local-1"))
    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, entity.syncStatus)
    assertEquals(1, entity.retryCount)
  }

  @Test
  fun `500 (transient server error) marks the draft FAILED and requests a retry`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = Response.error(500, "server error".toResponseBody("text/plain".toMediaType()))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByLocalBeneficiaryId("local-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    assertEquals(1, entity.retryCount)
  }

  @Test
  fun `400 validation error (real geography-uuid failure) marks FAILED WITHOUT requesting a retry`() = runTest {
    // The exact body a real device hit against /beneficiaries: invalid (non-UUID) geography
    // ids from the still-fake StaticGeographyRepository, a missing healthBlockId/rchNumber, and
    // an unmet gravida=liveBirths+stillbirths+abortions invariant. This is a permanent data
    // problem — retrying the identical payload will 400 identically forever, so unlike the 500
    // case above this must NOT be classified as a retryable failure.
    seedPendingDraft()
    val body = """
      {"success":false,"message":"pii.villageId: Invalid uuid; pii.healthBlockId: Required; pii.rchNumber: Required",
      "errorCode":"VALIDATION_ERROR","fieldErrors":{"pii.villageId":"Invalid uuid"}}
    """.trimIndent()
    enrollmentApi.response = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByLocalBeneficiaryId("local-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    assertEquals(1, entity.retryCount)
  }

  @Test
  fun `IOException leaves the draft PENDING and requests a retry`() = runTest {
    seedPendingDraft()
    enrollmentApi.exceptionToThrow = IOException("no route to host")

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `missing encrypted payload marks the draft FAILED without calling either API`() = runTest {
    dao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = "orphan",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-uuid-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, enrollmentApi.callCount)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalBeneficiaryId("orphan")?.syncStatus)
  }

  @Test
  fun `submits the formVersionId recorded at save time, not a re-fetched one`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    executor.run()

    assertEquals("version-1", formSubmissionApi.lastRequest?.formVersionId)
    assertEquals("server-beneficiary-1", formSubmissionApi.lastRequest?.beneficiaryId)
    assertEquals("submission-uuid-1", formSubmissionApi.lastRequest?.localSubmissionUuid)
  }
}
