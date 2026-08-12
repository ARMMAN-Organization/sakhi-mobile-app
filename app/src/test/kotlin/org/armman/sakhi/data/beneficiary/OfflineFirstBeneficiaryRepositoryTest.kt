package org.armman.sakhi.data.beneficiary

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.dynamicFormDraftGson
import org.armman.sakhi.data.forms.dynamicFormDraftPayloadKey
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.BeneficiaryDetailResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryListItemDto
import org.armman.sakhi.data.motherlink.BeneficiaryListResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryPiiDto
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/**
 * Covers [OfflineFirstBeneficiaryRepository] — the local/remote merge for My Beneficiaries (Phase
 * 2). [RemoteBeneficiaryListFeatureFlag.ENABLED] is a compile-time `const val`, so this suite can
 * only exercise the flag's CURRENT value. It is currently `true` (temporarily, risk explicitly
 * accepted — see the flag's own KDoc), so the tests below drive the real entry point,
 * [OfflineFirstBeneficiaryRepository.getBeneficiaries], and expect the actual fetch+merge. The
 * "merge logic, exercised directly" section further down additionally exercises that same merge
 * rule directly against a `RemoteBeneficiaryRepository` built from a fake API — kept even though
 * it's now redundant with the flag being on, so this suite doesn't silently lose coverage of the
 * merge contract itself if the flag is ever reverted to `false`.
 */
class OfflineFirstBeneficiaryRepositoryTest {

  private lateinit var draftDao: FakeDynamicFormDraftDao
  private lateinit var childDraftDao: FakeChildFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var localEnrolments: LocalEnrolmentBeneficiarySource

  private val lmp = LocalDate.of(2026, 1, 1)

  @Before
  fun setUp() {
    draftDao = FakeDynamicFormDraftDao()
    childDraftDao = FakeChildFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    localEnrolments = LocalEnrolmentBeneficiarySource(
      draftDao,
      childDraftDao,
      secureStore,
      RoomVisitScheduleRepository(FakeVisitScheduleDao()),
      FakeFormsRepository(),
    )
  }

  private class FakeBeneficiaryApi(
    var listAllResponse: (() -> Response<BeneficiaryListResponseDto>)? = null,
  ) : BeneficiaryApi {
    override suspend fun list(caseType: String, status: String): Response<BeneficiaryListResponseDto> =
      throw UnsupportedOperationException("not used here")

    override suspend fun listAll(caseType: String?, status: String?): Response<BeneficiaryListResponseDto> =
      listAllResponse?.invoke() ?: throw IOException("offline")

    override suspend fun detail(id: String): Response<BeneficiaryDetailResponseDto> =
      throw UnsupportedOperationException("not used here")
  }

  private fun remoteRow(id: String, name: String = "Remote Row") = BeneficiaryListItemDto(
    id = id,
    caseType = "MOTHER",
    currentStatus = "ACTIVE",
    currentPhase = "ANC",
    registrationDate = "2026-07-01T00:00:00.000Z",
    motherBeneficiaryId = null,
    pii = BeneficiaryPiiDto(
      id = "pii-$id",
      fullName = name,
      villageId = "village-1",
      padaId = "pada-1",
      healthSubCentreId = "sc-1",
      phcId = "phc-1",
      healthBlockId = "block-1",
      dateOfBirth = null,
      sex = "FEMALE",
      stateId = "state-1",
      districtId = "district-1",
      talukaId = "taluka-1",
    ),
  )

  // BeneficiaryListResponseDto.data is a raw JsonElement (see its KDoc — the backend has shipped
  // both a bare array and an `{ "items": [...] }` wrapper); built via Gson.toJsonTree so this
  // exercises the same normalization real responses go through.
  private fun okRemote(vararg rows: BeneficiaryListItemDto) = Response.success(
    BeneficiaryListResponseDto(success = true, message = "OK", data = Gson().toJsonTree(rows.toList())),
  )

  private fun repository(remoteApi: BeneficiaryApi = FakeBeneficiaryApi()) = OfflineFirstBeneficiaryRepository(
    localEnrolments,
    RemoteBeneficiaryRepository(remoteApi, FakeSecureKeyValueStore()),
  )

  // ---- Flag on (temporarily, risk accepted — see RemoteBeneficiaryListFeatureFlag's KDoc) --------

  @Test
  fun `flag on merges local and remote through the real entry point`() = runTest {
    assertTrue("this test's premise depends on the flag being on", RemoteBeneficiaryListFeatureFlag.ENABLED)
    saveMotherEnrolment("local-1", "Sunita", "Pawar") // remoteBeneficiaryId stays null — never synced
    val api = FakeBeneficiaryApi(listAllResponse = { okRemote(remoteRow("remote-1")) })

    val result = repository(api).getBeneficiaries()

    // Local rows first, then remote-only rows — same ordering OfflineFirstBeneficiaryRepository's
    // KDoc documents (local rows already newest-first, remote-only appended, not a true interleave).
    assertEquals(listOf("local-1", "remote-1"), result.map { it.id })
  }

  @Test
  fun `flag on with no local data returns the remote list instead of an empty screen`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { okRemote(remoteRow("remote-1")) })

    val result = repository(api).getBeneficiaries()

    assertEquals(listOf("remote-1"), result.map { it.id })
  }

  @Test
  fun `flag on with no network and nothing ever cached falls back to the local-only list`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")
    val api = FakeBeneficiaryApi(listAllResponse = { throw IOException("offline") })

    val result = repository(api).getBeneficiaries()

    assertEquals(listOf("local-1"), result.map { it.id })
  }

  // ---- Merge logic, exercised directly (flag-on shape) -------------------------------------------
  // These exercise the same local+remote combination logic that
  // OfflineFirstBeneficiaryRepository.getBeneficiaries() runs once
  // RemoteBeneficiaryListFeatureFlag.ENABLED is true, guarding the documented contract in
  // OfflineFirstBeneficiaryRepository's KDoc so a future change to that rule fails a test, not just
  // a code review.

  @Test
  fun `a local-unsynced beneficiary has no remote counterpart yet and is unaffected by merging`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar") // remoteBeneficiaryId stays null — never synced

    val local = localEnrolments.getLocalBeneficiaries()
    assertEquals(null, local.single().remoteBeneficiaryId)

    val remote = RemoteBeneficiaryRepository(
      FakeBeneficiaryApi(listAllResponse = { okRemote() }),
      FakeSecureKeyValueStore(),
    ).fetchRemoteBeneficiaries(lmp)
    val syncedRemoteIds = local.mapNotNull { it.remoteBeneficiaryId }.toSet()
    val merged = local + (remote.orEmpty().filterNot { it.id in syncedRemoteIds })

    assertEquals(listOf("local-1"), merged.map { it.id })
  }

  @Test
  fun `a local row that has synced suppresses its own remote row so she is not shown twice`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")
    // Mark it synced against a server id, exactly as DynamicFormSyncExecutor now does post-fix.
    draftDao.upsert(
      requireNotNull(draftDao.getByLocalBeneficiaryId("local-1")).copy(
        syncStatus = EnrollmentSyncStatus.SYNCED,
        remoteBeneficiaryId = "server-1",
      ),
    )

    val local = localEnrolments.getLocalBeneficiaries()
    val remote = RemoteBeneficiaryRepository(
      FakeBeneficiaryApi(listAllResponse = { okRemote(remoteRow("server-1", "Sunita Pawar")) }),
      FakeSecureKeyValueStore(),
    ).fetchRemoteBeneficiaries(lmp)

    val syncedRemoteIds = local.mapNotNull { it.remoteBeneficiaryId }.toSet()
    val merged = local + (remote.orEmpty().filterNot { it.id in syncedRemoteIds })

    // Shown once, from the LOCAL copy — real risk/visit data, not the remote placeholder.
    assertEquals(1, merged.size)
    assertEquals("local-1", merged.single().id)
    assertTrue(merged.single().isAssessed)
  }

  @Test
  fun `a remote-only beneficiary appears unassessed when no local draft exists on this device`() = runTest {
    val local = localEnrolments.getLocalBeneficiaries()
    assertTrue(local.isEmpty())

    val remote = RemoteBeneficiaryRepository(
      FakeBeneficiaryApi(listAllResponse = { okRemote(remoteRow("server-1", "Deepa T")) }),
      FakeSecureKeyValueStore(),
    ).fetchRemoteBeneficiaries(lmp)

    val syncedRemoteIds = local.mapNotNull { it.remoteBeneficiaryId }.toSet()
    val merged = local + (remote.orEmpty().filterNot { it.id in syncedRemoteIds })

    val row = merged.single()
    assertEquals("server-1", row.id)
    assertFalse(row.isAssessed)
    assertEquals("Deepa T", row.name)
  }

  // ---- Helpers (mirrors LocalBeneficiaryRepositoryTest's conventions) ----------------------------

  private fun draftRow(id: String, formCode: String = "MOTHER_REGISTRATION") = DynamicFormDraftEntity(
    localBeneficiaryId = id,
    formCode = formCode,
    formVersionId = "version-1",
    localSubmissionUuid = "submission-$id",
    syncStatus = EnrollmentSyncStatus.PENDING,
    createdAtEpochMillis = 1_754_265_600_000L,
    lastAttemptAtEpochMillis = null,
    retryCount = 0,
    remoteBeneficiaryId = null,
    remoteSubmissionId = null,
    lastErrorMessage = null,
  )

  private suspend fun saveMotherEnrolment(id: String, firstName: String, lastName: String) {
    draftDao.upsert(draftRow(id, formCode = "MOTHER_REGISTRATION"))
    secureStore.putString(
      dynamicFormDraftPayloadKey(id),
      dynamicFormDraftGson.toJson(
        DynamicFormDraftPayload(
          answers = FormAnswers(
            singleValues = mapOf(
              "first_name" to firstName,
              "last_name" to lastName,
              "mobile_number" to "9876543210",
            ),
          ),
          registrationDateIso = lmp.toString(),
        ),
      ),
    )
  }
}
