package org.armman.sakhi.data.forms

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseData
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseDto
import org.armman.sakhi.data.enrollment.DuplicateOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.CcvScheduleGenerator
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.FakeVisitScheduleSyncScheduler
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.IncScheduleGenerator
import org.armman.sakhi.data.schedule.MotherEnrolmentScheduleTrigger
import org.armman.sakhi.data.schedule.NnScheduleGenerator
import org.armman.sakhi.data.schedule.PpScheduleGenerator
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.FakeVisitScheduleApi
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var visitScheduleApi: FakeVisitScheduleApi
  private lateinit var repository: RoomDynamicFormDraftRepository
  private lateinit var lookupRepository: FakeLookupRepository

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
    lookupRepository = FakeLookupRepository()
    val mapper = DynamicFormSubmissionMapper(sessionStore, lookupRepository)
    val coordinator = DynamicFormSubmissionCoordinator(enrollmentApi, formSubmissionApi, mapper)
    // Reuses the same dao/secureStore as the repository so runOne() sees the row submitDraft just
    // wrote — matching how the real Hilt graph wires a single instance of each.
    // CR-022: submitDraft also generates the ANC schedule, and a successful sync links it to the
    // server beneficiary id. Wired with real collaborators over a fake DAO so both side-effects are
    // exercised here rather than stubbed away — this is the only place the enrolment flow and the
    // scheduling engine meet.
    scheduleDao = FakeVisitScheduleDao()
    val scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    syncExecutor = DynamicFormSyncExecutor(dao, secureStore, coordinator, scheduleRepository, FakeVisitScheduleSyncScheduler())
    val rules = HardcodedRuleSource()
    visitScheduleApi = FakeVisitScheduleApi()
    val visitScheduleSyncExecutor = VisitScheduleSyncExecutor(scheduleRepository, visitScheduleApi)
    val scheduleTrigger = MotherEnrolmentScheduleTrigger(
      coordinator = VisitScheduleCoordinator(
        repository = scheduleRepository,
        ancGenerator = AncScheduleGenerator(rules),
        ppGenerator = PpScheduleGenerator(rules),
        nnGenerator = NnScheduleGenerator(rules),
        incGenerator = IncScheduleGenerator(rules),
        ccvGenerator = CcvScheduleGenerator(rules),
      ),
      ruleSource = rules,
    )
    repository = RoomDynamicFormDraftRepository(
      dao,
      secureStore,
      connectivityChecker,
      syncExecutor,
      scheduleTrigger,
      visitScheduleSyncExecutor,
    )
  }

  private val answers = FormAnswers(
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
      "date_of_birth" to "1996-01-01",
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
  fun `saveDraft schedules no upload - sync is the Sakhi's manual Data Upload action`() = runTest {
    repository.saveDraft(
      "local-1", "MOTHER_REGISTRATION", "version-1", "submission-uuid-1", answers, LocalDate.now(),
    )

    // SRS 3A.1 is manual-trigger-only. Enqueueing WorkManager work here would carry a
    // NetworkType.CONNECTED constraint and therefore fire by itself on reconnect — auto-sync by
    // another name.
    assertEquals(0, syncScheduler.syncNowCallCount)
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
    // submitDraft's online path delegates straight to syncExecutor.runOne — the server-assigned id
    // must come through here too, not just via the background run() sweep.
    assertEquals("server-beneficiary-1", dao.getByLocalBeneficiaryId("local-1")?.remoteBeneficiaryId)
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
  fun `submitDraft online 400 validation error carries fieldErrors end-to-end`() = runTest {
    connectivityChecker.online = true
    val body = """
      {"success":false,"message":"pii.firstName: String must contain at least 1 character(s)",
      "errorCode":"VALIDATION_ERROR",
      "fieldErrors":{"pii.firstName":"String must contain at least 1 character(s)"}}
    """.trimIndent()
    enrollmentApi.response = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    val result = submit()

    assertTrue(result is DynamicFormSubmitResult.Failed)
    assertEquals(
      "String must contain at least 1 character(s)",
      (result as DynamicFormSubmitResult.Failed).fieldErrors["pii.firstName"],
    )
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
  fun `submitDraft offline saves locally and returns QueuedOffline without calling either API or scheduling`() = runTest {
    connectivityChecker.online = false

    val result = submit()

    assertEquals(DynamicFormSubmitResult.QueuedOffline, result)
    assertEquals(0, enrollmentApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
    // The draft waits as PENDING for the Sakhi's Data Upload tap. Scheduling here would upload it
    // automatically on reconnect, which SRS 3A.1 rules out.
    assertEquals(0, syncScheduler.syncNowCallCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `submitDraft online but connectivity drops mid-call falls back to QueuedOffline`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.exceptionToThrow = IOException("no route to host")

    val result = submit()

    assertEquals(DynamicFormSubmitResult.QueuedOffline, result)
    // Same rule as the plain-offline case: left PENDING for the next manual Data Upload, not
    // auto-scheduled.
    assertEquals(0, syncScheduler.syncNowCallCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  @Test
  fun `submitDraft online shows a clear retryable message when a submit-critical lookup is missing`() = runTest {
    connectivityChecker.online = true
    // Simulate CASE_TYPE/BENEFICIARY_TYPE never having loaded (cold cache on a weak network) — the
    // mapper can't resolve caseTypeLookupId and throws LookupNotAvailable.
    lookupRepository.valuesByCategory = mutableMapOf()

    val result = submit()

    assertTrue(result is DynamicFormSubmitResult.Failed)
    val message = (result as DynamicFormSubmitResult.Failed).message.orEmpty()
    // Plain, actionable text — NOT the internal "seeded server-side?" phrasing.
    assertTrue(message.contains("connect to the internet", ignoreCase = true))
    assertFalse(message.contains("seeded", ignoreCase = true))
    // Left FAILED, which the sync worker still retries once the lookups warm.
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
  }

  // --- getUploadRecords: Home screen "Forms Uploaded" sync-status modal ------------------------

  @Test
  fun `getUploadRecords is empty when there are no drafts`() = runTest {
    assertTrue(repository.getUploadRecords().isEmpty())
  }

  @Test
  fun `getUploadRecords maps every draft's id, status and createdAt, newest first`() = runTest {
    // Timestamps set explicitly (not via saveDraft's Instant.now()) so ordering is deterministic
    // rather than depending on two real clock reads landing in different milliseconds.
    dao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = "local-1",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "sub-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 1_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
    dao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = "local-2",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "sub-2",
        syncStatus = EnrollmentSyncStatus.FAILED,
        createdAtEpochMillis = 2_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 1,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = "pii.villageId: Invalid uuid",
      ),
    )

    val records = repository.getUploadRecords()

    assertEquals(2, records.size)
    assertEquals("local-2", records[0].localBeneficiaryId)
    assertEquals("MOTHER_REGISTRATION", records[0].formCode)
    assertEquals(EnrollmentSyncStatus.FAILED, records[0].syncStatus)
    assertEquals(2_000L, records[0].createdAtEpochMillis)
    assertEquals("local-1", records[1].localBeneficiaryId)
    assertEquals(EnrollmentSyncStatus.PENDING, records[1].syncStatus)
  }

  @Test
  fun `observeUploadRecords emits the current drafts and re-emits when a status changes`() = runTest {
    dao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = "local-1",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "sub-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 1_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )

    assertEquals(EnrollmentSyncStatus.PENDING, repository.observeUploadRecords().first().single().syncStatus)

    // Simulate the sync worker completing the upload — the stream must reflect it live.
    dao.upsert(requireNotNull(dao.getByLocalBeneficiaryId("local-1")).copy(syncStatus = EnrollmentSyncStatus.SYNCED))

    assertEquals(EnrollmentSyncStatus.SYNCED, repository.observeUploadRecords().first().single().syncStatus)
  }

  @Test
  fun `getUploadRecords never exposes remote ids or error messages, only the PII-free projection`() = runTest {
    repository.saveDraft("local-1", "MOTHER_REGISTRATION", "version-1", "sub-1", answers, LocalDate.of(2026, 7, 1))
    dao.upsert(
      requireNotNull(dao.getByLocalBeneficiaryId("local-1")).copy(
        remoteBeneficiaryId = "server-ben-1",
        lastErrorMessage = "pii.villageId: Invalid uuid",
      ),
    )

    val record = repository.getUploadRecords().single()

    // FormUploadRecord's declared fields are the whole contract here — this assertion documents
    // that the type itself has no remoteBeneficiaryId/lastErrorMessage field to leak.
    assertEquals("local-1", record.localBeneficiaryId)
    assertEquals(EnrollmentSyncStatus.PENDING, record.syncStatus)
  }

  // ---- CR-022: ANC schedule generation on submit -----------------------------------------------

  /**
   * TR-1. This is the join between the enrolment flow and the scheduling engine — the engine can be
   * perfect and the Sakhi still sees nothing if this call is missing.
   */
  @Test
  fun `submitDraft generates the ANC schedule from the LMP answer`() = runTest {
    repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    val schedule = scheduleDao.getForBeneficiary("local-1")
    assertTrue("A submitted enrolment must produce a schedule", schedule.isNotEmpty())
    assertTrue(schedule.all { it.visitType == VisitCodeType.ANC })
    // ANC1 falls on the registration date itself (FR-S-3.2).
    assertEquals(LocalDate.of(2026, 5, 20), schedule.first().scheduledDate)
  }

  /** TR-4 — the SRS requires generation to work with no connectivity at all. */
  @Test
  fun `submitDraft generates the schedule even when offline`() = runTest {
    connectivityChecker.online = false

    repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    assertTrue(scheduleDao.getForBeneficiary("local-1").isNotEmpty())
  }

  /** A partial save is not an enrolment, so it must not produce a schedule. */
  @Test
  fun `saveDraft does not generate a schedule`() = runTest {
    repository.saveDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    assertTrue(scheduleDao.getForBeneficiary("local-1").isEmpty())
  }

  /** TR-5 — a double-tapped Submit must not produce two schedules. */
  @Test
  fun `submitting twice does not duplicate the schedule`() = runTest {
    repeat(2) {
      repository.submitDraft(
        localBeneficiaryId = "local-1",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-1",
        answers = answers,
        registrationDate = LocalDate.of(2026, 5, 20),
      )
    }

    // LMP 2026-05-01 → EDD 2027-02-05; registered 2026-05-20, so ((261 / 30) + 1) = 9 visits.
    assertEquals(9, scheduleDao.getForBeneficiary("local-1").size)
  }

  /**
   * The bug this catches: the schedule sync path was fully built but permanently inert.
   *
   * `getUnsynced()` deliberately skips any row whose `serverBeneficiaryId` is null — a schedule
   * cannot be uploaded before its beneficiary exists server-side. But nothing ever populated that
   * column: the submission coordinator obtained the server id, used it for the form submission and
   * then discarded it. So every generated schedule stayed invisible to the sync queue and silently
   * never uploaded, with no error anywhere to show for it.
   */
  @Test
  fun `a successful sync makes the generated schedule eligible for upload`() = runTest {
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    val stored = scheduleDao.getForBeneficiary("local-1")
    assertTrue("The schedule must exist", stored.isNotEmpty())
    assertTrue(
      "Every row must carry the server beneficiary id, or it can never upload",
      stored.all { it.serverBeneficiaryId == "server-beneficiary-1" },
    )
  }

  /**
   * The point of this fix: while online, the Sakhi should never need a manual Data Upload tap
   * just to make a just-generated schedule sync-eligible AND actually synced — submitDraft now
   * pushes it up itself, in the same call, the moment the beneficiary sync succeeds.
   */
  @Test
  fun `submitDraft online success also syncs the freshly generated schedule immediately`() = runTest {
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()

    repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    assertTrue("The schedule upload must have actually been attempted", visitScheduleApi.requests.isNotEmpty())
    val stored = scheduleDao.getForBeneficiary("local-1")
    assertTrue(
      "No manual Data Upload tap happened, yet every row must already carry a server schedule id",
      stored.all { it.serverScheduleId != null },
    )
  }

  /** A failed immediate schedule upload must not turn a successful enrolment into a reported failure
   * — it just leaves the schedule for the next manual Data Upload, exactly as before this fix. */
  @Test
  fun `a failed immediate schedule sync does not affect the reported submit result`() = runTest {
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = successfulSubmissionResponse()
    visitScheduleApi.throwIoException = true

    val result = repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    assertEquals(DynamicFormSubmitResult.Synced, result)
    val stored = scheduleDao.getForBeneficiary("local-1")
    assertTrue(
      "Left unsynced for the next Data Upload, not lost",
      stored.all { it.serverScheduleId == null },
    )
  }

  @Test
  fun `a schedule stays ineligible for upload while its beneficiary is unsynced`() = runTest {
    connectivityChecker.online = false

    repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "MOTHER_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    val stored = scheduleDao.getForBeneficiary("local-1")
    assertTrue("Generated offline", stored.isNotEmpty())
    assertTrue(
      "Deferred, not failed — it uploads once the beneficiary syncs",
      stored.all { it.serverBeneficiaryId == null },
    )
  }

  /**
   * The guard that stops this generic path minting an ANC series for anything that happens to
   * carry an `lmp_date`. Without it a re-registration, or any future form routed through the
   * dynamic-form repository, would each produce one.
   */
  @Test
  fun `a non-mother form does not generate an ANC schedule`() = runTest {
    repository.submitDraft(
      localBeneficiaryId = "local-1",
      formCode = "CHILD_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 5, 20),
    )

    assertTrue(scheduleDao.getForBeneficiary("local-1").isEmpty())
  }

  /**
   * A registration the scheduler cannot use still has to save. Losing an enrolment is far worse
   * than missing a schedule, which can be generated later.
   *
   * Run offline deliberately. Online, a missing `lmp_date` also fails the *submission* — the
   * beneficiary DTO requires it — so the result would be `Failed` for a reason that has nothing to
   * do with scheduling, and the test would prove nothing about the trigger. Offline the upload is
   * skipped entirely, isolating the one thing under test: the schedule trigger swallowing its own
   * failure rather than taking the draft down with it.
   */
  @Test
  fun `an LMP the scheduler cannot use still saves the draft and generates no schedule`() =
    runTest {
      connectivityChecker.online = false
      val withoutLmp = answers.copy(singleValues = answers.singleValues - "lmp_date")

      val result = repository.submitDraft(
        localBeneficiaryId = "local-1",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-1",
        answers = withoutLmp,
        registrationDate = LocalDate.of(2026, 5, 20),
      )

      assertEquals(DynamicFormSubmitResult.QueuedOffline, result)
      assertNotNull("The draft must still be saved", dao.getByLocalBeneficiaryId("local-1"))
      assertTrue(scheduleDao.getForBeneficiary("local-1").isEmpty())
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

  private fun storedPayload() = dynamicFormDraftGson.fromJson(
    requireNotNull(secureStore.getString(dynamicFormDraftPayloadKey("local-1"))),
    DynamicFormDraftPayload::class.java,
  )

  @Test
  fun `submitDraft surfaces a re-enrolment 409 as a new pregnancy prompt`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = conflictResponse(reEnrolmentConflictBody)

    val result = submit()

    assertEquals(
      DynamicFormSubmitResult.DuplicateConflict(DuplicateOutcome.NewPregnancyPrompt("earlier-case-uuid")),
      result,
    )
  }

  @Test
  fun `confirmNewPregnancy resubmits with the acknowledgement and syncs`() = runTest {
    connectivityChecker.online = true
    // First attempt is rejected as a possible duplicate, the retry succeeds.
    enrollmentApi.responseQueue.addLast(conflictResponse(reEnrolmentConflictBody))
    enrollmentApi.responseQueue.addLast(successfulBeneficiaryResponse())
    formSubmissionApi.response = successfulSubmissionResponse()
    submit()

    val result = repository.confirmNewPregnancy("local-1", "earlier-case-uuid")

    assertEquals(DynamicFormSubmitResult.Synced, result)
    val retry = requireNotNull(enrollmentApi.lastRequest)
    assertEquals(true, retry.acknowledgeDuplicate)
    assertEquals("earlier-case-uuid", retry.case.previousBeneficiaryId)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
    // Answered, so Home must stop offering the prompt.
    assertNull(storedPayload().pendingNewPregnancyBeneficiaryId)
  }

  @Test
  fun `confirmNewPregnancy offline queues the draft with the acknowledgement kept for the next upload`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = conflictResponse(reEnrolmentConflictBody)
    submit()
    val callsBefore = enrollmentApi.callCount
    connectivityChecker.online = false

    val result = repository.confirmNewPregnancy("local-1", "earlier-case-uuid")

    assertEquals(DynamicFormSubmitResult.QueuedOffline, result)
    assertEquals(callsBefore, enrollmentApi.callCount)
    // PENDING (not DUPLICATE_CONFLICT), or the manual Data Upload would skip it forever.
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
    assertEquals("earlier-case-uuid", storedPayload().duplicateAcknowledgement?.existingBeneficiaryId)
  }

  @Test
  fun `confirmNewPregnancy on an unknown draft fails instead of reporting a success that never happened`() = runTest {
    val result = repository.confirmNewPregnancy("does-not-exist", "earlier-case-uuid")

    assertTrue(result is DynamicFormSubmitResult.Failed)
    assertEquals(0, enrollmentApi.callCount)
  }

  @Test
  fun `dismissNewPregnancyPrompt clears the question but keeps the draft and its answers`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = conflictResponse(reEnrolmentConflictBody)
    submit()
    assertEquals("earlier-case-uuid", storedPayload().pendingNewPregnancyBeneficiaryId)

    repository.dismissNewPregnancyPrompt("local-1")

    assertNull(storedPayload().pendingNewPregnancyBeneficiaryId)
    assertNull(storedPayload().duplicateAcknowledgement)
    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, dao.getByLocalBeneficiaryId("local-1")?.syncStatus)
    assertNotNull(storedPayload().answers.singleValues["first_name"])
  }

  @Test
  fun `an unanswered prompt is exposed on the upload record so Home can ask about it`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = conflictResponse(reEnrolmentConflictBody)
    submit()

    val record = repository.getUploadRecords().single { it.localBeneficiaryId == "local-1" }

    assertEquals("earlier-case-uuid", record.pendingNewPregnancyBeneficiaryId)
  }

  @Test
  fun `a hard duplicate exposes no prompt on the upload record`() = runTest {
    connectivityChecker.online = true
    enrollmentApi.response = conflictResponse("{\"message\":\"A possible duplicate beneficiary already exists.\"}")
    submit()

    val record = repository.getUploadRecords().single { it.localBeneficiaryId == "local-1" }

    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, record.syncStatus)
    assertNull(record.pendingNewPregnancyBeneficiaryId)
  }
}
