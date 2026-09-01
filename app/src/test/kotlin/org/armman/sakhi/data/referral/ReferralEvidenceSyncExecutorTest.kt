package org.armman.sakhi.data.referral

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * CR-Referral-02: covers [ReferralEvidenceSyncExecutor]'s offline-queue drain — same run()/
 * reclaimStaleSyncing()/retry shape as every other executor in this app (see
 * [org.armman.sakhi.data.adhocform.AdHocFormSyncExecutorTest] for the sibling coverage this
 * mirrors). [RemoteReferralRepository.uploadEvidence]'s own request/response mapping is covered
 * separately in [RemoteReferralRepositoryTest] — this file only exercises the queue-draining
 * logic against a fake [ReferralRepository].
 */
class ReferralEvidenceSyncExecutorTest {

  private class FakeReferralRepository : ReferralRepository {
    var uploadResult: (ReferralEvidenceType, File) -> Result<String> = { _, _ -> Result.success("media-1") }
    data class UploadCall(val referralId: String, val followupId: String?, val submissionId: String?, val evidenceType: ReferralEvidenceType)
    val uploadCalls = mutableListOf<UploadCall>()

    override suspend fun getCachedReferralVisitName(referralId: String): String? = throw UnsupportedOperationException()
    override suspend fun getPendingFollowUps(): List<ReferralFollowUp> = throw UnsupportedOperationException()
    override suspend fun createReferral(
      visitId: String?,
      beneficiaryId: String,
      sourceSubmissionId: String?,
      capture: ReferralCapture,
      triggeringConditionIds: List<String>,
    ): Result<CreateReferralOutcome> = throw UnsupportedOperationException()

    override suspend fun submitFollowUp(
      referralId: String,
      visitedFacilityFlag: Boolean,
      followupDate: java.time.LocalDate,
      notVisitedReason: String?,
      diagnosis: String?,
      treatmentGiven: String?,
      outcome: String?,
    ): Result<ReferralFollowUpResult> = throw UnsupportedOperationException()

    override suspend fun convertToAccompanied(referralId: String): Result<Referral> = throw UnsupportedOperationException()

    override suspend fun uploadEvidence(
      referralId: String,
      followupId: String?,
      evidenceType: ReferralEvidenceType,
      file: File,
      submissionId: String?,
    ): Result<String> {
      uploadCalls += UploadCall(referralId, followupId, submissionId, evidenceType)
      return uploadResult(evidenceType, file)
    }
  }

  private lateinit var dao: FakeReferralEvidenceDao
  private lateinit var referralRepository: FakeReferralRepository
  private lateinit var executor: ReferralEvidenceSyncExecutor
  private lateinit var tempFile: File

  @Before
  fun setUp() {
    dao = FakeReferralEvidenceDao()
    referralRepository = FakeReferralRepository()
    executor = ReferralEvidenceSyncExecutor(dao, referralRepository)
    tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1)) }
  }

  @After
  fun tearDown() {
    tempFile.delete()
  }

  private fun media(
    localMediaUuid: String = "media-uuid-1",
    referralId: String = "referral-1",
    evidenceType: ReferralEvidenceType = ReferralEvidenceType.REFERRAL_CASE_PAPER,
    localFilePath: String = tempFile.absolutePath,
    syncStatus: EnrollmentSyncStatus = EnrollmentSyncStatus.PENDING,
    followupId: String? = "followup-1",
    submissionId: String? = null,
  ) = ReferralEvidenceMediaEntity(
    localMediaUuid = localMediaUuid,
    referralId = referralId,
    evidenceType = evidenceType.name,
    localFilePath = localFilePath,
    syncStatus = syncStatus,
    createdAtEpochMillis = 0L,
    lastAttemptAtEpochMillis = null,
    retryCount = 0,
    followupId = followupId,
    submissionId = submissionId,
    remoteMediaId = null,
    lastErrorMessage = null,
  )

  @Test
  fun `run with nothing pending completes immediately`() = runTest {
    assertEquals(EnrollmentSyncOutcome.COMPLETED, executor.run())
  }

  @Test
  fun `run uploads a pending item and marks it SYNCED with the returned media id`() = runTest {
    dao.upsert(media())

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val stored = dao.getByReferralId("referral-1").single()
    assertEquals(EnrollmentSyncStatus.SYNCED, stored.syncStatus)
    assertEquals("media-1", stored.remoteMediaId)
    assertEquals(1, referralRepository.uploadCalls.size)
  }

  @Test
  fun `a network failure leaves the item PENDING and reports a retryable outcome`() = runTest {
    dao.upsert(media())
    referralRepository.uploadResult = { _, _ -> Result.failure(java.io.IOException("offline")) }

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val stored = dao.getByReferralId("referral-1").single()
    assertEquals(EnrollmentSyncStatus.PENDING, stored.syncStatus)
    assertEquals(1, stored.retryCount)
  }

  @Test
  fun `a missing local file is a permanent failure, not retried`() = runTest {
    dao.upsert(media(localFilePath = "/tmp/does-not-exist-${System.nanoTime()}.jpg"))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByReferralId("referral-1").single().syncStatus)
    assertTrue(referralRepository.uploadCalls.isEmpty())
  }

  @Test
  fun `a row with no followupId yet is not picked up for sync`() = runTest {
    dao.upsert(media(followupId = null))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertTrue(referralRepository.uploadCalls.isEmpty())
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByReferralId("referral-1").single().syncStatus)
  }

  @Test
  fun `reclaims rows stuck in SYNCING from a run that never finished`() = runTest {
    dao.upsert(media(syncStatus = EnrollmentSyncStatus.SYNCING))

    executor.run()

    assertEquals(1, referralRepository.uploadCalls.size)
  }

  @Test
  fun `multiple pending items across different evidence types all get uploaded`() = runTest {
    dao.upsert(media(localMediaUuid = "m1", evidenceType = ReferralEvidenceType.REFERRAL_CASE_PAPER))
    dao.upsert(media(localMediaUuid = "m2", evidenceType = ReferralEvidenceType.REFERRAL_INVESTIGATION_REPORT))
    dao.upsert(media(localMediaUuid = "m3", evidenceType = ReferralEvidenceType.REFERRAL_HEALTH_FACILITY_PHOTO))

    executor.run()

    assertEquals(3, referralRepository.uploadCalls.size)
    assertTrue(dao.getByReferralId("referral-1").all { it.syncStatus == EnrollmentSyncStatus.SYNCED })
  }

  // --- submissionId-keyed rows (CR-Referral-01/02: ad-hoc form pipeline evidence) ---

  @Test
  fun `a row keyed by submissionId (no followupId) is eligible and uploads with submissionId`() = runTest {
    dao.upsert(media(followupId = null, submissionId = "submission-1"))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val call = referralRepository.uploadCalls.single()
    assertEquals(null, call.followupId)
    assertEquals("submission-1", call.submissionId)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByReferralId("referral-1").single().syncStatus)
  }

  @Test
  fun `a row with neither followupId nor submissionId is left PENDING and never uploaded`() = runTest {
    dao.upsert(media(followupId = null, submissionId = null))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertTrue(referralRepository.uploadCalls.isEmpty())
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByReferralId("referral-1").single().syncStatus)
  }
}
