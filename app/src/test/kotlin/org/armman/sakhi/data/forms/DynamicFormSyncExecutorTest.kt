package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseData
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseDto
import org.armman.sakhi.data.enrollment.DuplicateAcknowledgement
import org.armman.sakhi.data.enrollment.DuplicateOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.FakeVisitScheduleSyncScheduler
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

class DynamicFormSyncExecutorTest {

  private lateinit var dao: FakeDynamicFormDraftDao
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var enrollmentApi: FakeEnrollmentApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var visitScheduleSyncScheduler: FakeVisitScheduleSyncScheduler
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
    val coordinator = DynamicFormSubmissionCoordinator(
      enrollmentApi,
      formSubmissionApi,
      mapper,
      sessionStore,
      FakeFormAuditRepository(),
    )
    scheduleDao = FakeVisitScheduleDao()
    visitScheduleSyncScheduler = FakeVisitScheduleSyncScheduler()
    executor = DynamicFormSyncExecutor(
      dao,
      secureStore,
      coordinator,
      RoomVisitScheduleRepository(scheduleDao),
      visitScheduleSyncScheduler,
    )
  }

  private fun validAnswers() = FormAnswers(
    singleValues = mapOf(
      "did_we_receive_consent" to "yes",
      "lmp_date" to "2026-05-01",
      // 1 living child + 0 still births + 0 abortions = 1 past outcome, + the current pregnancy.
      "gravida_total_number_of_pregnancies" to "2",
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

  /** Puts a draft in the exact state a killed/cancelled sync pass leaves behind. */
  private suspend fun seedOrphanedSyncingDraft(localBeneficiaryId: String = "local-1") {
    seedPendingDraft(localBeneficiaryId)
    dao.upsert(
      requireNotNull(dao.getByLocalBeneficiaryId(localBeneficiaryId))
        .copy(syncStatus = EnrollmentSyncStatus.SYNCING),
    )
  }

  @Test
  fun `a draft orphaned in SYNCING is reclaimed and uploaded by the next run`() = runTest {
    // Regression: SYNCING is written before the network attempt, but getPendingSync() selects only
    // PENDING/FAILED. A pass killed mid-attempt (process death, OS stopping the worker, or
    // WorkManager cancelling it for a fresh manual tap) used to strand the draft permanently — the
    // Home badge counted it as pending forever while no sync could ever pick it up.
    seedOrphanedSyncingDraft()
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(1, enrollmentApi.callCount)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `reclaiming SYNCING leaves SYNCED and DUPLICATE_CONFLICT drafts untouched`() = runTest {
    seedPendingDraft("synced-1")
    dao.upsert(
      requireNotNull(dao.getByLocalBeneficiaryId("synced-1")).copy(syncStatus = EnrollmentSyncStatus.SYNCED),
    )
    seedPendingDraft("dupe-1")
    dao.upsert(
      requireNotNull(dao.getByLocalBeneficiaryId("dupe-1"))
        .copy(syncStatus = EnrollmentSyncStatus.DUPLICATE_CONFLICT),
    )

    executor.run()

    // Re-uploading an already-SYNCED draft would be wasteful, and a DUPLICATE_CONFLICT is held for
    // the Sakhi to resolve — neither is an orphan, so neither may be swept back into PENDING.
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalBeneficiaryId("synced-1")?.syncStatus)
    assertEquals(
      EnrollmentSyncStatus.DUPLICATE_CONFLICT,
      dao.getByLocalBeneficiaryId("dupe-1")?.syncStatus,
    )
    assertEquals(0, enrollmentApi.callCount)
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
    // The server-assigned id must survive onto the draft — the offline-first beneficiary list
    // (Phase 2) matches a local-synced row against its remote counterpart using this field.
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
  fun `runOne on a 400 validation error returns Failed carrying the DTO-path fieldErrors`() = runTest {
    seedPendingDraft()
    val body = """
      {"success":false,"message":"pii.firstName: String must contain at least 1 character(s)",
      "errorCode":"VALIDATION_ERROR",
      "fieldErrors":{"pii.firstName":"String must contain at least 1 character(s)"}}
    """.trimIndent()
    enrollmentApi.response = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    val result = executor.runOne("local-1")

    assertTrue(result is DynamicFormSyncItemResult.Failed)
    assertEquals(
      "String must contain at least 1 character(s)",
      (result as DynamicFormSyncItemResult.Failed).fieldErrors["pii.firstName"],
    )
  }

  @Test
  fun `runOne never surfaces the raw response body, but still logs it on the draft`() = runTest {
    // The bug this guards: the banner used to read
    // `POST /beneficiaries failed: HTTP 400 — {"success":false,…,"traceId":"5c42c1e5…"}`.
    seedPendingDraft()
    val body = """
      {"success":false,"message":"motherDetails.lmpDate: lmpDate cannot be in the future",
      "errorCode":"VALIDATION_ERROR","traceId":"5c42c1e51d2ae1d079ec420f587d6eac",
      "fieldErrors":{"motherDetails.lmpDate":"lmpDate cannot be in the future"}}
    """.trimIndent()
    enrollmentApi.response = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    val result = executor.runOne("local-1")

    val message = requireNotNull((result as DynamicFormSyncItemResult.Failed).message)
    assertEquals("LMP date cannot be in the future", message)
    // The raw body is still kept for debugging — it just doesn't reach the screen.
    val entity = requireNotNull(dao.getByLocalBeneficiaryId("local-1"))
    assertTrue(requireNotNull(entity.lastErrorMessage).contains("traceId"))
  }

  @Test
  fun `runOne on a 500 with a non-envelope body shows the generic sentence`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = Response.error(500, "server error".toResponseBody("text/plain".toMediaType()))

    val result = executor.runOne("local-1")

    assertEquals(SubmitErrorCopy.GENERIC, (result as DynamicFormSyncItemResult.Failed).message)
  }

  @Test
  fun `runOne on a 422 returns Failed with empty fieldErrors (banner-only)`() = runTest {
    seedPendingDraft()
    val body = """
      {"success":false,"message":"pii.phcId does not refer to a known geography unit.",
      "errorCode":"UNPROCESSABLE"}
    """.trimIndent()
    enrollmentApi.response = Response.error(422, body.toResponseBody("application/json".toMediaType()))

    val result = executor.runOne("local-1")

    assertTrue(result is DynamicFormSyncItemResult.Failed)
    assertTrue((result as DynamicFormSyncItemResult.Failed).fieldErrors.isEmpty())
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

  // --- CR-033 duplicate detection (SRS FR-S-2.4 / FR-S-2.5) ------------------------------------

  private val reEnrolmentConflictBody = """
    {"success":false,"message":"A previous record exists for this beneficiary. Is this a new pregnancy?",
     "errorCode":"CONFLICT","traceId":"t-1",
     "fieldErrors":{"reason":"RE_ENROLLMENT","existingBeneficiaryId":"earlier-case-uuid",
     "resolution":"Resubmit with acknowledgeDuplicate: true to enroll a new pregnancy."}}
  """.trimIndent()

  private fun conflictResponse(body: String) =
    Response.error<CreateBeneficiaryResponseDto>(409, body.toResponseBody("application/json".toMediaType()))

  private fun storedPayload(localBeneficiaryId: String = "local-1") =
    dynamicFormDraftGson.fromJson(
      requireNotNull(secureStore.getString(dynamicFormDraftPayloadKey(localBeneficiaryId))),
      DynamicFormDraftPayload::class.java,
    )

  @Test
  fun `runOne on a re-enrolment 409 returns a new pregnancy prompt carrying the earlier case id`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = conflictResponse(reEnrolmentConflictBody)

    val result = executor.runOne("local-1")

    assertEquals(
      DynamicFormSyncItemResult.DuplicateConflict(
        DuplicateOutcome.NewPregnancyPrompt("earlier-case-uuid"),
      ),
      result,
    )
  }

  @Test
  fun `a re-enrolment prompt is remembered on the draft so it can be answered from Home`() = runTest {
    // The 409 can land during a background upload with nobody on the form. Without persisting the
    // prompt the draft would sit in DUPLICATE_CONFLICT forever — visible, unanswerable, unuploadable.
    seedPendingDraft()
    enrollmentApi.response = conflictResponse(reEnrolmentConflictBody)

    executor.run()

    assertEquals("earlier-case-uuid", storedPayload().pendingNewPregnancyBeneficiaryId)
    assertEquals(
      EnrollmentSyncStatus.DUPLICATE_CONFLICT,
      requireNotNull(dao.getByLocalBeneficiaryId("local-1")).syncStatus,
    )
  }

  @Test
  fun `a hard duplicate 409 is blocked and stores no prompt`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = conflictResponse(
      "{\"message\":\"A possible duplicate beneficiary already exists (beneficiaryId: abc).\"}",
    )

    val result = executor.runOne("local-1")

    assertEquals(
      DynamicFormSyncItemResult.DuplicateConflict(DuplicateOutcome.HardDuplicate),
      result,
    )
    assertNull(storedPayload().pendingNewPregnancyBeneficiaryId)
  }

  @Test
  fun `a first attempt sends neither acknowledgeDuplicate nor a previous case link`() = runTest {
    seedPendingDraft()
    enrollmentApi.response = successfulBeneficiaryResponse()

    executor.runOne("local-1")

    val request = requireNotNull(enrollmentApi.lastRequest)
    assertNull(request.acknowledgeDuplicate)
    assertNull(request.case.previousBeneficiaryId)
  }

  @Test
  fun `a draft carrying an acknowledgement uploads with acknowledgeDuplicate and the earlier case link`() = runTest {
    seedPendingDraft()
    secureStore.putString(
      dynamicFormDraftPayloadKey("local-1"),
      dynamicFormDraftGson.toJson(
        storedPayload().copy(duplicateAcknowledgement = DuplicateAcknowledgement("earlier-case-uuid")),
      ),
    )
    enrollmentApi.response = successfulBeneficiaryResponse()
    // Both calls have to succeed for the draft to reach SYNCED — `POST /beneficiaries` then
    // `POST /forms/.../submissions`. Stubbing only the first left the submission failing, so the
    // draft ended FAILED even though the acknowledgement assertions below passed.
    formSubmissionApi.response = successfulSubmissionResponse()

    executor.run()

    val request = requireNotNull(enrollmentApi.lastRequest)
    assertEquals(true, request.acknowledgeDuplicate)
    assertEquals("earlier-case-uuid", request.case.previousBeneficiaryId)
    assertEquals(
      EnrollmentSyncStatus.SYNCED,
      requireNotNull(dao.getByLocalBeneficiaryId("local-1")).syncStatus,
    )
  }
}
