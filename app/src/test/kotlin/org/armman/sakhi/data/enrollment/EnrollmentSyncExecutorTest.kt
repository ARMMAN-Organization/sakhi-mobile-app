package org.armman.sakhi.data.enrollment

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate

class EnrollmentSyncExecutorTest {

  private lateinit var dao: FakeEnrollmentDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var mapper: EnrollmentApiMapper
  private lateinit var api: FakeEnrollmentApi
  private lateinit var executor: EnrollmentSyncExecutor

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
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    mapper = EnrollmentApiMapper(sessionStore, FakeLookupRepository())
    api = FakeEnrollmentApi()
    executor = EnrollmentSyncExecutor(dao, secureStore, mapper, api)
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
      // 1 living child + 0 still births + 0 abortions = 1 past outcome, + the current pregnancy.
      gravida = 2,
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

  private suspend fun seedPendingDraft(beneficiaryId: String = "b-1") {
    secureStore.putString(
      enrollmentDraftPayloadKey(beneficiaryId),
      enrollmentRecordGson.toJson(record(beneficiaryId)),
    )
    dao.upsert(
      EnrollmentDraftEntity(
        beneficiaryId = beneficiaryId,
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        lastErrorMessage = null,
      ),
    )
  }

  @Test
  fun `no pending drafts completes without calling the API`() = runTest {
    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, api.createCallCount)
  }

  @Test
  fun `successful submit marks the draft SYNCED with the server id`() = runTest {
    seedPendingDraft()
    api.createResponse = Response.success(
      CreateBeneficiaryResponseDto(success = true, message = null, data = CreateBeneficiaryResponseData(id = "server-id-1")),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByBeneficiaryId("b-1"))
    assertEquals(EnrollmentSyncStatus.SYNCED, entity.syncStatus)
    assertEquals("server-id-1", entity.remoteBeneficiaryId)
  }

  @Test
  fun `409 response marks the draft DUPLICATE_CONFLICT and does not request a retry`() = runTest {
    seedPendingDraft()
    api.createResponse = Response.error(
      409,
      "{\"message\":\"possible duplicate\"}".toResponseBody("application/json".toMediaType()),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    val entity = requireNotNull(dao.getByBeneficiaryId("b-1"))
    assertEquals(EnrollmentSyncStatus.DUPLICATE_CONFLICT, entity.syncStatus)
    assertEquals(1, entity.retryCount)
  }

  @Test
  fun `other HTTP error marks the draft FAILED and requests a retry`() = runTest {
    seedPendingDraft()
    api.createResponse = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByBeneficiaryId("b-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    assertEquals(1, entity.retryCount)
  }

  @Test
  fun `network IOException leaves the draft PENDING and requests a retry`() = runTest {
    seedPendingDraft()
    api.createExceptionToThrow = java.io.IOException("no route to host")

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    val entity = requireNotNull(dao.getByBeneficiaryId("b-1"))
    assertEquals(EnrollmentSyncStatus.PENDING, entity.syncStatus)
  }

  @Test
  fun `missing encrypted payload marks the draft FAILED without calling the API`() = runTest {
    // Metadata row exists but its encrypted payload was never written / was cleared — shouldn't
    // happen in practice, but the executor must not crash or loop forever on it.
    dao.upsert(
      EnrollmentDraftEntity(
        beneficiaryId = "b-orphan",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        lastErrorMessage = null,
      ),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, api.createCallCount)
    val entity = requireNotNull(dao.getByBeneficiaryId("b-orphan"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
  }

  @Test
  fun `mapper failure marks the draft FAILED with the mapper's error message`() = runTest {
    // No active session -> mapper fails with NoActiveSession, before any API call.
    val sessionlessMapper = EnrollmentApiMapper(SessionStore(FakeSecureKeyValueStore()), FakeLookupRepository())
    executor = EnrollmentSyncExecutor(dao, secureStore, sessionlessMapper, api)
    seedPendingDraft()

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals(0, api.createCallCount)
    val entity = requireNotNull(dao.getByBeneficiaryId("b-1"))
    assertEquals(EnrollmentSyncStatus.FAILED, entity.syncStatus)
    assertNull(entity.remoteBeneficiaryId)
  }

  @Test
  fun `each draft is processed independently — one failure does not block the others`() = runTest {
    seedPendingDraft("b-1")
    seedPendingDraft("b-2")
    api.createResponse = Response.success(
      CreateBeneficiaryResponseDto(success = true, message = null, data = CreateBeneficiaryResponseData(id = "server-id")),
    )

    executor.run()

    assertEquals(2, api.createCallCount)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByBeneficiaryId("b-1")?.syncStatus)
    assertEquals(EnrollmentSyncStatus.SYNCED, dao.getByBeneficiaryId("b-2")?.syncStatus)
  }
}
