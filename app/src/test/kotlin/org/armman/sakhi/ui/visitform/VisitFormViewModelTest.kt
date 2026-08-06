package org.armman.sakhi.ui.visitform

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.R
import org.armman.sakhi.data.visitform.CriticalCondition
import org.armman.sakhi.data.visitform.VisitContext
import org.armman.sakhi.data.visitform.VisitDataSubTab
import org.armman.sakhi.data.visitform.VisitFormRepository
import org.armman.sakhi.data.visitform.VisitFormStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * CR-016a/b unit tests — spec: docs/test-cases/visit-form.md (VF-1..12, VD-1..31).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisitFormViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  /** GA ≈ 28 weeks from "today" — deterministic, keeps fetal fields (≥20wk) in scope. */
  private val testContext = VisitContext(
    visitTypeLabel = "ANC2",
    rchNumber = "RCH-TEST-001",
    lmp = LocalDate.now().minusWeeks(28),
    heightCm = 150,
    previousHb = 9.0,
    advisedDeliveryPlace = 5,
    sickleCell = 1,
  )

  private class FakeVisitFormRepository(
    private val context: VisitContext,
    private val shouldFail: Boolean = false,
  ) : VisitFormRepository {
    override suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext {
      if (shouldFail) throw NoSuchElementException("No visit context for beneficiary: $beneficiaryId")
      return context
    }

    override suspend fun canStartVisit(beneficiaryId: String): Boolean = !shouldFail
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(
    beneficiaryId: String = "b01",
    visitId: String = "v2",
    label: String = "Visit 2",
    repository: VisitFormRepository = FakeVisitFormRepository(testContext),
  ): VisitFormViewModel {
    val args = mapOf(
      VisitFormViewModel.NAV_ARG_BENEFICIARY_ID to beneficiaryId,
      VisitFormViewModel.NAV_ARG_VISIT_ID to visitId,
      VisitFormViewModel.NAV_ARG_LABEL to label,
    )
    val viewModel = VisitFormViewModel(repository, SavedStateHandle(args))
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  /** Tests sub-tab fields only (Q12–16, Q23–32) — mirrors [VisitDataState.isTestsComplete]. */
  private fun fillTestsFields(viewModel: VisitFormViewModel) {
    viewModel.setWeightKg("55")
    viewModel.setMuacCm("22")
    viewModel.setBpSystolic("120")
    viewModel.setBpDiastolic("80")
    viewModel.setTemperatureF("98.6")
    viewModel.setHemoglobin("9.0")
    viewModel.setBloodGlucose("90")
    viewModel.toggleUrineTest(VisitDataState.URINE_NORMAL)
    viewModel.setFetalMovements(1)
    viewModel.setFetalHeartRate("140")
    viewModel.setFundalHeightCm("28")
  }

  /** Symptoms sub-tab fields only (Q17–22) — mirrors [VisitDataState.isSymptomsComplete]. */
  private fun fillSymptomsFields(viewModel: VisitFormViewModel) {
    viewModel.toggleDangerSign(VisitDataState.DANGER_SIGN_NONE)
    viewModel.setPalmNails(1)
    viewModel.setSclera(1)
    viewModel.setSkin(1)
    viewModel.toggleSwelling(VisitDataState.SWELLING_NONE)
    viewModel.toggleDehydration(1)
  }

  /** History sub-tab fields only (Q1–4, Q5–11, Q33–56) — mirrors [VisitDataState.isHistoryComplete]. */
  private fun fillHistoryFields(viewModel: VisitFormViewModel) {
    viewModel.setRchNumber("RCH-TEST-001")
    viewModel.setHasSonographyReport(false)
    viewModel.setMetBeneficiary(true)
    viewModel.setTdNone(true)
    viewModel.toggleFoodConsumed(1)
    viewModel.toggleFoodAvoided(1)
    viewModel.setTakingIfa(false)
    viewModel.toggleIfaNonConsumptionReason(1)
    viewModel.setCalciumTaken(true)
    viewModel.setVisitedFacilitySinceLastVisit(false)
    viewModel.toggleFamilyPlanningMethod(1)
    viewModel.setFeelingStressed(false)
    viewModel.setAdequateFamilySupport(true)
    viewModel.setPlanningMigration(false)
    viewModel.setUsgDone(false)
    viewModel.setTransportContactShared(true)
    viewModel.setFundsArranged(true)
    viewModel.setBirthCompanionIdentified(true)
  }

  /** Fills every mandatory (and conditionally-visible) field with a valid value. */
  private fun fillMinimalValidVisitData(viewModel: VisitFormViewModel) {
    fillHistoryFields(viewModel)
    fillTestsFields(viewModel)
    fillSymptomsFields(viewModel)
  }

  // --- Shell / stepper -------------------------------------------------------

  @Test
  fun `initial state opens on visit data tab`() {
    val state = createViewModel().uiState.value

    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals(VisitFormStep.VISIT_DATA, state.currentStep)
    assertEquals(VisitFormStep.VISIT_DATA, state.furthestStep)
  }

  @Test
  fun `nav args are exposed for the header title`() {
    val viewModel = createViewModel(beneficiaryId = "b01", visitId = "v2", label = "Visit 2")

    assertEquals("b01", viewModel.beneficiaryId)
    assertEquals("v2", viewModel.visitId)
    assertEquals("Visit 2", viewModel.visitLabel)
  }

  @Test
  fun `load failure sets the error state, retry re-attempts the load`() {
    val viewModel = createViewModel(repository = FakeVisitFormRepository(testContext, shouldFail = true))
    assertTrue(viewModel.uiState.value.hasError)
    assertFalse(viewModel.uiState.value.isLoading)

    // Retrying against the same failing repository still errors (no crash/hang).
    viewModel.loadContext()
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.uiState.value.hasError)
  }

  @Test
  fun `step order is fixed and gated by furthest step`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)

    // Cannot jump ahead of the furthest reached step.
    viewModel.goToStep(VisitFormStep.REFERRAL)
    assertEquals(VisitFormStep.VISIT_DATA, viewModel.uiState.value.currentStep)

    viewModel.goToSummary()
    assertEquals(VisitFormStep.SUMMARY, viewModel.uiState.value.currentStep)
    assertEquals(VisitFormStep.SUMMARY, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `tab back-navigation to completed steps allowed`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)
    viewModel.goToSummary()

    viewModel.goToStep(VisitFormStep.VISIT_DATA)
    assertEquals(VisitFormStep.VISIT_DATA, viewModel.uiState.value.currentStep)

    // Ahead of furthest (SUMMARY) is still ignored.
    viewModel.goToStep(VisitFormStep.REFERRAL)
    assertEquals(VisitFormStep.VISIT_DATA, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `visit data sub-tabs default to tests and switch freely`() {
    val viewModel = createViewModel()
    assertEquals(VisitDataSubTab.TESTS, viewModel.uiState.value.visitDataSubTab)

    viewModel.goToSubTab(VisitDataSubTab.HISTORY)
    assertEquals(VisitDataSubTab.HISTORY, viewModel.uiState.value.visitDataSubTab)

    // Sub-tabs are not gated by furthestStep — only the top-level stepper is.
    viewModel.goToSubTab(VisitDataSubTab.SYMPTOMS)
    assertEquals(VisitDataSubTab.SYMPTOMS, viewModel.uiState.value.visitDataSubTab)
  }

  @Test
  fun `back from visit data exits the form`() = runTest {
    val viewModel = createViewModel()
    viewModel.goBack()
    assertEquals(VisitFormEvent.ExitForm, viewModel.events.first())
  }

  @Test
  fun `in-flow back moves one step and retains the draft`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)
    viewModel.goToSummary()
    viewModel.goToHealthInfo()

    viewModel.goBack()
    assertEquals(VisitFormStep.SUMMARY, viewModel.uiState.value.currentStep)
    // furthestStep is unaffected by moving backwards.
    assertEquals(VisitFormStep.HEALTH_INFO, viewModel.uiState.value.furthestStep)
  }

  @Test
  fun `full forward path reaches referral`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)
    viewModel.goToSummary()
    viewModel.goToHealthInfo()
    viewModel.goToReferral()

    assertEquals(VisitFormStep.REFERRAL, viewModel.uiState.value.currentStep)
    assertEquals(VisitFormStep.REFERRAL, viewModel.uiState.value.furthestStep)
  }

  // --- Discard-on-exit (FR-S-4.2) ---------------------------------------------

  @Test
  fun `confirmed exit discards the entire draft`() = runTest {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)
    viewModel.goToSummary()
    viewModel.goToSubTab(VisitDataSubTab.HISTORY)

    viewModel.exitForm()

    val state = viewModel.uiState.value
    assertEquals(VisitFormStep.VISIT_DATA, state.currentStep)
    assertEquals(VisitFormStep.VISIT_DATA, state.furthestStep)
    assertEquals(VisitDataSubTab.TESTS, state.visitDataSubTab)
    assertEquals(VisitFormEvent.ExitForm, viewModel.events.first())
  }

  @Test
  fun `submit is stubbed as coming soon`() = runTest {
    val viewModel = createViewModel()
    viewModel.onSubmit()
    assertEquals(VisitFormEvent.ComingSoon, viewModel.events.first())
  }

  // --- Critical-condition mid-form banner (FR-S-4.4) --------------------------

  @Test
  fun `critical condition state slot starts null`() {
    assertNull(createViewModel().uiState.value.criticalCondition)
  }

  @Test
  fun `reporting a critical condition populates the state slot`() {
    val viewModel = createViewModel()
    val condition = CriticalCondition(messageRes = R.string.visit_form_exit_title)

    viewModel.reportCriticalCondition(condition)

    assertEquals(condition, viewModel.uiState.value.criticalCondition)
  }

  @Test
  fun `dismissing a critical banner discards and exits the form`() = runTest {
    val viewModel = createViewModel()
    viewModel.reportCriticalCondition(CriticalCondition(messageRes = R.string.visit_form_exit_title))

    viewModel.dismissCritical()

    val state = viewModel.uiState.value
    assertNull(state.criticalCondition)
    assertEquals(VisitFormStep.VISIT_DATA, state.currentStep) // draft reset
    assertEquals(VisitFormEvent.ExitForm, viewModel.events.first())
  }

  // --- VD-1..5: Visit tracking & pregnancy dating -----------------------------

  @Test
  fun `VD-1 visit date defaults to today and rejects future dates`() {
    val viewModel = createViewModel()
    assertEquals(LocalDate.now(), viewModel.uiState.value.visitData.visitDate)

    viewModel.setVisitDate(LocalDate.now().plusDays(1))
    assertEquals(VisitDataFieldError.DATE_FUTURE, viewModel.uiState.value.visitData.visitDateError)
  }

  @Test
  fun `VD-1b a valid past visit date is not flagged required once the banner is showing`() {
    // Regression: visitDateError used to fall through to "required" for any
    // filled-in, non-future date once showValidationBanner was true, because
    // `visitDate?.let { ... }` returns null for the valid case and `?:` can't
    // tell that apart from "visitDate is null".
    val viewModel = createViewModel()
    viewModel.setVisitDate(LocalDate.now().minusMonths(1))
    viewModel.goToSummary() // Raises the banner (other fields still missing).

    assertTrue(viewModel.uiState.value.visitData.showValidationBanner)
    assertNull(viewModel.uiState.value.visitData.visitDateError)
  }

  @Test
  fun `VD-2 met beneficiary no requires a reason and blocks completion`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)
    viewModel.setMetBeneficiary(false)

    assertTrue(viewModel.uiState.value.visitData.showNotMetReason)
    assertFalse(viewModel.uiState.value.visitData.isComplete)

    viewModel.goToSummary()
    assertEquals(VisitFormStep.VISIT_DATA, viewModel.uiState.value.currentStep)
  }

  @Test
  fun `VD-3 rch number and lmp auto-populate from registration`() {
    val visitData = createViewModel().uiState.value.visitData
    assertEquals(testContext.rchNumber, visitData.rchNumber)
    assertEquals(testContext.lmp, visitData.lmp)
  }

  @Test
  fun `VD-4 editing lmp recalculates ga and edd`() {
    val viewModel = createViewModel()
    val newLmp = LocalDate.now().minusWeeks(10)

    viewModel.setLmp(newLmp)

    val visitData = viewModel.uiState.value.visitData
    assertEquals(newLmp.plusDays(280), visitData.edd)
    assertEquals(10, visitData.gestationalAgeWeeks)
    assertTrue(visitData.isLmpEdited)
  }

  @Test
  fun `VD-5 sonography photo required when lmp is edited and sonography report exists`() {
    val viewModel = createViewModel()
    viewModel.setLmp(LocalDate.now().minusWeeks(10))
    viewModel.setHasSonographyReport(true)

    assertTrue(viewModel.uiState.value.visitData.showSonographyUpload)

    viewModel.onSonographyPhotoCaptured("content://visitform/sonography_report.jpg")
    assertEquals(
      "content://visitform/sonography_report.jpg",
      viewModel.uiState.value.visitData.sonographyPhotoUri,
    )
  }

  // --- VD-6..12: Tests sub-tab -------------------------------------------------

  @Test
  fun `VD-6 height captured once, read-only thereafter`() {
    // testContext.heightCm is non-null (a prior visit exists) — read-only this time.
    val visitData = createViewModel().uiState.value.visitData
    assertFalse(visitData.isHeightEditable)
    assertEquals(testContext.heightCm, visitData.heightLockedCm)
  }

  @Test
  fun `VD-6 height is editable on the beneficiary's first visit`() {
    val firstVisitContext = testContext.copy(heightCm = null)
    val viewModel = createViewModel(repository = FakeVisitFormRepository(firstVisitContext))

    assertTrue(viewModel.uiState.value.visitData.isHeightEditable)

    viewModel.setHeightCm("119")
    assertEquals(VisitDataFieldError.RANGE, viewModel.uiState.value.visitData.heightError)
  }

  @Test
  fun `VD-7 bmi auto-calculated from height and weight`() {
    val viewModel = createViewModel()
    viewModel.setWeightKg("60")

    // heightLockedCm = 150cm = 1.5m; BMI = 60 / (1.5*1.5) = 26.666...
    val bmi = viewModel.uiState.value.visitData.bmi
    assertEquals(26.67, bmi!!, 0.01)
  }

  @Test
  fun `VD-8 bp systolic diastolic range validation`() {
    val viewModel = createViewModel()

    viewModel.setBpSystolic("310")
    assertEquals(VisitDataFieldError.RANGE, viewModel.uiState.value.visitData.bpSystolicError)

    viewModel.setBpDiastolic("30")
    assertEquals(VisitDataFieldError.RANGE, viewModel.uiState.value.visitData.bpDiastolicError)
  }

  @Test
  fun `VD-9 hb range and stale-value confirmation prompt`() {
    val viewModel = createViewModel()

    viewModel.setHemoglobin("19")
    assertEquals(VisitDataFieldError.RANGE, viewModel.uiState.value.visitData.hemoglobinError)

    // previousHb = 9.0; a jump to 12.5 differs by > 2 g/dl — needs confirmation, not blocked.
    viewModel.setHemoglobin("12.5")
    assertEquals(VisitDataFieldError.HB_CONFIRM_NEEDED, viewModel.uiState.value.visitData.hemoglobinError)

    viewModel.confirmHemoglobin()
    assertNull(viewModel.uiState.value.visitData.hemoglobinError)
  }

  @Test
  fun `VD-10 blood glucose and urine test range and options`() {
    val viewModel = createViewModel()

    viewModel.setBloodGlucose("30")
    assertEquals(VisitDataFieldError.RANGE, viewModel.uiState.value.visitData.bloodGlucoseError)

    viewModel.toggleUrineTest(VisitDataState.URINE_NORMAL)
    viewModel.toggleUrineTest(2)
    // Selecting a specific finding clears "Normal" (mutual exclusion).
    assertEquals(setOf(2), viewModel.uiState.value.visitData.urineTest)
  }

  @Test
  fun `VD-11 fetal movements heart rate fundal height hidden before 20 weeks`() {
    val viewModel = createViewModel()
    viewModel.setLmp(LocalDate.now().minusWeeks(10))

    assertFalse(viewModel.uiState.value.visitData.showFetalAndFundalFields)

    viewModel.setLmp(LocalDate.now().minusWeeks(22))
    assertTrue(viewModel.uiState.value.visitData.showFetalAndFundalFields)
  }

  @Test
  fun `VD-12 temperature range validation`() {
    val viewModel = createViewModel()
    viewModel.setTemperatureF("106")
    assertEquals(VisitDataFieldError.RANGE, viewModel.uiState.value.visitData.temperatureError)
  }

  // --- VD-13..15: Symptoms sub-tab ----------------------------------------------

  @Test
  fun `VD-13 danger sign checklist none-of-the-above is exclusive`() {
    val viewModel = createViewModel()

    viewModel.toggleDangerSign(3)
    viewModel.toggleDangerSign(VisitDataState.DANGER_SIGN_NONE)
    assertEquals(setOf(VisitDataState.DANGER_SIGN_NONE), viewModel.uiState.value.visitData.dangerSigns)

    viewModel.toggleDangerSign(3)
    assertEquals(setOf(3), viewModel.uiState.value.visitData.dangerSigns)
  }

  @Test
  fun `VD-14 palm nails sclera skin are independent single-selects`() {
    val viewModel = createViewModel()
    viewModel.setPalmNails(1)
    viewModel.setSclera(2)
    viewModel.setSkin(3)

    val visitData = viewModel.uiState.value.visitData
    assertEquals(1, visitData.palmNails)
    assertEquals(2, visitData.sclera)
    assertEquals(3, visitData.skin)
  }

  @Test
  fun `VD-15 swelling and dehydration are independent multi-selects`() {
    val viewModel = createViewModel()
    viewModel.toggleSwelling(2)
    viewModel.toggleDehydration(1)

    val visitData = viewModel.uiState.value.visitData
    assertEquals(setOf(2), visitData.swelling)
    assertEquals(setOf(1), visitData.dehydration)
  }

  // --- VD-16..21: History sub-tab -----------------------------------------------

  @Test
  fun `VD-16 td vaccination status mirrors enrollment td pattern`() {
    val viewModel = createViewModel()

    viewModel.setTd1(true)
    viewModel.setTd1Date(LocalDate.now().minusMonths(2))
    viewModel.setTd2(true)
    viewModel.setTd2Date(LocalDate.now().minusMonths(1))
    assertNull(viewModel.uiState.value.visitData.tdDateError)

    // Td2 before Td1 is an ordering violation.
    viewModel.setTd2Date(LocalDate.now().minusMonths(3))
    assertEquals(VisitDataFieldError.DATE_TOO_EARLY, viewModel.uiState.value.visitData.tdDateError)

    // None is exclusive against any ticked dose.
    viewModel.setTdNone(true)
    val visitData = viewModel.uiState.value.visitData
    assertFalse(visitData.td1)
    assertFalse(visitData.td2)
  }

  @Test
  fun `VD-17 ifa non-consumption reason required only when ifa is no`() {
    val viewModel = createViewModel()

    viewModel.setTakingIfa(true)
    assertTrue(viewModel.uiState.value.visitData.showTreatmentIfaCount)
    assertFalse(viewModel.uiState.value.visitData.showIfaNonConsumptionReasons)

    viewModel.setTakingIfa(false)
    assertFalse(viewModel.uiState.value.visitData.showTreatmentIfaCount)
    assertTrue(viewModel.uiState.value.visitData.showIfaNonConsumptionReasons)
  }

  @Test
  fun `VD-18 sickle cell and advised delivery place auto-populate and are editable`() {
    val viewModel = createViewModel()
    assertEquals(testContext.advisedDeliveryPlace, viewModel.uiState.value.visitData.advisedDeliveryPlace)
    assertEquals(testContext.sickleCell, viewModel.uiState.value.visitData.sickleCell)

    viewModel.setAdvisedDeliveryPlace(2)
    viewModel.setSickleCell(3)
    assertEquals(2, viewModel.uiState.value.visitData.advisedDeliveryPlace)
    assertEquals(3, viewModel.uiState.value.visitData.sickleCell)
  }

  @Test
  fun `VD-19 usg detail fields conditional on usg done`() {
    val viewModel = createViewModel()
    viewModel.setUsgDone(false)
    assertFalse(viewModel.uiState.value.visitData.showUsgDetails)

    viewModel.setUsgDone(true)
    assertTrue(viewModel.uiState.value.visitData.showUsgDetails)

    // USG date must be after LMP and not in the future.
    viewModel.setUsgDate(testContext.lmp.minusDays(1))
    assertEquals(VisitDataFieldError.DATE_TOO_EARLY, viewModel.uiState.value.visitData.usgDateError)

    viewModel.setUsgDate(LocalDate.now().plusDays(1))
    assertEquals(VisitDataFieldError.DATE_FUTURE, viewModel.uiState.value.visitData.usgDateError)
  }

  @Test
  fun `VD-20 mental health and birth-preparedness fields are independent`() {
    val viewModel = createViewModel()
    viewModel.setFeelingStressed(true)
    viewModel.setAdequateFamilySupport(false)
    viewModel.setPlanningMigration(true)
    viewModel.setTransportContactShared(false)
    viewModel.setFundsArranged(true)

    val visitData = viewModel.uiState.value.visitData
    assertEquals(true, visitData.feelingStressed)
    assertEquals(false, visitData.adequateFamilySupport)
    assertEquals(true, visitData.planningMigration)
    assertEquals(false, visitData.transportContactShared)
    assertEquals(true, visitData.fundsArranged)
  }

  @Test
  fun `VD-21 counselling checklist never blocks completion`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)
    // Deliberately left unchecked.
    assertTrue(viewModel.uiState.value.visitData.counsellingTopics.isEmpty())
    assertTrue(viewModel.uiState.value.visitData.isComplete)
  }

  // --- VD-22..26: Critical-pathway detection (FR-S-4.4) -------------------------

  @Test
  fun `VD-22 severe hypertension with symptoms reports a critical condition`() {
    // Accompanying symptom via swelling (not a Q17 danger sign) so the
    // hypertension-specific message surfaces instead of the higher-priority
    // "any danger sign" branch in checkCriticalConditions().
    val viewModel = createViewModel()
    viewModel.toggleSwelling(2)
    viewModel.setBpSystolic("170")
    viewModel.setBpDiastolic("90")

    assertEquals(
      R.string.visit_form_critical_hypertension,
      viewModel.uiState.value.criticalCondition?.messageRes,
    )
  }

  @Test
  fun `VD-23 severe anaemia with dizziness reports a critical condition`() {
    // NOTE: Dizziness (code 7) is itself a Q17 danger sign, so
    // checkCriticalConditions()'s hasAnyDangerSign branch fires first and
    // surfaces the generic danger-sign message rather than the
    // anaemia-specific one — per spec this still counts as "a critical
    // condition reported" (VD-23 does not require a specific message).
    val viewModel = createViewModel()
    viewModel.toggleDangerSign(7) // Dizziness
    viewModel.setHemoglobin("6.5")

    assertTrue(viewModel.uiState.value.visitData.hasCriticalAnaemia)
    assertEquals(
      viewModel.uiState.value.visitData.hasAnyDangerSign,
      viewModel.uiState.value.criticalCondition != null,
    )
  }

  @Test
  fun `VD-24 hypoglycaemia with symptoms reports a critical condition`() {
    // Same precedence note as VD-23 — dizziness/cold-sweat (code 7) is a
    // danger sign, so the generic message wins; still "a condition reported".
    val viewModel = createViewModel()
    viewModel.toggleDangerSign(7)
    viewModel.setBloodGlucose("60")

    assertTrue(viewModel.uiState.value.visitData.hasCriticalHypoglycaemia)
    assertEquals(
      R.string.visit_form_critical_danger_sign,
      viewModel.uiState.value.criticalCondition?.messageRes,
    )
  }

  @Test
  fun `VD-25 any danger sign selected reports a critical condition immediately`() {
    val viewModel = createViewModel()
    viewModel.toggleDangerSign(3)

    assertEquals(
      R.string.visit_form_critical_danger_sign,
      viewModel.uiState.value.criticalCondition?.messageRes,
    )
  }

  @Test
  fun `VD-26 mild moderate values do not trigger a critical condition`() {
    val viewModel = createViewModel()
    viewModel.setBpSystolic("140")
    viewModel.setBpDiastolic("90")
    viewModel.setHemoglobin("9.0")

    assertNull(viewModel.uiState.value.criticalCondition)
  }

  // --- VD-27..31: Completion gate & sub-tab persistence -------------------------

  @Test
  fun `VD-27 incomplete visit data blocks summary and shows banner`() {
    val viewModel = createViewModel()

    viewModel.goToSummary()

    val state = viewModel.uiState.value
    assertEquals(VisitFormStep.VISIT_DATA, state.currentStep)
    assertTrue(state.visitData.showValidationBanner)
  }

  @Test
  fun `VD-28 banner clears once fields valid`() {
    val viewModel = createViewModel()
    viewModel.goToSummary() // Blocked — raises the banner.
    assertTrue(viewModel.uiState.value.visitData.showValidationBanner)

    fillMinimalValidVisitData(viewModel)
    assertFalse(viewModel.uiState.value.visitData.showValidationBanner)
  }

  @Test
  fun `VD-29 complete visit data advances to summary`() {
    val viewModel = createViewModel()
    fillMinimalValidVisitData(viewModel)

    viewModel.goToSummary()

    val state = viewModel.uiState.value
    assertEquals(VisitFormStep.SUMMARY, state.currentStep)
    assertEquals(VisitFormStep.SUMMARY, state.furthestStep)
  }

  @Test
  fun `VD-30 sub-tab switching does not lose entered values`() {
    val viewModel = createViewModel()
    viewModel.setWeightKg("58")

    viewModel.goToSubTab(VisitDataSubTab.SYMPTOMS)
    viewModel.goToSubTab(VisitDataSubTab.TESTS)

    assertEquals("58", viewModel.uiState.value.visitData.weightKg)
  }

  @Test
  fun `VD-31 exit discards visit data same as any other step`() = runTest {
    val viewModel = createViewModel()
    viewModel.setWeightKg("58")

    viewModel.exitForm()

    assertEquals("", viewModel.uiState.value.visitData.weightKg)
    assertEquals(VisitFormEvent.ExitForm, viewModel.events.first())
  }

  // --- VD-32..37: Per-sub-tab Next gating (Tests → Symptoms → History → Summary) --

  @Test
  fun `VD-32 goToSymptoms blocked with banner until tests fields are valid`() {
    val viewModel = createViewModel()

    viewModel.goToSymptoms()

    val state = viewModel.uiState.value
    assertEquals(VisitDataSubTab.TESTS, state.visitDataSubTab)
    assertTrue(state.visitData.showValidationBanner)
  }

  @Test
  fun `VD-33 goToSymptoms advances the sub-tab once tests fields are valid`() {
    val viewModel = createViewModel()
    fillTestsFields(viewModel)

    viewModel.goToSymptoms()

    val state = viewModel.uiState.value
    assertEquals(VisitDataSubTab.SYMPTOMS, state.visitDataSubTab)
    assertFalse(state.visitData.showValidationBanner)
    // Still the Visit Data step — this only moves the sub-tab, not the stepper.
    assertEquals(VisitFormStep.VISIT_DATA, state.currentStep)
  }

  @Test
  fun `VD-34 goToHistory blocked with banner until symptoms fields are valid`() {
    val viewModel = createViewModel()
    viewModel.goToSubTab(VisitDataSubTab.SYMPTOMS)

    viewModel.goToHistory()

    val state = viewModel.uiState.value
    assertEquals(VisitDataSubTab.SYMPTOMS, state.visitDataSubTab)
    assertTrue(state.visitData.showValidationBanner)
  }

  @Test
  fun `VD-35 goToHistory advances the sub-tab once symptoms fields are valid`() {
    val viewModel = createViewModel()
    viewModel.goToSubTab(VisitDataSubTab.SYMPTOMS)
    fillSymptomsFields(viewModel)

    viewModel.goToHistory()

    val state = viewModel.uiState.value
    assertEquals(VisitDataSubTab.HISTORY, state.visitDataSubTab)
    assertFalse(state.visitData.showValidationBanner)
  }

  @Test
  fun `VD-36 jumping straight to history via the pill tabs still requires every sub-tab for Summary`() {
    val viewModel = createViewModel()
    // Skip Tests/Symptoms entirely via the pill tabs, fill only History.
    viewModel.goToSubTab(VisitDataSubTab.HISTORY)
    fillHistoryFields(viewModel)

    viewModel.goToSummary()

    val state = viewModel.uiState.value
    assertEquals(VisitFormStep.VISIT_DATA, state.currentStep)
    assertTrue(state.visitData.showValidationBanner)
  }

  @Test
  fun `VD-37 completing all three sub-tabs in order reaches summary`() {
    val viewModel = createViewModel()

    fillTestsFields(viewModel)
    viewModel.goToSymptoms()
    fillSymptomsFields(viewModel)
    viewModel.goToHistory()
    fillHistoryFields(viewModel)
    viewModel.goToSummary()

    val state = viewModel.uiState.value
    assertEquals(VisitFormStep.SUMMARY, state.currentStep)
    assertEquals(VisitDataSubTab.HISTORY, state.visitDataSubTab)
  }
}
