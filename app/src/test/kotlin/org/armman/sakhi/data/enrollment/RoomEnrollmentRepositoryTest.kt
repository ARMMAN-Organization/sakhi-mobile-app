package org.armman.sakhi.data.enrollment

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class RoomEnrollmentRepositoryTest {

  private lateinit var dao: FakeEnrollmentDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var syncScheduler: FakeEnrollmentSyncScheduler
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var api: FakeEnrollmentApi
  private lateinit var syncExecutor: EnrollmentSyncExecutor
  private lateinit var repository: RoomEnrollmentRepository

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
    dao = FakeEnrollmentDraftDao()
    secureStore = FakeSecureKeyValueStore()
    syncScheduler = FakeEnrollmentSyncScheduler()
    connectivityChecker = FakeConnectivityChecker(online = true)
    api = FakeEnrollmentApi()
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val mapper = EnrollmentApiMapper(sessionStore, FakeLookupRepository())
    // Reuses the same dao/secureStore as the repository so runOne() sees the row saveEnrollment
    // just wrote — matching how the real Hilt graph wires a single instance of each.
    syncExecutor = EnrollmentSyncExecutor(dao, secureStore, mapper, api)
    repository = RoomEnrollmentRepository(dao, secureStore, connectivityChecker, syncExecutor)
  }

  private fun record(beneficiaryId: String = "b-1") = EnrollmentRecord(
    beneficiaryId = beneficiaryId,
    registrationDate = LocalDate.of(2026, 7, 20),
    projectName = "Project X",
    lmp = LocalDate.of(2026, 5, 1),
    edd = LocalDate.of(2027, 2, 4),
    gestationalAgeWeeks = 12,
    stateId = "state-1",
    districtId = "district-1",
    blockId = "block-1",
    villageId = "village-1",
    padaId = "pada-1",
    phcId = "phc-1",
    subCentreId = "subcentre-1",
    firstName = "Jane",
    middleName = "",
    lastName = "Doe",
    dob = LocalDate.of(1998, 5, 14),
    ageYears = 28,
    address = "203, Pada 4, MG Road",
    mobileNumber = "9876543210",
    phoneOwner = 1,
    networkAvailability = 5,
    educationSelf = 4,
    educationPartner = 4,
    partnerOccupation = 1,
    yearsInVillage = 5,
    migrationPattern = 1,
    incomeBand = 1,
    religion = 3,
    category = 5,
    householdMembers = 5,
    childrenUnderFive = 1,
    consent = ConsentSnapshot(
      willingPersonalInfo = true,
      willingHealthHistory = true,
      willingDiagnosticTests = true,
      understandsReferral = true,
      consentReceived = null,
      photoUri = null,
    ),
    healthHistory = HealthHistorySnapshot(
      trimester = 2,
      plannedPregnancy = 1,
      tookTreatment = false,
      treatmentType = null,
      rchStatus = 1,
      rchNumber = "RCH123456",
      ancStatus = 1,
      anc1Date = null,
      ancConditions = emptySet(),
      tdNone = true,
      td1Date = null,
      td2Date = null,
      tdBoosterDate = null,
      gravida = 1,
      para = 0,
      livingChildren = 1,
      abortions = 0,
      stillBirths = 0,
      deadChildren = null,
      lastPregnancyWhen = null,
      deliveryComplications = emptySet(),
      lastDeliveryDuration = null,
      lastDeliveryType = null,
      lastDeliveryPlace = null,
      lastDeliveryOutcome = null,
      birthWeight = null,
      selfConditions = emptySet(),
      longTermMeds = emptySet(),
      sickleCell = 1,
      substanceUse = emptySet(),
      familyHistory = false,
      familyConditions = emptySet(),
      malnutrition = null,
      remarks = null,
    ),
    heightCm = 158.0,
    weightKg = 55.0,
    submittedAt = Instant.now(),
  )

  @Test
  fun `saveEnrollment persists an encrypted payload and a PENDING metadata row`() = runTest {
    val result = repository.saveEnrollment(record())

    assertTrue(result.isSuccess)
    val entity = dao.getByBeneficiaryId("b-1")
    assertEquals(EnrollmentSyncStatus.PENDING, entity?.syncStatus)
    assertEquals(0, entity?.retryCount)
    assertNull(entity?.remoteBeneficiaryId)
  }

  @Test
  fun `saveEnrollment schedules no upload - sync is the Sakhi's manual Data Upload action`() = runTest {
    repository.saveEnrollment(record())

    // SRS 3A.1 is manual-trigger-only; see the equivalent test on the mother queue.
    assertEquals(0, syncScheduler.syncNowCallCount)
  }

  @Test
  fun `getEnrollment round-trips the exact record via the encrypted store`() = runTest {
    val original = record()
    repository.saveEnrollment(original)

    val loaded = repository.getEnrollment("b-1")

    assertEquals(original, loaded)
  }

  @Test
  fun `getEnrollment returns null for a beneficiaryId never saved`() = runTest {
    assertNull(repository.getEnrollment("never-saved"))
  }

  @Test
  fun `re-saving a SYNCED draft resets it to PENDING so the worker retries it`() = runTest {
    repository.saveEnrollment(record())
    dao.upsert(
      requireNotNull(dao.getByBeneficiaryId("b-1")).copy(
        syncStatus = EnrollmentSyncStatus.SYNCED,
        remoteBeneficiaryId = "server-id-1",
        retryCount = 2,
      ),
    )

    repository.saveEnrollment(record())

    val entity = requireNotNull(dao.getByBeneficiaryId("b-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
    // createdAt/retryCount/remoteBeneficiaryId survive the re-save — only status resets.
    assertEquals(2, entity.retryCount)
    assertEquals("server-id-1", entity.remoteBeneficiaryId)
  }

  // --- submitEnrollment: the submit-then-navigate fix -----------------------------------------

  @Test
  fun `submitEnrollment online success returns Synced and marks the draft SYNCED`() = runTest {
    connectivityChecker.online = true
    api.createResponse = Response.success(
      CreateBeneficiaryResponseDto(success = true, message = null, data = CreateBeneficiaryResponseData(id = "server-id-1")),
    )

    val result = repository.submitEnrollment(record())

    assertEquals(EnrollmentSubmitResult.Synced, result)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByBeneficiaryId("b-1")?.syncStatus)
  }

  @Test
  fun `submitEnrollment online validation failure returns Failed with the backend message, not navigation`() = runTest {
    connectivityChecker.online = true
    api.createResponse = Response.error(
      400,
      "{\"message\":\"pii.villageId: Invalid uuid\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = repository.submitEnrollment(record())

    assertTrue(result is EnrollmentSubmitResult.Failed)
    assertEquals("HTTP 400: {\"message\":\"pii.villageId: Invalid uuid\"}", (result as EnrollmentSubmitResult.Failed).message)
    // The local save still happened — offline-first guarantee holds even on a rejected submit —
    // but the caller must see Failed (not Synced/QueuedOffline) so the UI stays on-screen.
    assertEquals(EnrollmentSyncStatus.FAILED, dao.getByBeneficiaryId("b-1")?.syncStatus)
  }

  @Test
  fun `submitEnrollment online duplicate conflict returns DuplicateConflict`() = runTest {
    connectivityChecker.online = true
    api.createResponse = Response.error(
      409,
      "{\"message\":\"possible duplicate\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = repository.submitEnrollment(record())

    assertTrue(result is EnrollmentSubmitResult.DuplicateConflict)
    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, dao.getByBeneficiaryId("b-1")?.syncStatus)
  }

  @Test
  fun `submitEnrollment offline saves locally and returns QueuedOffline without calling the API or scheduling`() = runTest {
    connectivityChecker.online = false

    val result = repository.submitEnrollment(record())

    assertEquals(EnrollmentSubmitResult.QueuedOffline, result)
    assertEquals(0, api.createCallCount)
    assertEquals(0, syncScheduler.syncNowCallCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByBeneficiaryId("b-1")?.syncStatus)
  }

  @Test
  fun `submitEnrollment online but connectivity drops mid-call falls back to QueuedOffline`() = runTest {
    connectivityChecker.online = true
    api.createExceptionToThrow = IOException("no route to host")

    val result = repository.submitEnrollment(record())

    assertEquals(EnrollmentSubmitResult.QueuedOffline, result)
    assertEquals(0, syncScheduler.syncNowCallCount)
    assertEquals(EnrollmentSyncStatus.PENDING, dao.getByBeneficiaryId("b-1")?.syncStatus)
  }
}
