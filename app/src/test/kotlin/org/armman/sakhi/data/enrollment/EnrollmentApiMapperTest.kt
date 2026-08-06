package org.armman.sakhi.data.enrollment

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class EnrollmentApiMapperTest {

  private lateinit var sessionStore: SessionStore
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var mapper: EnrollmentApiMapper

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
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    lookupRepository = FakeLookupRepository()
    mapper = EnrollmentApiMapper(sessionStore, lookupRepository)
  }

  private fun validRecord(
    para: Int = 0,
    abortions: Int = 0,
    livingChildren: Int = 1,
    stillBirths: Int = 0,
    // 1 living child + 0 still births + 0 abortions = 1 past outcome, plus the current pregnancy.
    gravida: Int = 2,
    deadChildren: Int? = null,
  ): EnrollmentRecord {
    val lmp = LocalDate.of(2026, 5, 1)
    return EnrollmentRecord(
      beneficiaryId = "b-1",
      registrationDate = LocalDate.of(2026, 7, 20),
      projectName = "Project X",
      lmp = lmp,
      edd = lmp.plusDays(280),
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
        photoUri = "content://photo.jpg",
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
        gravida = gravida,
        para = para,
        livingChildren = livingChildren,
        abortions = abortions,
        stillBirths = stillBirths,
        deadChildren = deadChildren,
        lastPregnancyWhen = null,
        deliveryComplications = emptySet(),
        lastDeliveryDuration = null,
        lastDeliveryType = null,
        lastDeliveryPlace = null,
        lastDeliveryOutcome = null,
        birthWeight = null,
        selfConditions = setOf(1),
        longTermMeds = setOf(1),
        sickleCell = 1,
        substanceUse = setOf(1),
        familyHistory = false,
        familyConditions = emptySet(),
        malnutrition = null,
        remarks = null,
      ),
      heightCm = 158.0,
      weightKg = 55.0,
      submittedAt = Instant.now(),
    )
  }

  @Test
  fun `maps every field to the exact backend shape`() = runTest {
    sessionStore.saveSession(session)

    val result = mapper.toCreateBeneficiaryRequest(validRecord())

    assertTrue(result.isSuccess)
    val dto = result.getOrThrow()

    assertEquals("Jane", dto.pii.firstName)
    assertEquals(null, dto.pii.middleName) // blank middleName is sent as null, not ""
    assertEquals("Doe", dto.pii.lastName)
    assertEquals("9876543210", dto.pii.phone)
    assertEquals("1998-05-14", dto.pii.dateOfBirth)
    assertEquals("FEMALE", dto.pii.sex)
    assertEquals("village-1", dto.pii.villageId)
    assertEquals("pada-1", dto.pii.padaId)
    assertEquals("subcentre-1", dto.pii.healthSubCentreId) // naming reconciliation
    assertEquals("phc-1", dto.pii.phcId)
    assertNull(dto.pii.healthBlockId) // not collected — must stay null, not guessed
    assertEquals("state-1", dto.pii.stateId)
    assertEquals("district-1", dto.pii.districtId)
    assertEquals("block-1", dto.pii.talukaId) // block/taluka naming reconciliation
    assertEquals("RCH123456", dto.pii.rchNumber)

    assertEquals("project-uuid-1", dto.case.projectId)
    assertEquals("sakhi-uuid-1", dto.case.sakhiId)
    assertEquals("MOTHER", dto.case.caseType)
    assertEquals("2026-07-20", dto.case.registrationDate)
    assertEquals("lookup-case-mother", dto.case.caseTypeLookupId)
    assertEquals("lookup-ben-pw", dto.case.beneficiaryTypeLookupId)
    assertNull(dto.case.previousBeneficiaryId)
    assertNull(dto.case.motherBeneficiaryId)
    // CR-017: localCaseUuid reuses the record's own client-generated beneficiaryId so the same
    // value is sent on every retry of the same enrollment.
    assertEquals("b-1", dto.case.localCaseUuid)

    val mother = requireNotNull(dto.motherDetails)
    assertEquals("2026-05-01", mother.lmpDate)
    assertEquals(2, mother.gravida)
    assertEquals(0, mother.parity) // naming reconciliation: mobile "para" -> backend "parity"
    assertEquals(1, mother.liveBirths) // naming reconciliation: mobile "livingChildren" -> "liveBirths"
    assertEquals(0, mother.stillbirths)
    assertEquals(0, mother.abortions)
    assertNull(mother.deadChildren)
    assertEquals(158.0, mother.heightCm)
    assertEquals(55.0, mother.weightKg)

    assertNull(dto.childDetails)
    assertEquals("GIVEN", dto.consent.status)
    assertEquals("2026-07-20", dto.consent.date)
    assertNull(dto.acknowledgeDuplicate)
  }

  @Test
  fun `acknowledgeDuplicate true is passed through, false becomes null`() = runTest {
    sessionStore.saveSession(session)

    val withAck = mapper.toCreateBeneficiaryRequest(validRecord(), acknowledgeDuplicate = true).getOrThrow()
    val withoutAck = mapper.toCreateBeneficiaryRequest(validRecord(), acknowledgeDuplicate = false).getOrThrow()

    assertEquals(true, withAck.acknowledgeDuplicate)
    assertNull(withoutAck.acknowledgeDuplicate)
  }

  @Test
  fun `no session fails with NoActiveSession`() = runTest {
    val result = mapper.toCreateBeneficiaryRequest(validRecord())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.NoActiveSession)
  }

  @Test
  fun `session with no projectId fails with MissingProjectId`() = runTest {
    sessionStore.saveSession(session.copy(projectId = null))

    val result = mapper.toCreateBeneficiaryRequest(validRecord())

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.MissingProjectId)
  }

  @Test
  fun `case type lookup not seeded fails with LookupNotAvailable`() = runTest {
    sessionStore.saveSession(session)
    lookupRepository.valuesByCategory["CASE_TYPE"] = emptyList()

    val result = mapper.toCreateBeneficiaryRequest(validRecord())

    val error = result.exceptionOrNull()
    assertTrue(error is EnrollmentMappingException.LookupNotAvailable)
    assertEquals("CASE_TYPE", (error as EnrollmentMappingException.LookupNotAvailable).categoryCode)
  }

  @Test
  fun `beneficiary type lookup not seeded fails with LookupNotAvailable`() = runTest {
    sessionStore.saveSession(session)
    lookupRepository.valuesByCategory["BENEFICIARY_TYPE"] = emptyList()

    val result = mapper.toCreateBeneficiaryRequest(validRecord())

    val error = result.exceptionOrNull()
    assertTrue(error is EnrollmentMappingException.LookupNotAvailable)
    assertEquals("BENEFICIARY_TYPE", (error as EnrollmentMappingException.LookupNotAvailable).categoryCode)
  }

  @Test
  fun `parity exceeding gravida fails cross-field validation`() = runTest {
    sessionStore.saveSession(session)

    val result = mapper.toCreateBeneficiaryRequest(
      validRecord(gravida = 1, para = 2, livingChildren = 1, abortions = 0, stillBirths = 0),
    )

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `abortions exceeding gravida fails cross-field validation`() = runTest {
    sessionStore.saveSession(session)

    val result = mapper.toCreateBeneficiaryRequest(
      validRecord(gravida = 1, para = 0, livingChildren = 0, abortions = 2, stillBirths = 0),
    )

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `deadChildren exceeding liveBirths fails cross-field validation`() = runTest {
    sessionStore.saveSession(session)

    val result = mapper.toCreateBeneficiaryRequest(
      validRecord(gravida = 1, para = 0, livingChildren = 1, abortions = 0, stillBirths = 0, deadChildren = 2),
    )

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `liveBirths plus stillbirths plus abortions must equal gravida minus the current pregnancy`() = runTest {
    sessionStore.saveSession(session)

    // 1 (liveBirths) + 0 (stillbirths) + 0 (abortions) = 1, but gravida 3 implies 2 — must fail.
    val result = mapper.toCreateBeneficiaryRequest(
      validRecord(gravida = 3, para = 0, livingChildren = 1, abortions = 0, stillBirths = 0),
    )

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `valid cross-total passes`() = runTest {
    sessionStore.saveSession(session)

    // 1 (liveBirths) + 1 (stillbirths) + 1 (abortions) = 3 = gravida (4) - 1.
    val result = mapper.toCreateBeneficiaryRequest(
      validRecord(gravida = 4, para = 0, livingChildren = 1, abortions = 1, stillBirths = 1),
    )

    assertTrue(result.isSuccess)
  }

  @Test
  fun `blank optional name and address fields are omitted as null, not empty strings`() = runTest {
    sessionStore.saveSession(session)
    val record = validRecord().copy(middleName = "")

    val dto = mapper.toCreateBeneficiaryRequest(record).getOrThrow()

    assertEquals(null, dto.pii.middleName) // blank middleName is sent as null, not ""
  }

  @Test
  fun `dateOfBirth falls back to an approximate DOB when only age was entered`() = runTest {
    sessionStore.saveSession(session)
    val record = validRecord().copy(dob = null, ageYears = 28, registrationDate = LocalDate.of(2026, 7, 22))

    val dto = mapper.toCreateBeneficiaryRequest(record).getOrThrow()

    assertEquals("1998-07-22", dto.pii.dateOfBirth)
  }

  @Test
  fun `localCaseUuid is identical across repeated mapping calls for the same record (CR-017 retry safety)`() =
    runTest {
      sessionStore.saveSession(session)
      val record = validRecord()

      val first = mapper.toCreateBeneficiaryRequest(record).getOrThrow()
      val second = mapper.toCreateBeneficiaryRequest(record).getOrThrow()

      assertEquals(first.case.localCaseUuid, second.case.localCaseUuid)
      assertEquals(record.beneficiaryId, first.case.localCaseUuid)
    }

  @Test
  fun `finds lookup values regardless of category ordering in the fake`() = runTest {
    sessionStore.saveSession(session)
    lookupRepository.valuesByCategory["CASE_TYPE"] = listOf(
      LookupValue(id = "different-id", valueCode = "MOTHER", valueLabel = "Mother (renamed)"),
    )

    val dto = mapper.toCreateBeneficiaryRequest(validRecord()).getOrThrow()

    assertEquals("different-id", dto.case.caseTypeLookupId)
  }
}
