package org.armman.sakhi.data.adhocform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.closure.FakeClosureRepository
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.referral.FakeReferralEvidenceDao
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.referral.FakeReferralEvidenceSyncScheduler
import org.armman.sakhi.data.visitform.FakeReferralRepository
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Covers [RoomAdHocFormDraftRepository] — the offline-first entry point
 * [org.armman.sakhi.ui.adhocform.AdHocFormViewModel.onSubmit] calls. The core contract under test
 * mirrors [org.armman.sakhi.data.visitform.RoomVisitFormDraftRepositoryTest]'s: the
 * immediate-online happy path, the offline-queue path, and — the one thing THIS queue exists to
 * fix over reusing [org.armman.sakhi.data.forms.DynamicFormDraftEntity]'s shape — that two
 * independent drafts for the same beneficiary never collide.
 */
class RoomAdHocFormDraftRepositoryTest {

  private lateinit var dao: FakeAdHocFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var syncExecutor: AdHocFormSyncExecutor
  private lateinit var repository: RoomAdHocFormDraftRepository
  private lateinit var formAuditRepository: FakeFormAuditRepository

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
    dao = FakeAdHocFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    connectivityChecker = FakeConnectivityChecker(online = true)
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val coordinator = AdHocFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      sessionStore = sessionStore,
      formAuditRepository = FakeFormAuditRepository(),
      closureRepository = FakeClosureRepository(),
      lookupRepository = FakeLookupRepository(),
      statusOverrideStore = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore()),
      referralRepository = FakeReferralRepository(),
      referralEvidenceDao = FakeReferralEvidenceDao(),
      referralEvidenceSyncScheduler = FakeReferralEvidenceSyncScheduler(),
      referralLinkDao = FakeReferralLinkDao(),
    )
    // Reuses the same dao/secureStore as the repository so runOne() sees the row submitDraft just
    // wrote — matching how the real Hilt graph wires a single instance of each.
    syncExecutor = AdHocFormSyncExecutor(dao, secureStore, coordinator)
    formAuditRepository = FakeFormAuditRepository()
    repository = RoomAdHocFormDraftRepository(dao, secureStore, connectivityChecker, syncExecutor, formAuditRepository)
  }

  private val answers = FormAnswers(singleValues = mapOf("referral_facility" to "PHC Sonapur"))

  private suspend fun seedSyncedBeneficiary(localBeneficiaryId: String = "ben-1") {
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "schedule-for-$localBeneficiaryId",
          localBeneficiaryId = localBeneficiaryId,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  private fun successfulSubmissionResponse(id: String = "server-sub-1") = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = id)),
  )

  private suspend fun submit(
    localFormInstanceUuid: String = "instance-1",
    localBeneficiaryId: String = "ben-1",
    formCode: String = "REFERRAL_VISIT",
  ) = repository.submitDraft(
    localFormInstanceUuid = localFormInstanceUuid,
    localBeneficiaryId = localBeneficiaryId,
    formCode = formCode,
    formVersionId = "version-1",
    answers = answers,
    referralId = null,
    capturedImagePaths = emptyMap(),
  )

  @Test
  fun `saveLocally (via submitDraft) persists a draft row and writes a SAVED audit event`() = runTest {
    seedSyncedBeneficiary()
    connectivityChecker.online = false

    submit()

    val entity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-1"))
    assertEquals("ben-1", entity.localBeneficiaryId)
    assertEquals("REFERRAL_VISIT", entity.formCode)
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    assertEquals(
      listOf(FormAuditEventType.SAVED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("instance-1", formAuditRepository.recordedEvents.single().subjectId)
    assertEquals("REFERRAL_VISIT", formAuditRepository.recordedEvents.single().formCode)
  }

  @Test
  fun `submitDraft online success returns Synced and records the submission`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse(id = "server-sub-42")

    val result = submit()

    assertEquals(AdHocFormSubmitResult.Synced, result)
    val entity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-1"))
    assertEquals(EnrollmentSyncStatus.SYNCED, entity.syncStatus)
    assertEquals("server-sub-42", entity.serverSubmissionId)
  }

  @Test
  fun `submitDraft offline saves locally and returns QueuedOffline`() = runTest {
    seedSyncedBeneficiary()
    connectivityChecker.online = false

    val result = submit()

    assertEquals(AdHocFormSubmitResult.QueuedOffline, result)
    assertEquals(0, formSubmissionApi.callCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalFormInstanceUuid("instance-1")?.syncStatus)
    val json = secureStore.getString(adHocFormDraftPayloadKey("instance-1"))
    assertTrue(json != null && json.isNotBlank())
  }

  @Test
  fun `submitDraft online failure still leaves the draft queued for the next Data Upload`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    val result = submit()

    assertTrue(result is AdHocFormSubmitResult.Failed)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByLocalFormInstanceUuid("instance-1")?.syncStatus)
    val json = secureStore.getString(adHocFormDraftPayloadKey("instance-1"))
    assertTrue(json != null && json.isNotBlank())
  }

  @Test
  fun `two independent drafts for the same beneficiary (different formCodes) do not collide`() = runTest {
    // THE regression test this new entity fixes over DynamicFormDraftEntity's one-row-per-
    // beneficiary shape: a beneficiary with both a Referral and a Referral Follow-up draft in
    // flight at once must keep BOTH rows, not have the second overwrite the first.
    seedSyncedBeneficiary("ben-1")
    connectivityChecker.online = false

    val referralResult = submit(
      localFormInstanceUuid = "instance-referral",
      localBeneficiaryId = "ben-1",
      formCode = "REFERRAL_VISIT",
    )
    val followUpResult = submit(
      localFormInstanceUuid = "instance-followup",
      localBeneficiaryId = "ben-1",
      formCode = "REFERRAL_FOLLOWUP_VISIT",
    )

    assertEquals(AdHocFormSubmitResult.QueuedOffline, referralResult)
    assertEquals(AdHocFormSubmitResult.QueuedOffline, followUpResult)

    val referralEntity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-referral"))
    val followUpEntity = requireNotNull(dao.getByLocalFormInstanceUuid("instance-followup"))
    assertEquals("REFERRAL_VISIT", referralEntity.formCode)
    assertEquals("REFERRAL_FOLLOWUP_VISIT", followUpEntity.formCode)
    assertEquals("ben-1", referralEntity.localBeneficiaryId)
    assertEquals("ben-1", followUpEntity.localBeneficiaryId)
    // Both rows survive — neither submitDraft call overwrote the other's.
    assertEquals(2, dao.getAll().size)
  }

  @Test
  fun `submitDraft surfaces NotYetSynced as an immediate Failed but keeps the draft PENDING for later`() = runTest {
    // No schedule row at all for this beneficiary — no server beneficiary id is known yet.
    val result = submit(localBeneficiaryId = "ben-unsynced")

    assertTrue(result is AdHocFormSubmitResult.Failed)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByLocalFormInstanceUuid("instance-1")?.syncStatus)
  }
}
