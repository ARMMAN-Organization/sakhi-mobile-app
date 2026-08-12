package org.armman.sakhi.data.childregistration

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
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeEnrollmentApi
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

class ChildFormSyncExecutorTest {

  private lateinit var dao: FakeChildFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var enrollmentApi: FakeEnrollmentApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var executor: ChildFormSyncExecutor

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
    dao = FakeChildFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    enrollmentApi = FakeEnrollmentApi()
    formSubmissionApi = FakeFormSubmissionApi()

    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val lookupRepository = FakeLookupRepository().apply {
      valuesByCategory["BENEFICIARY_TYPE"] = listOf(
        LookupValue(id = "lookup-ben-child", valueCode = "CHILD", valueLabel = "Child"),
      )
    }
    val mapper = ChildRegistrationSubmissionMapper(sessionStore, lookupRepository)
    val coordinator = ChildRegistrationSubmissionCoordinator(enrollmentApi, formSubmissionApi, mapper)
    executor = ChildFormSyncExecutor(dao, secureStore, coordinator)
  }

  private fun validAnswers() = FormAnswers(
    singleValues = mapOf(
      "who_are_you_registering_in_the_program" to "child_directly_mother_not_registered_in_the_program",
      "did_we_receive_consent" to "yes",
      "date_of_birth_of_infant" to "2026-05-01",
      "name_of_the_child" to "Aarav Sharma",
      "sex_of_child" to "male",
      "term_of_delivery" to "full_term",
      "mobile_number" to "9876543210",
      "registrtion_date" to "2026-07-20",
    ),
  )

  private suspend fun seedPendingDraft(
    localBeneficiaryId: String = "local-1",
    status: EnrollmentSyncStatus = EnrollmentSyncStatus.PENDING,
  ) {
    secureStore.putString(
      childFormDraftPayloadKey(localBeneficiaryId),
      childFormDraftGson.toJson(ChildFormDraftPayload(validAnswers(), "2026-07-20")),
    )
    dao.upsert(
      ChildFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = "CHILD_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-uuid-1",
        syncStatus = status,
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
    assertEquals("version-1", formSubmissionApi.lastRequest?.formVersionId)
    assertEquals("server-beneficiary-1", formSubmissionApi.lastRequest?.beneficiaryId)
    // beneficiary_id injected from the server response into formData.
    assertEquals("server-beneficiary-1", formSubmissionApi.lastRequest?.formData?.get("beneficiary_id"))
    // The server-assigned id must survive onto the draft — ChildRegistrationSubmissionCoordinator
    // now returns it (Result<String>, was Result<Unit>) precisely so this can be persisted and
    // later used by the offline-first beneficiary list to match a synced child against her remote row.
    assertEquals("server-beneficiary-1", dao.getByLocalBeneficiaryId("local-1")?.remoteBeneficiaryId)
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
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `400 validation error marks FAILED WITHOUT requesting a retry`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = Response.error(
      400,
      "{\"message\":\"pii.villageId: Invalid uuid\"}".toResponseBody("application/json".toMediaType()),
    )

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
      ChildFormDraftEntity(
        localBeneficiaryId = "orphan",
        formCode = "CHILD_REGISTRATION",
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
  fun `runOne is idempotent when the draft is already SYNCED`() = runTest {
    seedPendingDraft(status = EnrollmentSyncStatus.SYNCED)

    val result = executor.runOne("local-1")

    assertEquals(ChildFormSyncItemResult.Synced, result)
    assertEquals(0, enrollmentApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `runOne returns null when there is no draft row for the id`() = runTest {
    assertEquals(null, executor.runOne("missing"))
  }

  @Test
  fun `runOne surfaces a duplicate conflict and holds the draft`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = Response.error(
      409,
      "{\"message\":\"possible duplicate\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = executor.runOne("local-1")

    assertEquals(ChildFormSyncItemResult.DuplicateConflict::class, result!!::class)
    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `runOne Failed carries a cleaned sentence, never the raw HTTP body — but keeps the raw body in the debug column`() = runTest {
    // Regression (PR #31 review): runOne's Failed.message flows straight to the Sakhi's snackbar.
    // It must be SubmitErrorCopy's cleaned sentence, not the exception's diagnostic
    // "POST /beneficiaries failed: HTTP 400 — {json}" text. The raw body must still be retained in
    // the draft's lastErrorMessage debug column.
    seedPendingDraft()
    enrollmentApi.response = Response.error(
      400,
      "{\"message\":\"lmpDate cannot be in the future\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = executor.runOne("local-1")

    val failed = result as ChildFormSyncItemResult.Failed
    val shown = requireNotNull(failed.message)
    assertFalse("UI message must not contain the raw HTTP prelude", shown.contains("POST /beneficiaries"))
    assertFalse("UI message must not contain the raw JSON body", shown.contains("{"))
    // The backend's own sentence, relabelled by SubmitErrorCopy (lmpDate -> "LMP date").
    assertEquals("LMP date cannot be in the future", shown)
    // Diagnostic body is still kept for debugging.
    val entity = requireNotNull(dao.getByLocalBeneficiaryId("local-1"))
    assertTrue(requireNotNull(entity.lastErrorMessage).contains("POST /beneficiaries failed: HTTP 400"))
  }
}
