package org.armman.sakhi.data.childregistration

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.EnrollmentMappingException
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ChildRegistrationSubmissionMapperTest {

  private lateinit var sessionStore: SessionStore
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var mapper: ChildRegistrationSubmissionMapper

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
    // The default fake seeds BENEFICIARY_TYPE with PREGNANT_WOMAN/INFANT — the child flow needs a
    // CHILD value_code in both categories (see mapper). CASE_TYPE already has a CHILD value.
    lookupRepository.valuesByCategory["BENEFICIARY_TYPE"] = listOf(
      LookupValue(id = "lookup-ben-child", valueCode = "CHILD", valueLabel = "Child"),
    )
    mapper = ChildRegistrationSubmissionMapper(sessionStore, lookupRepository)
  }

  /** Registered-mother path child with a full, valid answer set. */
  private fun answeredForm(
    consent: String = "yes",
    path: String = "child_of_a_registered_pregnant_woman",
    name: String = "Aarav Kumar Sharma",
    sex: String = "male",
    term: String = "full_term",
  ) = FormAnswers(
    singleValues = mapOf(
      "who_are_you_registering_in_the_program" to path,
      "mother_beneficiary_id" to "mother-123",
      "did_we_receive_consent" to consent,
      "date_of_birth_of_infant" to "2026-05-01",
      "name_of_the_child" to name,
      "sex_of_child" to sex,
      "child_length_at_birth_in_cm" to "50",
      "child_weight_at_birth_in_kg" to "3.2",
      "term_of_delivery" to term,
      "mobile_number" to "9876543210",
      "enter_the_beneficiary_address" to "Pada 4, Dhadgaon",
      "registrtion_date" to "2026-07-20",
      "name_of_the_state" to "state-1",
      "name_of_district" to "district-1",
      "name_of_block_taluka" to "block-1",
      "name_of_the_revenue_village_grampanchayat" to "village-1",
      "beneficary_pada_name" to "pada-1",
      "beneficary_phc_name" to "phc-1",
      "name_of_sub_center" to "subcentre-1",
      "caregiver_name_first_name_middle_name_last_name" to "Meena Sharma",
    ),
    multiValues = mapOf("vaccinations_given" to listOf("bcg")),
  )

  @Test
  fun `maps the child onto the beneficiaries DTO with CHILD case type and resolved lookups`() = runTest {
    sessionStore.saveSession(session)

    val dto = mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(), LocalDate.of(2026, 7, 20))
      .getOrThrow()

    assertEquals("CHILD", dto.case.caseType)
    assertEquals("project-uuid-1", dto.case.projectId)
    assertEquals("sakhi-uuid-1", dto.case.sakhiId)
    assertEquals("local-case-1", dto.case.localCaseUuid)
    assertEquals("2026-07-20", dto.case.registrationDate)
    assertEquals("lookup-case-child", dto.case.caseTypeLookupId)
    assertEquals("lookup-ben-child", dto.case.beneficiaryTypeLookupId)
    assertEquals("MALE", dto.pii.sex)
    assertEquals("9876543210", dto.pii.phone)
    assertEquals("2026-05-01", dto.pii.dateOfBirth)
    assertEquals("Pada 4, Dhadgaon", dto.pii.addressLine)
    assertEquals("NA", dto.pii.rchNumber)
    assertNull(dto.motherDetails)
  }

  @Test
  fun `geography ids map onto the pii block`() = runTest {
    sessionStore.saveSession(session)

    val dto = mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(), LocalDate.of(2026, 7, 20))
      .getOrThrow()

    assertEquals("state-1", dto.pii.stateId)
    assertEquals("district-1", dto.pii.districtId)
    assertEquals("block-1", dto.pii.talukaId)
    assertEquals("village-1", dto.pii.villageId)
    assertEquals("pada-1", dto.pii.padaId)
    assertEquals("phc-1", dto.pii.phcId)
    assertEquals("subcentre-1", dto.pii.healthSubCentreId)
    assertNull(dto.pii.healthBlockId)
  }

  @Test
  fun `childDetails maps dob, sex, weight, length and prematureFlag`() = runTest {
    sessionStore.saveSession(session)

    val child = requireNotNull(
      mapper.toCreateBeneficiaryRequest("local-case-1", answeredForm(), LocalDate.of(2026, 7, 20))
        .getOrThrow().childDetails,
    )

    assertEquals("2026-05-01", child.dateOfBirth)
    assertEquals("MALE", child.sex)
    assertEquals(3.2, child.birthWeightKg!!, 0.0001)
    assertEquals(50.0, child.birthLengthCm!!, 0.0001)
    assertEquals(false, child.prematureFlag)
  }

  @Test
  fun `prematureFlag is true for pre_term, false for full_term and post_term, null for unknown`() = runTest {
    sessionStore.saveSession(session)

    suspend fun premature(term: String) =
      mapper.toCreateBeneficiaryRequest("c", answeredForm(term = term), LocalDate.of(2026, 7, 20))
        .getOrThrow().childDetails!!.prematureFlag

    assertEquals(true, premature("pre_term"))
    assertEquals(false, premature("full_term"))
    assertEquals(false, premature("post_term"))
    assertNull(premature("something_else"))
  }

  @Test
  fun `sex maps male female intersex_other to MALE FEMALE OTHER`() = runTest {
    sessionStore.saveSession(session)

    suspend fun sex(code: String) =
      mapper.toCreateBeneficiaryRequest("c", answeredForm(sex = code), LocalDate.of(2026, 7, 20))
        .getOrThrow().pii.sex

    assertEquals("MALE", sex("male"))
    assertEquals("FEMALE", sex("female"))
    assertEquals("OTHER", sex("intersex_other"))
  }

  @Test
  fun `name splits into first, middle and last across 3, 2 and 1 token forms`() = runTest {
    sessionStore.saveSession(session)

    val three = mapper.toCreateBeneficiaryRequest("c", answeredForm(name = "Aarav Kumar Sharma"), LocalDate.of(2026, 7, 20))
      .getOrThrow().pii
    assertEquals("Aarav", three.firstName)
    assertEquals("Kumar", three.middleName)
    assertEquals("Sharma", three.lastName)

    val two = mapper.toCreateBeneficiaryRequest("c", answeredForm(name = "Aarav Sharma"), LocalDate.of(2026, 7, 20))
      .getOrThrow().pii
    assertEquals("Aarav", two.firstName)
    assertNull(two.middleName)
    assertEquals("Sharma", two.lastName)

    val one = mapper.toCreateBeneficiaryRequest("c", answeredForm(name = "Aarav"), LocalDate.of(2026, 7, 20))
      .getOrThrow().pii
    assertEquals("Aarav", one.firstName)
    assertNull(one.middleName)
    assertEquals("Aarav", one.lastName)
  }

  @Test
  fun `motherBeneficiaryId is set on the registered-mother path`() = runTest {
    sessionStore.saveSession(session)

    val dto = mapper.toCreateBeneficiaryRequest(
      "c",
      answeredForm(path = "child_of_a_registered_pregnant_woman"),
      LocalDate.of(2026, 7, 20),
    ).getOrThrow()

    assertEquals("mother-123", dto.case.motherBeneficiaryId)
  }

  @Test
  fun `motherBeneficiaryId is null on the direct path even if a value is present`() = runTest {
    sessionStore.saveSession(session)

    val dto = mapper.toCreateBeneficiaryRequest(
      "c",
      answeredForm(path = "child_directly_mother_not_registered_in_the_program"),
      LocalDate.of(2026, 7, 20),
    ).getOrThrow()

    assertNull(dto.case.motherBeneficiaryId)
  }

  @Test
  fun `consent not given blocks submission`() = runTest {
    sessionStore.saveSession(session)

    val result = mapper.toCreateBeneficiaryRequest("c", answeredForm(consent = "no"), LocalDate.now())

    assertTrue(result.exceptionOrNull() is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `missing infant dob blocks submission with a specific message`() = runTest {
    sessionStore.saveSession(session)
    val form = answeredForm().let { it.copy(singleValues = it.singleValues - "date_of_birth_of_infant") }

    val error = mapper.toCreateBeneficiaryRequest("c", form, LocalDate.of(2026, 7, 20)).exceptionOrNull()

    assertTrue(error is EnrollmentMappingException.CrossFieldValidation)
    assertEquals(
      "Date of birth is required and must be a valid date",
      (error as EnrollmentMappingException.CrossFieldValidation).rule,
    )
  }

  @Test
  fun `unparseable infant dob blocks submission with the same message`() = runTest {
    sessionStore.saveSession(session)
    val form = answeredForm().let {
      it.copy(singleValues = it.singleValues + mapOf("date_of_birth_of_infant" to "01 May 2026"))
    }

    val error = mapper.toCreateBeneficiaryRequest("c", form, LocalDate.of(2026, 7, 20)).exceptionOrNull()

    assertTrue(error is EnrollmentMappingException.CrossFieldValidation)
  }

  @Test
  fun `case type lookup fail-fasts when the category is not seeded`() = runTest {
    sessionStore.saveSession(session)
    lookupRepository.valuesByCategory["CASE_TYPE"] = emptyList()

    val error = mapper.toCreateBeneficiaryRequest("c", answeredForm(), LocalDate.now()).exceptionOrNull()

    assertTrue(error is EnrollmentMappingException.LookupNotAvailable)
    assertEquals("CASE_TYPE", (error as EnrollmentMappingException.LookupNotAvailable).categoryCode)
  }

  @Test
  fun `beneficiary type lookup fail-fasts when the category is not seeded`() = runTest {
    sessionStore.saveSession(session)
    lookupRepository.valuesByCategory["BENEFICIARY_TYPE"] = emptyList()

    val error = mapper.toCreateBeneficiaryRequest("c", answeredForm(), LocalDate.now()).exceptionOrNull()

    assertTrue(error is EnrollmentMappingException.LookupNotAvailable)
    assertEquals("BENEFICIARY_TYPE", (error as EnrollmentMappingException.LookupNotAvailable).categoryCode)
  }

  @Test
  fun `no session fails with NoActiveSession`() = runTest {
    val error = mapper.toCreateBeneficiaryRequest("c", answeredForm(), LocalDate.now()).exceptionOrNull()

    assertTrue(error is EnrollmentMappingException.NoActiveSession)
  }

  @Test
  fun `form submission data includes every answer, both single and multi, but not beneficiary_id`() {
    val formData = mapper.toFormSubmissionData(answeredForm())

    // Beneficiary-DTO fields must still be present (the submissions endpoint validates the full
    // schema and 422s on any absent required field).
    assertEquals("Aarav Kumar Sharma", formData["name_of_the_child"])
    assertEquals("male", formData["sex_of_child"])
    assertEquals("state-1", formData["name_of_the_state"])
    assertEquals("yes", formData["did_we_receive_consent"])
    // Multi-value answers are carried through as a list.
    assertEquals(listOf("bcg"), formData["vaccinations_given"])
    // beneficiary_id is injected by the coordinator from the server response, never by the mapper.
    assertTrue("beneficiary_id" !in formData)
  }
}
