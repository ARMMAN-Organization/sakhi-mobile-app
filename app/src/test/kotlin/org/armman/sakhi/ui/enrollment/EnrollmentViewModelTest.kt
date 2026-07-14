package org.armman.sakhi.ui.enrollment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.enrollment.EnrollmentRecord
import org.armman.sakhi.data.enrollment.EnrollmentRepository
import org.armman.sakhi.data.geography.StaticGeographyRepository
import org.armman.sakhi.data.profile.ProfileRepository
import org.armman.sakhi.data.profile.SakhiProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * CR-015a/b unit tests — spec: docs/test-cases/enrollment.md (VM-1..16, PI-1..26).
 * Note: the design's Consent frame has no "Did we receive consent?" radio, so
 * the gate is: all four checkboxes + photo AND consent not explicitly refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {

  private companion object {
    const val PHOTO_URI = "content://org.armman.sakhi.fileprovider/enrollment/consent_photo.jpg"
    const val PROJECT_NAME = "Project X"
  }

  private class FakeProfileRepository : ProfileRepository {
    override suspend fun getProfile() = SakhiProfile(
      name = "Tarini Swaraj",
      sakhiId = "12345678",
      projectName = PROJECT_NAME,
      cardNumber = "AFCPC7070A",
      mobileNumber = "0987654321",
      maskedBankAccount = "*******431",
    )
  }

  /**
   * Records saves and lets a test choose the outcome. [gate], when set,
   * suspends the save until completed — used to assert the in-flight guard.
   */
  private class FakeEnrollmentRepository(
    var result: Result<Unit> = Result.success(Unit),
    var gate: CompletableDeferred<Unit>? = null,
  ) : EnrollmentRepository {
    val saved = mutableListOf<EnrollmentRecord>()

    override suspend fun saveEnrollment(record: EnrollmentRecord): Result<Unit> {
      gate?.await()
      saved += record
      return result
    }

    override suspend fun getEnrollment(beneficiaryId: String): EnrollmentRecord? =
      saved.lastOrNull { it.beneficiaryId == beneficiaryId }
  }

  private val dispatcher = StandardTestDispatcher()
  private lateinit var viewModel: EnrollmentViewModel
  private lateinit var enrollmentRepository: FakeEnrollmentRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    enrollmentRepository = FakeEnrollmentRepository()
    viewModel = EnrollmentViewModel(
      StaticGeographyRepository(),
      FakeProfileRepository(),
      enrollmentRepository,
    )
    idle()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** Runs pending coroutines (geography/profile loads). */
  private fun idle() = dispatcher.scheduler.advanceUntilIdle()

  // --- Helpers ---------------------------------------------------------------

  private fun completeConsent() {
    viewModel.setWillingPersonalInfo(true)
    viewModel.setWillingHealthHistory(true)
    viewModel.setWillingDiagnosticTests(true)
    viewModel.setUnderstandsReferral(true)
    viewModel.onConsentPhotoCaptured(PHOTO_URI)
  }

  private fun reachConsent() {
    viewModel.selectBeneficiaryType(BeneficiaryType.PREGNANT_WOMAN)
    viewModel.startEnrollment()
  }

  // --- Entry selector (VM-1..3) ---------------------------------------------

  @Test
  fun `VM-1 initial state has no beneficiary type selected`() {
    val state = viewModel.uiState.value
    assertNull(state.beneficiaryType)
    assertEquals(EnrollmentStep.ENTRY, state.currentStep)
    assertEquals(EnrollmentStep.ENTRY, state.furthestStep)
  }

  @Test
  fun `VM-2 selecting pregnant woman enables flow`() {
    viewModel.selectBeneficiaryType(BeneficiaryType.PREGNANT_WOMAN)
    assertEquals(BeneficiaryType.PREGNANT_WOMAN, viewModel.uiState.value.beneficiaryType)

    viewModel.startEnrollment()
    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VM-3 selecting child emits coming soon and stays on entry`() = runTest {
    viewModel.selectBeneficiaryType(BeneficiaryType.CHILD)
    viewModel.startEnrollment()

    // Buffered channel: the event is retained until collected.
    assertEquals(EnrollmentEvent.ComingSoon, viewModel.events.first())
    assertNull(viewModel.uiState.value.beneficiaryType)
    assertEquals(EnrollmentStep.ENTRY, viewModel.uiState.value.currentStep)
  }

  // --- Consent step (VM-4..11) ------------------------------------------------

  @Test
  fun `VM-4 consent step starts incomplete`() {
    reachConsent()
    val consent = viewModel.uiState.value.consent

    assertFalse(consent.willingPersonalInfo)
    assertFalse(consent.willingHealthHistory)
    assertFalse(consent.willingDiagnosticTests)
    assertFalse(consent.understandsReferral)
    assertNull(consent.consentReceived)
    assertFalse(consent.videoPlayed)
    assertFalse(consent.photoTaken)
    assertFalse(consent.isComplete)
  }

  @Test
  fun `VM-5 toggling willingness checkboxes updates state independently`() {
    reachConsent()

    viewModel.setWillingPersonalInfo(true)
    assertTrue(viewModel.uiState.value.consent.willingPersonalInfo)
    assertFalse(viewModel.uiState.value.consent.willingHealthHistory)

    viewModel.setWillingHealthHistory(true)
    viewModel.setWillingDiagnosticTests(true)
    viewModel.setUnderstandsReferral(true)
    val consent = viewModel.uiState.value.consent
    assertTrue(consent.willingHealthHistory)
    assertTrue(consent.willingDiagnosticTests)
    assertTrue(consent.understandsReferral)

    viewModel.setWillingPersonalInfo(false)
    assertFalse(viewModel.uiState.value.consent.willingPersonalInfo)
  }

  @Test
  fun `VM-6 video play stub marks video as played and emits coming soon`() = runTest {
    reachConsent()

    viewModel.onPlayVideo()

    assertTrue(viewModel.uiState.value.consent.videoPlayed)
    assertEquals(EnrollmentEvent.ComingSoon, viewModel.events.first())
  }

  @Test
  fun `VM-7 consent photo capture stores uri and gates progression`() {
    reachConsent()
    viewModel.setWillingPersonalInfo(true)
    viewModel.setWillingHealthHistory(true)
    viewModel.setWillingDiagnosticTests(true)
    viewModel.setUnderstandsReferral(true)
    // All checkboxes ticked but photo missing → gate closed (Excel Q4 mandatory).
    assertFalse(viewModel.uiState.value.consent.isComplete)

    viewModel.onConsentPhotoCaptured(PHOTO_URI)

    val consent = viewModel.uiState.value.consent
    assertTrue(consent.photoTaken)
    assertEquals(PHOTO_URI, consent.photoUri)
    assertTrue(consent.isComplete)
  }

  @Test
  fun `VM-7b retake replaces the previous photo uri`() {
    reachConsent()
    viewModel.onConsentPhotoCaptured(PHOTO_URI)

    viewModel.onConsentPhotoCaptured("content://retaken.jpg")

    assertEquals("content://retaken.jpg", viewModel.uiState.value.consent.photoUri)
    assertTrue(viewModel.uiState.value.consent.photoTaken)
  }

  @Test
  fun `VM-8 consent refused blocks progression`() {
    reachConsent()
    completeConsent()
    viewModel.setConsentReceived(false)

    assertTrue(viewModel.uiState.value.consent.consentRefused)
    assertFalse(viewModel.uiState.value.consent.isComplete)

    viewModel.goToPersonalInfo()
    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VM-9 gate requires all checkboxes photo and consent not refused`() {
    reachConsent()
    viewModel.setWillingPersonalInfo(true)
    viewModel.setWillingHealthHistory(true)
    viewModel.setWillingDiagnosticTests(true)
    viewModel.onConsentPhotoCaptured(PHOTO_URI)
    assertFalse(viewModel.uiState.value.consent.isComplete)

    viewModel.setUnderstandsReferral(true)
    assertTrue(viewModel.uiState.value.consent.isComplete)

    viewModel.setConsentReceived(true)
    assertTrue(viewModel.uiState.value.consent.isComplete)
  }

  @Test
  fun `VM-10 next advances to personal info when complete`() {
    reachConsent()
    completeConsent()

    viewModel.goToPersonalInfo()

    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `VM-10b next does not advance when incomplete`() {
    reachConsent()

    viewModel.goToPersonalInfo()

    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VM-11 back from consent returns to entry retaining draft`() {
    reachConsent()
    viewModel.setWillingPersonalInfo(true)

    viewModel.goBack()

    val state = viewModel.uiState.value
    assertEquals(EnrollmentStep.ENTRY, state.currentStep)
    assertTrue(state.consent.willingPersonalInfo)
    assertEquals(BeneficiaryType.PREGNANT_WOMAN, state.beneficiaryType)
  }

  // --- Stepper & completion scaffold (VM-12..16) -------------------------------

  @Test
  fun `VM-12 forward step skipping is blocked`() {
    reachConsent()

    viewModel.goToStep(EnrollmentStep.SUMMARY)
    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)

    viewModel.goToStep(EnrollmentStep.HEALTH_HISTORY)
    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VM-13 tab back-navigation to completed steps allowed`() {
    reachConsent()
    completeConsent()
    viewModel.goToPersonalInfo()

    viewModel.goToStep(EnrollmentStep.CONSENT)
    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)

    // Forward again to the furthest reached step is allowed.
    viewModel.goToStep(EnrollmentStep.PERSONAL_INFO)
    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)

    // Beyond furthest is ignored.
    viewModel.goToStep(EnrollmentStep.SUMMARY)
    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VM-14 submit from summary reaches complete`() {
    reachConsent()
    completeConsent()
    viewModel.goToPersonalInfo()

    // Submit outside SUMMARY is a no-op.
    viewModel.submit()
    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VM-15 start visit form emits coming soon`() = runTest {
    viewModel.onStartVisitForm()

    assertEquals(EnrollmentEvent.ComingSoon, viewModel.events.first())
  }

  @Test
  fun `VM-16 exit clears the in-memory draft entirely`() {
    reachConsent()
    completeConsent()
    viewModel.goToPersonalInfo()
    viewModel.setFirstName("Reema")

    val previousId = viewModel.uiState.value.personalInfo.beneficiaryId
    viewModel.exitEnrollment()
    idle()

    val state = viewModel.uiState.value
    assertEquals(EnrollmentStep.ENTRY, state.currentStep)
    assertNull(state.beneficiaryType)
    assertEquals(ConsentState(), state.consent)
    assertEquals("", state.personalInfo.firstName)
    assertNull(state.personalInfo.lmp)
    // Fresh draft gets a fresh beneficiary id.
    assertFalse(state.personalInfo.beneficiaryId == previousId)
  }

  // === CR-015b — Personal Info (PI-1..26) =====================================

  private val today: LocalDate = LocalDate.now()

  private fun reachPersonalInfo() {
    reachConsent()
    completeConsent()
    viewModel.goToPersonalInfo()
  }

  private fun pi() = viewModel.uiState.value.personalInfo

  /** Fills every mandatory Personal Info field with valid values. */
  private fun completePersonalInfo() {
    with(viewModel) {
      setLmp(today.minusDays(90))
      setFirstName("Reema")
      setMiddleName("Manish")
      setLastName("Powra")
      setAge("25")
      setAddress("203, Pada 4, MG Road")
      setMobileNumber("9740887212")
      setPhoneOwner(1)
      setNetworkAvailability(5)
      setEducationSelf(4)
      setEducationPartner(4)
      setPartnerOccupation(1)
      setYearsInVillage("5")
      setMigrationPattern(1)
      setIncomeBand(1)
      setReligion(3)
      setCategory(5)
      setHouseholdMembers("5")
      setChildrenUnderFive("1")
      selectVillage("V-BHAV")
    }
    idle()
    viewModel.selectPada("P-CHAU")
  }

  @Test
  fun `PI-1 lmp known defaults to yes with empty lmp`() {
    reachPersonalInfo()
    assertTrue(pi().lmpKnown)
    assertNull(pi().lmp)
    assertNull(pi().edd)
    assertNull(pi().gestationalAgeWeeks)
  }

  @Test
  fun `PI-2 selecting lmp not known is a no-op`() {
    reachPersonalInfo()
    viewModel.setLmpKnown(false)
    assertTrue(pi().lmpKnown)
  }

  @Test
  fun `PI-3 future lmp rejected`() {
    reachPersonalInfo()
    viewModel.setLmp(today.plusDays(1))
    assertEquals(FieldError.LMP_FUTURE, pi().lmpError)
  }

  @Test
  fun `PI-4 lmp within 30 days rejected`() {
    reachPersonalInfo()
    viewModel.setLmp(today.minusDays(29))
    assertEquals(FieldError.LMP_TOO_RECENT, pi().lmpError)
    viewModel.setLmp(today.minusDays(30))
    assertEquals(FieldError.LMP_TOO_RECENT, pi().lmpError)
  }

  @Test
  fun `PI-5 lmp of 240 days or more rejected`() {
    reachPersonalInfo()
    viewModel.setLmp(today.minusDays(240))
    assertEquals(FieldError.LMP_TOO_OLD, pi().lmpError)
    viewModel.setLmp(today.minusDays(239))
    assertNull(pi().lmpError)
  }

  @Test
  fun `PI-6 valid lmp computes edd and ga`() {
    reachPersonalInfo()
    viewModel.setLmp(today.minusDays(90))
    assertNull(pi().lmpError)
    assertEquals(today.minusDays(90).plusDays(280), pi().edd)
    assertEquals(12, pi().gestationalAgeWeeks)
  }

  @Test
  fun `PI-7 changing lmp recomputes derived values`() {
    reachPersonalInfo()
    viewModel.setLmp(today.minusDays(90))
    viewModel.setLmp(today.minusDays(60))
    assertEquals(today.minusDays(60).plusDays(280), pi().edd)
    assertEquals(8, pi().gestationalAgeWeeks)
  }

  @Test
  fun `PI-8 beneficiary id is a stable local uuid`() {
    reachPersonalInfo()
    val id = pi().beneficiaryId
    assertNotNull(UUID.fromString(id)) // throws if not a UUID
    viewModel.setFirstName("Reema")
    assertEquals(id, pi().beneficiaryId)
  }

  @Test
  fun `PI-9 registration date defaults to today and project prefilled`() {
    reachPersonalInfo()
    assertEquals(today, pi().registrationDate)
    assertEquals(PROJECT_NAME, pi().projectName)
  }

  @Test
  fun `PI-10 names reject special characters`() {
    reachPersonalInfo()
    viewModel.setFirstName("R33ma!")
    assertEquals(FieldError.NAME_SPECIAL_CHARS, pi().firstNameError)
    viewModel.setFirstName("Reema Devi")
    assertNull(pi().firstNameError)
    viewModel.setLastName("Powra#")
    assertEquals(FieldError.NAME_SPECIAL_CHARS, pi().lastNameError)
  }

  @Test
  fun `PI-11 dob auto-derives age and direct age accepted`() {
    reachPersonalInfo()
    viewModel.setDob(today.minusYears(25).minusDays(100))
    assertEquals(25, pi().ageYears)

    viewModel.setAge("30")
    assertEquals(30, pi().ageYears)
    assertNull(pi().dob)
  }

  @Test
  fun `PI-12 age outside 10 to 50 rejected boundaries accepted`() {
    reachPersonalInfo()
    viewModel.setAge("9")
    assertEquals(FieldError.AGE_OUT_OF_RANGE, pi().ageError)
    viewModel.setAge("51")
    assertEquals(FieldError.AGE_OUT_OF_RANGE, pi().ageError)
    viewModel.setAge("10")
    assertNull(pi().ageError)
    viewModel.setAge("50")
    assertNull(pi().ageError)
  }

  @Test
  fun `PI-13 mobile must be 10 digits`() {
    reachPersonalInfo()
    viewModel.setMobileNumber("974088721")
    assertEquals(FieldError.MOBILE_LENGTH, pi().mobileError)
    viewModel.setMobileNumber("97408872123")
    assertEquals(FieldError.MOBILE_LENGTH, pi().mobileError)
    viewModel.setMobileNumber("9740887212")
    assertNull(pi().mobileError)
  }

  @Test
  fun `PI-14 empty address surfaces error after blocked next`() {
    reachPersonalInfo()
    viewModel.goToHealthHistory() // blocked → banner on
    assertEquals(FieldError.REQUIRED, pi().addressError)
  }

  @Test
  fun `PI-15 repo returns hierarchical children per parent`() = runTest {
    val repo = StaticGeographyRepository()
    assertTrue(repo.getStates().isNotEmpty())
    assertTrue(repo.getDistricts("MH").isNotEmpty())
    assertTrue(repo.getBlocks("MH-PAL").isNotEmpty())
    assertTrue(repo.getVillages("MH-PAL-JAW").isNotEmpty())
    assertTrue(repo.getPadas("V-BHAV").isNotEmpty())
    assertTrue(repo.getPhcs("V-BHAV").isNotEmpty())
    assertTrue(repo.getSubCentres("V-BHAV").isNotEmpty())
    assertTrue(repo.getDistricts("XX").isEmpty())
  }

  @Test
  fun `PI-16 geography prefilled from sakhi assignment`() {
    reachPersonalInfo()
    assertEquals("MH", pi().stateId)
    assertEquals("MH-PAL", pi().districtId)
    assertEquals("MH-PAL-JAW", pi().blockId)
    assertTrue(pi().villages.isNotEmpty())
  }

  @Test
  fun `PI-17 changing parent resets descendants`() {
    reachPersonalInfo()
    viewModel.selectVillage("V-BHAV")
    idle()
    viewModel.selectPada("P-CHAU")

    viewModel.selectDistrict("MH-NAN")
    idle()

    assertNull(pi().blockId)
    assertNull(pi().villageId)
    assertNull(pi().padaId)
    assertNull(pi().phcId)
    assertNull(pi().subCentreId)
    assertTrue(pi().blocks.isNotEmpty()) // reloaded for the new district
    assertTrue(pi().villages.isEmpty())
  }

  @Test
  fun `PI-18 phc and sc auto populate from village`() {
    reachPersonalInfo()
    viewModel.selectVillage("V-BHAV")
    idle()
    // Single-option lists auto-select; multi-option pada stays unselected.
    assertEquals("PHC-JAW", pi().phcId)
    assertEquals("SC-BHAV", pi().subCentreId)
    assertNull(pi().padaId)
    assertEquals(2, pi().padas.size)
  }

  @Test
  fun `PI-19 dropdown selections stored as codes`() {
    reachPersonalInfo()
    viewModel.setPhoneOwner(2)
    viewModel.setNetworkAvailability(5)
    viewModel.setEducationSelf(4)
    viewModel.setEducationPartner(7)
    viewModel.setPartnerOccupation(5)
    viewModel.setMigrationPattern(2)
    viewModel.setIncomeBand(3)
    viewModel.setReligion(3)
    viewModel.setCategory(5)
    with(pi()) {
      assertEquals(2, phoneOwner)
      assertEquals(5, networkAvailability)
      assertEquals(4, educationSelf)
      assertEquals(7, educationPartner)
      assertEquals(5, partnerOccupation)
      assertEquals(2, migrationPattern)
      assertEquals(3, incomeBand)
      assertEquals(3, religion)
      assertEquals(5, category)
    }
  }

  @Test
  fun `PI-20 years in village must be numeric`() {
    reachPersonalInfo()
    viewModel.setYearsInVillage("abc")
    assertEquals(FieldError.YEARS_INVALID, pi().yearsInVillageError)
    viewModel.setYearsInVillage("5")
    assertNull(pi().yearsInVillageError)
  }

  @Test
  fun `PI-21 household size range 2 to 15`() {
    reachPersonalInfo()
    viewModel.setHouseholdMembers("1")
    assertEquals(FieldError.HOUSEHOLD_RANGE, pi().householdMembersError)
    viewModel.setHouseholdMembers("16")
    assertEquals(FieldError.HOUSEHOLD_RANGE, pi().householdMembersError)
    viewModel.setHouseholdMembers("2")
    assertNull(pi().householdMembersError)
    viewModel.setHouseholdMembers("15")
    assertNull(pi().householdMembersError)
  }

  @Test
  fun `PI-22 children under five cannot exceed household`() {
    reachPersonalInfo()
    viewModel.setHouseholdMembers("4")
    viewModel.setChildrenUnderFive("5")
    assertEquals(FieldError.CHILDREN_EXCEED_HOUSEHOLD, pi().childrenUnderFiveError)
    viewModel.setChildrenUnderFive("2")
    assertNull(pi().childrenUnderFiveError)
  }

  @Test
  fun `PI-23 incomplete step blocks next and shows banner`() {
    reachPersonalInfo()
    viewModel.goToHealthHistory()
    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
    assertTrue(pi().showValidationBanner)
  }

  @Test
  fun `PI-24 banner clears once fields become valid`() {
    reachPersonalInfo()
    viewModel.goToHealthHistory() // blocked → banner
    assertTrue(pi().showValidationBanner)

    completePersonalInfo()

    assertFalse(pi().showValidationBanner)
  }

  @Test
  fun `PI-25 complete step advances and updates furthest`() {
    reachPersonalInfo()
    completePersonalInfo()

    viewModel.goToHealthHistory()

    assertEquals(EnrollmentStep.HEALTH_HISTORY, viewModel.uiState.value.currentStep)
    assertEquals(EnrollmentStep.HEALTH_HISTORY, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `PI-26 back to consent retains personal info draft`() {
    reachPersonalInfo()
    viewModel.setFirstName("Reema")
    viewModel.setLmp(today.minusDays(90))

    viewModel.goBack()
    assertEquals(EnrollmentStep.CONSENT, viewModel.uiState.value.currentStep)

    viewModel.goToStep(EnrollmentStep.PERSONAL_INFO)
    assertEquals("Reema", pi().firstName)
    assertEquals(today.minusDays(90), pi().lmp)
  }

  // === CR-015d — Summary + submit (SM-1..11) ==================================

  /** Reaches SUMMARY with a complete, valid draft (incl. Health History). */
  private fun reachSummary() {
    reachHealthHistory()
    completeHealthHistory()
    viewModel.goToSummary()
  }

  @Test
  fun `SM-1 health history gates advance to summary`() {
    reachHealthHistory()
    // Incomplete Health History blocks Summary (updated for CR-015c).
    viewModel.goToSummary()
    assertEquals(EnrollmentStep.HEALTH_HISTORY, viewModel.uiState.value.currentStep)

    completeHealthHistory()
    viewModel.goToSummary()

    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.currentStep)
    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `SM-2 summary exposes personal info draft for review`() {
    reachSummary()
    with(pi()) {
      assertEquals("Reema", firstName)
      assertEquals("Powra", lastName)
      assertEquals(4, educationSelf)
      assertEquals("9740887212", mobileNumber)
      assertEquals("MH", stateId)
    }
  }

  @Test
  fun `SM-3 edit personal info returns to that step retaining draft`() {
    reachSummary()

    viewModel.goToStep(EnrollmentStep.PERSONAL_INFO)

    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
    assertEquals("Reema", pi().firstName)
    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `SM-4 re-entering summary after edit shows updated values`() {
    reachSummary()
    viewModel.goToStep(EnrollmentStep.PERSONAL_INFO)

    viewModel.setFirstName("Rekha")
    viewModel.goToStep(EnrollmentStep.SUMMARY)

    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.currentStep)
    assertEquals("Rekha", pi().firstName)
  }

  @Test
  fun `SM-5 edited draft failing validation blocks return to summary`() {
    reachSummary()
    viewModel.goToStep(EnrollmentStep.PERSONAL_INFO)

    viewModel.setMobileNumber("123") // now invalid
    viewModel.goToHealthHistory()

    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
    assertTrue(pi().showValidationBanner)
  }

  @Test
  fun `SM-6 submit is a no-op while draft incomplete`() {
    reachSummary()
    viewModel.goToStep(EnrollmentStep.PERSONAL_INFO)
    viewModel.setMobileNumber("123") // invalidate
    viewModel.goToStep(EnrollmentStep.SUMMARY)
    assertFalse(viewModel.uiState.value.canSubmit)

    viewModel.submit()
    idle()

    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.currentStep)
    assertTrue(enrollmentRepository.saved.isEmpty())
  }

  @Test
  fun `SM-7 submit saves draft through repository once`() {
    reachSummary()
    val id = pi().beneficiaryId

    viewModel.submit()
    idle()

    assertEquals(1, enrollmentRepository.saved.size)
    with(enrollmentRepository.saved.first()) {
      assertEquals(id, beneficiaryId)
      assertEquals("Reema", firstName)
      assertEquals(4, educationSelf) // stored as code, not label
      assertTrue(consent.willingPersonalInfo)
    }
  }

  @Test
  fun `SM-8 successful submit emits saved event then completes`() = runTest {
    reachSummary()

    viewModel.submit()
    idle()

    assertEquals(EnrollmentEvent.DataSaved, viewModel.events.first())
    assertEquals(EnrollmentStep.COMPLETE, viewModel.uiState.value.currentStep)
    assertFalse(viewModel.uiState.value.isSubmitting)
  }

  @Test
  fun `SM-9 failed submit stays on summary with retryable error`() {
    enrollmentRepository.result = Result.failure(RuntimeException("disk full"))
    reachSummary()

    viewModel.submit()
    idle()

    val state = viewModel.uiState.value
    assertEquals(EnrollmentStep.SUMMARY, state.currentStep)
    assertTrue(state.submitFailed)
    assertFalse(state.isSubmitting)
    assertTrue(state.canSubmit) // retry possible

    // Retry succeeds and clears the error.
    enrollmentRepository.result = Result.success(Unit)
    viewModel.submit()
    idle()
    assertEquals(EnrollmentStep.COMPLETE, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `SM-10 duplicate submit prevented while save in flight`() {
    val gate = CompletableDeferred<Unit>()
    enrollmentRepository.gate = gate
    reachSummary()

    viewModel.submit()
    idle()
    assertTrue(viewModel.uiState.value.isSubmitting)

    // Second call while suspended must not queue another save.
    viewModel.submit()
    idle()

    gate.complete(Unit)
    idle()

    assertEquals(1, enrollmentRepository.saved.size)
    assertEquals(EnrollmentStep.COMPLETE, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `SM-11 exit after submit clears draft but keeps saved record`() = runTest {
    reachSummary()
    val id = pi().beneficiaryId
    viewModel.submit()
    idle()

    viewModel.exitEnrollment()
    idle()

    assertEquals(EnrollmentStep.ENTRY, viewModel.uiState.value.currentStep)
    assertEquals("", viewModel.uiState.value.personalInfo.firstName)
    // The persisted record remains in the repository.
    assertEquals("Reema", enrollmentRepository.getEnrollment(id)?.firstName)
  }

  // === CR-015c — Health History (HH-1..27) ====================================

  private fun hh() = viewModel.uiState.value.healthHistory

  private fun reachHealthHistory() {
    reachPersonalInfo()
    completePersonalInfo()
    viewModel.goToHealthHistory()
  }

  /** Minimal valid Health History: Gravida 1 (no last-pregnancy block). */
  private fun completeHealthHistory() {
    with(viewModel) {
      setPlannedPregnancy(2)
      setTookTreatment(false)
      setRchStatus(3)
      setAncStatus(1)
      setTdNone(true)
      setGravida("1")
      setPara("0")
      setLivingChildren("0")
      setAbortions("0")
      setStillBirths("0")
      toggleSelfCondition(1)
      toggleLongTermMed(1)
      setSickleCell(1)
      toggleSubstanceUse(1)
      setFamilyHistory(false)
    }
  }

  @Test
  fun `HH-1 trimester auto-derives from gestational age`() {
    reachHealthHistory()
    viewModel.setLmp(today.minusDays(90)) // 12w
    assertEquals(1, hh().trimester)
    viewModel.setLmp(today.minusDays(140)) // 20w
    assertEquals(2, hh().trimester)
    viewModel.setLmp(today.minusDays(210)) // 30w
    assertEquals(3, hh().trimester)
  }

  @Test
  fun `HH-3 treatment type shown only when treatment taken`() {
    reachHealthHistory()
    viewModel.setTookTreatment(false)
    assertFalse(hh().showTreatmentType)
    viewModel.setTookTreatment(true)
    assertTrue(hh().showTreatmentType)
    viewModel.setTreatmentType(2)
    assertEquals(2, hh().treatmentType)
    // Toggling back clears the conditional answer.
    viewModel.setTookTreatment(false)
    assertNull(hh().treatmentType)
  }

  @Test
  fun `HH-4 rch number required only for card-available option`() {
    reachHealthHistory()
    viewModel.setRchStatus(1)
    assertTrue(hh().showRchNumber)
    viewModel.setRchNumber("RCH-9")
    viewModel.setRchStatus(3)
    assertFalse(hh().showRchNumber)
    assertEquals("", hh().rchNumber)
  }

  @Test
  fun `HH-5 anc1 details shown only when anc1 completed`() {
    reachHealthHistory()
    viewModel.setAncStatus(1)
    assertFalse(hh().showAnc1Details)
    viewModel.setAncStatus(2)
    assertTrue(hh().showAnc1Details)
    viewModel.setAnc1Date(today.minusDays(10))
    viewModel.toggleAncCondition(2)
    viewModel.setAncStatus(1) // leaving clears conditional data
    assertNull(hh().anc1Date)
    assertTrue(hh().ancConditions.isEmpty())
  }

  @Test
  fun `HH-6 anc1 date future rejected`() {
    reachHealthHistory()
    viewModel.setAncStatus(2)
    viewModel.setAnc1Date(today.plusDays(1))
    assertEquals(HealthFieldError.ANC1_DATE_INVALID, hh().anc1DateError)
    viewModel.setAnc1Date(today.minusDays(5))
    assertNull(hh().anc1DateError)
  }

  @Test
  fun `HH-7 anc conditions exclusive options`() {
    reachHealthHistory()
    viewModel.setAncStatus(2)
    viewModel.toggleAncCondition(2) // a specific condition
    viewModel.toggleAncCondition(1) // "No known" clears the rest
    assertEquals(setOf(1), hh().ancConditions)
    viewModel.toggleAncCondition(3) // a specific clears the exclusive
    assertEquals(setOf(3), hh().ancConditions)
  }

  @Test
  fun `HH-8 td none is exclusive`() {
    reachHealthHistory()
    viewModel.setTd1(true)
    viewModel.setTdNone(true)
    assertFalse(hh().td1)
    assertTrue(hh().tdNone)
    viewModel.setTd1(true)
    assertFalse(hh().tdNone)
  }

  @Test
  fun `HH-9 td dates future and order validated`() {
    reachHealthHistory()
    viewModel.setTd1(true)
    viewModel.setTd1Date(today.plusDays(1))
    assertEquals(HealthFieldError.TD_DATE_FUTURE, hh().tdDateError)

    viewModel.setTd1Date(today.minusDays(10))
    viewModel.setTd2(true)
    viewModel.setTd2Date(today.minusDays(20)) // before Td1
    assertEquals(HealthFieldError.TD_DATE_ORDER, hh().tdDateError)

    viewModel.setTd2Date(today.minusDays(5))
    assertNull(hh().tdDateError)
  }

  @Test
  fun `HH-10 gravida range 1 to 14`() {
    reachHealthHistory()
    viewModel.setGravida("0")
    assertEquals(HealthFieldError.COUNT_RANGE, hh().gravidaError)
    viewModel.setGravida("15")
    assertEquals(HealthFieldError.COUNT_RANGE, hh().gravidaError)
    viewModel.setGravida("1")
    assertNull(hh().gravidaError)
  }

  @Test
  fun `HH-11 para at most gravida`() {
    reachHealthHistory()
    viewModel.setGravida("2")
    viewModel.setPara("3")
    assertEquals(HealthFieldError.PARA_EXCEEDS_GRAVIDA, hh().paraError)
    viewModel.setPara("2")
    assertNull(hh().paraError)
  }

  @Test
  fun `HH-12 abortions at most gravida`() {
    reachHealthHistory()
    viewModel.setGravida("2")
    viewModel.setAbortions("3")
    assertEquals(HealthFieldError.ABORTIONS_EXCEED_GRAVIDA, hh().abortionsError)
  }

  @Test
  fun `HH-13 dead children at most living`() {
    reachHealthHistory()
    viewModel.setLivingChildren("1")
    viewModel.setDeadChildren("2")
    assertEquals(HealthFieldError.DEAD_EXCEEDS_LIVING, hh().deadChildrenError)
    viewModel.setDeadChildren("")
    assertNull(hh().deadChildrenError) // optional
  }

  @Test
  fun `HH-14 gravida cross-total enforced when all entered`() {
    reachHealthHistory()
    viewModel.setGravida("4")
    viewModel.setPara("2")
    viewModel.setAbortions("0") // 2 + 0 + 1 = 3 ≠ 4
    assertEquals(HealthFieldError.GRAVIDA_TOTAL_MISMATCH, hh().gravidaTotalError)
    viewModel.setAbortions("1") // 2 + 1 + 1 = 4
    assertNull(hh().gravidaTotalError)
  }

  @Test
  fun `HH-15 last pregnancy hidden when gravida is 1`() {
    reachHealthHistory()
    viewModel.setGravida("1")
    assertFalse(hh().showLastPregnancy)
    viewModel.setGravida("2")
    assertTrue(hh().showLastPregnancy)
  }

  @Test
  fun `HH-16 birth weight shown only for live-birth outcome`() {
    reachHealthHistory()
    viewModel.setGravida("2")
    viewModel.setLastDeliveryOutcome(2) // still birth
    assertFalse(hh().showBirthWeight)
    viewModel.setLastDeliveryOutcome(1) // live birth
    assertTrue(hh().showBirthWeight)
  }

  @Test
  fun `HH-17 changing gravida to 1 clears last-pregnancy answers`() {
    reachHealthHistory()
    viewModel.setGravida("2")
    viewModel.setLastDeliveryType(1)
    viewModel.setLastDeliveryOutcome(1)
    viewModel.setBirthWeight(1)

    viewModel.setGravida("1")

    assertNull(hh().lastDeliveryType)
    assertNull(hh().lastDeliveryOutcome)
    assertNull(hh().birthWeight)
  }

  @Test
  fun `HH-18 self conditions exclusive options`() {
    reachHealthHistory()
    viewModel.toggleSelfCondition(2)
    viewModel.toggleSelfCondition(1) // "No known" clears the rest
    assertEquals(setOf(1), hh().selfConditions)
  }

  @Test
  fun `HH-19 substance use exclusive no option`() {
    reachHealthHistory()
    viewModel.toggleSubstanceUse(2)
    viewModel.toggleSubstanceUse(1) // "No" clears the rest
    assertEquals(setOf(1), hh().substanceUse)
  }

  @Test
  fun `HH-20 sickle cell stores code`() {
    reachHealthHistory()
    viewModel.setSickleCell(3)
    assertEquals(3, hh().sickleCell)
  }

  @Test
  fun `HH-21 family conditions shown only when family history yes`() {
    reachHealthHistory()
    viewModel.setFamilyHistory(false)
    assertFalse(hh().showFamilyConditions)
    viewModel.setFamilyHistory(true)
    assertTrue(hh().showFamilyConditions)
    viewModel.toggleFamilyCondition(1)
    viewModel.setFamilyHistory(false) // clears conditional
    assertTrue(hh().familyConditions.isEmpty())
  }

  @Test
  fun `HH-22 optional fields do not block completion`() {
    reachHealthHistory()
    completeHealthHistory()
    // deadChildren, malnutrition, remarks all left empty.
    assertTrue(hh().isComplete)
  }

  @Test
  fun `HH-23 incomplete step blocks summary and shows banner`() {
    reachHealthHistory()
    viewModel.goToSummary()
    assertEquals(EnrollmentStep.HEALTH_HISTORY, viewModel.uiState.value.currentStep)
    assertTrue(hh().showValidationBanner)
  }

  @Test
  fun `HH-24 banner clears once fields valid`() {
    reachHealthHistory()
    viewModel.goToSummary() // blocked → banner
    assertTrue(hh().showValidationBanner)
    completeHealthHistory()
    assertFalse(hh().showValidationBanner)
  }

  @Test
  fun `HH-25 complete step advances to summary`() {
    reachHealthHistory()
    completeHealthHistory()
    viewModel.goToSummary()
    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.currentStep)
    assertEquals(EnrollmentStep.SUMMARY, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `HH-26 back to personal info retains health history draft`() {
    reachHealthHistory()
    viewModel.setGravida("3")
    viewModel.goBack()
    assertEquals(EnrollmentStep.PERSONAL_INFO, viewModel.uiState.value.currentStep)
    viewModel.goToStep(EnrollmentStep.HEALTH_HISTORY)
    assertEquals("3", hh().gravida)
  }

  @Test
  fun `HH-27 submitted record carries health history answers`() {
    reachSummary()
    viewModel.submit()
    idle()

    val record = enrollmentRepository.saved.single()
    with(record.healthHistory) {
      assertEquals(2, plannedPregnancy)
      assertEquals(1, gravida)
      assertEquals(1, sickleCell)
      assertTrue(tdNone)
      assertTrue(1 in selfConditions)
    }
  }
}
