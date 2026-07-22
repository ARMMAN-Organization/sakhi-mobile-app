package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.EnrollmentMappingException
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DynamicFormSubmissionMapperTest {

  private lateinit var sessionStore: SessionStore
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var mapper: DynamicFormSubmissionMapper

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
    mapper = DynamicFormSubmissionMapper(sessionStore, lookupRepository)
  }

  private fun answeredForm(consent: String = "yes") = FormAnswers(
    singleValues = mapOf(
      "did_we_receive_consent" to consent,
      "lmp_date" to "2026-05-01",
      "gravida_total_number_of_pregnancies" to "2",
      "para_number_of_births_after_24_weeks" to "0",
      "living_children" to "1",
      "abortions_pregnancy_losses_before_24_weeks" to "1",
      "still_births" to "0",
      "dead_children" to "0",
      "first_name" to "Test",
      "last_name" to "Mother",
      "mobile_number" to "9876543210",
      "date_of_birth" to "1996-01-01",
      "enter_the_beneficiary_address" to "Pada 4, Dhadgaon",
      "input_rch_number" to "RCH-TEST-0003",
      "registrtion_date" to "2026-07-20",
      "name_of_the_state" to "state-1",
      "name_of_district" to "district-1",
      "name_of_block_taluka" to "block-1",
      "name_of_the_revenue_village_grampanchayat" to "village-1",
      "beneficary_pada_name" to "pada-1",
      "beneficary_phc_name" to "phc-1",
      "name_of_sub_center" to "subcentre-1",
      "trimester_of_preganancy" to "first",
      "remarks" to "Test remark",
    ),
    multiValues = mapOf(
      "did_you_experience_any_complications_during_birth_delivery_in_previous_pregnancies" to
        listOf("no_complications"),
    ),
  )

  @Test
  fun `maps known question codes onto the beneficiaries DTO`() = runTest {
    sessionStore.saveSession(session)

    val dto = mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(), LocalDate.of(2026, 7, 20))
      .getOrThrow()

    assertEquals("Test", dto.pii.firstName)
    assertEquals(null, dto.pii.middleName)
    assertEquals("Mother", dto.pii.lastName)
    assertEquals("9876543210", dto.pii.phone)
    assertEquals("1996-01-01", dto.pii.dateOfBirth)
    assertEquals("Pada 4, Dhadgaon", dto.pii.addressLine)
    assertEquals("village-1", dto.pii.villageId)
    assertEquals("pada-1", dto.pii.padaId)
    assertEquals("subcentre-1", dto.pii.healthSubCentreId)
    assertEquals("phc-1", dto.pii.phcId)
    assertEquals("state-1", dto.pii.stateId)
    assertEquals("district-1", dto.pii.districtId)
    assertEquals("block-1", dto.pii.talukaId)
    assertEquals("RCH-TEST-0003", dto.pii.rchNumber)

    assertEquals("local-case-1", dto.case.localCaseUuid)
    assertEquals("2026-07-20", dto.case.registrationDate)
    assertEquals("project-uuid-1", dto.case.projectId)
    assertEquals("sakhi-uuid-1", dto.case.sakhiId)

    val mother = requireNotNull(dto.motherDetails)
    assertEquals("2026-05-01", mother.lmpDate)
    assertEquals(2, mother.gravida)
    assertEquals(0, mother.parity)
    assertEquals(1, mother.liveBirths)
    assertEquals(1, mother.abortions)
    assertEquals(0, mother.stillbirths)
    assertEquals(0, mother.deadChildren)

    assertEquals("GIVEN", dto.consent.status)
  }

  @Test
  fun `consent not given blocks submission`() = runTest {
    sessionStore.saveSession(session)

    val result = mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(consent = "no"), LocalDate.now())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `no session fails with NoActiveSession`() = runTest {
    val result = mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(), LocalDate.now())

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.NoActiveSession)
  }

  @Test
  fun `form submission data includes the beneficiary-DTO fields too (backend validates full schema)`() {
    val formData = mapper.toFormSubmissionData(answeredForm())

    // Regression guard: these were previously stripped out, which made /submissions 422 with
    // "Missing required field" for every one of them. The submissions endpoint validates the
    // whole schema, so formData must carry them.
    assertEquals("2026-05-01", formData["lmp_date"])
    assertEquals("2", formData["gravida_total_number_of_pregnancies"])
    assertEquals("Test", formData["first_name"])
    assertEquals("Mother", formData["last_name"])
    assertEquals("1996-01-01", formData["date_of_birth"])
    assertEquals("state-1", formData["name_of_the_state"])
    assertEquals("yes", formData["did_we_receive_consent"])
  }

  @Test
  fun `form submission data does not set beneficiary_id (coordinator injects the server id)`() {
    // The Sakhi never answers beneficiary_id; it's added by the coordinator from the
    // POST /beneficiaries response, so the mapper must leave it out.
    assertTrue("beneficiary_id" !in mapper.toFormSubmissionData(answeredForm()))
  }

  @Test
  fun `form submission data includes everything else, both single and multi answers`() {
    val formData = mapper.toFormSubmissionData(answeredForm())

    assertEquals("first", formData["trimester_of_preganancy"])
    assertEquals("Test remark", formData["remarks"])
    assertEquals(
      listOf("no_complications"),
      formData["did_you_experience_any_complications_during_birth_delivery_in_previous_pregnancies"],
    )
  }

  @Test
  fun `beneficiary type and case type lookups are still resolved for the dynamic path`() = runTest {
    sessionStore.saveSession(session)
    lookupRepository.valuesByCategory["CASE_TYPE"] = emptyList()

    val result = mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(), LocalDate.now())

    val error = result.exceptionOrNull()
    assertTrue(error is EnrollmentMappingException.LookupNotAvailable)
    assertEquals("CASE_TYPE", (error as EnrollmentMappingException.LookupNotAvailable).categoryCode)
  }

  @Test
  fun `first, middle and last name are sent as discrete fields, blank middle becomes null`() = runTest {
    sessionStore.saveSession(session)
    val form = answeredForm().let {
      it.copy(singleValues = it.singleValues + mapOf("middle_name" to "Kumari"))
    }

    val dto = mapper.toCreateBeneficiaryRequest("local-case-1", form, LocalDate.of(2026, 7, 20)).getOrThrow()

    assertEquals("Test", dto.pii.firstName)
    assertEquals("Kumari", dto.pii.middleName)
    assertEquals("Mother", dto.pii.lastName)
  }

  @Test
  fun `rchNumber falls back to a placeholder when the question is hidden and unanswered`() = runTest {
    sessionStore.saveSession(session)
    // Simulates RCH status != "card available": input_rch_number is hidden by the form's own
    // visibleWhen rule and never answered, but the backend still requires pii.rchNumber non-empty.
    val form = answeredForm().let {
      it.copy(singleValues = it.singleValues - "input_rch_number")
    }

    val dto = mapper.toCreateBeneficiaryRequest("local-case-1", form, LocalDate.of(2026, 7, 20)).getOrThrow()

    assertEquals("NA", dto.pii.rchNumber)
  }

  @Test
  fun `missing dateOfBirth blocks submission with a specific message instead of reaching the network`() =
    runTest {
      sessionStore.saveSession(session)
      val form = answeredForm().let { it.copy(singleValues = it.singleValues - "date_of_birth") }

      val result = mapper.toCreateBeneficiaryRequest("local-case-1", form, LocalDate.of(2026, 7, 20))

      val error = result.exceptionOrNull()
      assertTrue(error is EnrollmentMappingException.CrossFieldValidation)
      assertEquals(
        "Date of birth is required and must be a valid date",
        (error as EnrollmentMappingException.CrossFieldValidation).rule,
      )
    }

  @Test
  fun `unparseable dateOfBirth blocks submission with the same specific message`() = runTest {
    sessionStore.saveSession(session)
    val form = answeredForm().let {
      it.copy(singleValues = it.singleValues + mapOf("date_of_birth" to "22 Jul 2009"))
    }

    val result = mapper.toCreateBeneficiaryRequest("local-case-1", form, LocalDate.of(2026, 7, 20))

    val error = result.exceptionOrNull()
    assertTrue(error is EnrollmentMappingException.CrossFieldValidation)
    assertEquals(
      "Date of birth is required and must be a valid date",
      (error as EnrollmentMappingException.CrossFieldValidation).rule,
    )
  }

  @Test
  fun `gravida cross-total mismatch blocks submission with a specific message`() = runTest {
    sessionStore.saveSession(session)
    // livingChildren(1) + stillBirths(0) + abortions(1) = 2, but gravida is set to 3 — mismatch.
    val form = answeredForm().let {
      it.copy(singleValues = it.singleValues + mapOf("gravida_total_number_of_pregnancies" to "3"))
    }

    val result = mapper.toCreateBeneficiaryRequest("local-case-1", form, LocalDate.of(2026, 7, 20))

    val error = result.exceptionOrNull()
    assertTrue(error is EnrollmentMappingException.CrossFieldValidation)
    assertEquals(
      "liveBirths + stillbirths + abortions must equal gravida",
      (error as EnrollmentMappingException.CrossFieldValidation).rule,
    )
  }
}
