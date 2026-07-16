package org.armman.sakhi.data.enrollment

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** CR-015d unit tests — spec: docs/test-cases/enrollment.md (SR-1..4). */
class StaticEnrollmentRepositoryTest {

  private val repository = StaticEnrollmentRepository()

  @Test
  fun `SR-1 save stores record retrievable by id`() = runTest {
    val record = record(beneficiaryId = "b-1")

    val result = repository.saveEnrollment(record)

    assertTrue(result.isSuccess)
    assertEquals(record, repository.getEnrollment("b-1"))
    assertNull(repository.getEnrollment("unknown"))
  }

  @Test
  fun `SR-2 save is idempotent per beneficiary id`() = runTest {
    repository.saveEnrollment(record(beneficiaryId = "b-1", firstName = "Reema"))
    repository.saveEnrollment(record(beneficiaryId = "b-1", firstName = "Rekha"))

    val stored = repository.getEnrollment("b-1")
    assertEquals("Rekha", stored?.firstName)
  }

  @Test
  fun `SR-3 records survive across consumer scopes`() = runTest {
    // Singleton semantics: the same instance serves successive ViewModels.
    repository.saveEnrollment(record(beneficiaryId = "b-1"))

    val laterConsumerView: EnrollmentRepository = repository
    assertEquals("b-1", laterConsumerView.getEnrollment("b-1")?.beneficiaryId)
  }

  @Test
  fun `SR-4 answers stored as numeric codes`() = runTest {
    repository.saveEnrollment(record(beneficiaryId = "b-1"))

    val stored = requireNotNull(repository.getEnrollment("b-1"))
    // 1-based codes, not display strings (Excel: numeric storage).
    assertEquals(1, stored.phoneOwner)
    assertEquals(5, stored.networkAvailability)
    assertEquals(4, stored.educationSelf)
    assertEquals(3, stored.religion)
    assertTrue(stored.consent.willingPersonalInfo)
  }

  private fun record(beneficiaryId: String, firstName: String = "Reema"): EnrollmentRecord {
    val lmp = LocalDate.now().minusDays(90)
    return EnrollmentRecord(
      beneficiaryId = beneficiaryId,
      registrationDate = LocalDate.now(),
      projectName = "Project X",
      lmp = lmp,
      edd = lmp.plusDays(280),
      gestationalAgeWeeks = 12,
      stateId = "MH",
      districtId = "MH-PAL",
      blockId = "MH-PAL-JAW",
      villageId = "V-BHAV",
      padaId = "P-CHAU",
      phcId = "PHC-JAW",
      subCentreId = "SC-BHAV",
      firstName = firstName,
      middleName = "Manish",
      lastName = "Powra",
      dob = null,
      ageYears = 25,
      address = "203, Pada 4, MG Road",
      mobileNumber = "9740887212",
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
      // A fully valid post-validation snapshot — first pregnancy (gravida = 1),
      // so the last-pregnancy block is legitimately absent. Conditional fields
      // are consistent with their gating answers (RCH card available ⇒ number
      // present; ANC not started ⇒ no ANC-1 details) and the mandatory
      // multi-selects carry their "none/no" codes, mirroring what the
      // ViewModel would produce after passing validation.
      healthHistory = HealthHistorySnapshot(
        trimester = 2,
        plannedPregnancy = 1,
        tookTreatment = false,
        treatmentType = null,
        rchStatus = 1,
        rchNumber = "RCH-000123",
        ancStatus = 1,
        anc1Date = null,
        ancConditions = emptySet(),
        tdNone = true,
        td1Date = null,
        td2Date = null,
        tdBoosterDate = null,
        gravida = 1,
        para = 0,
        livingChildren = 0,
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
        selfConditions = setOf(1),
        longTermMeds = setOf(1),
        sickleCell = 1,
        substanceUse = setOf(1),
        familyHistory = false,
        familyConditions = emptySet(),
        malnutrition = null,
        remarks = null,
      ),
      submittedAt = Instant.now(),
    )
  }
}
