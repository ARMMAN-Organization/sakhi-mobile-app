package org.armman.sakhi.ui.visitform

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.visitform.CriticalCondition
import org.armman.sakhi.data.visitform.VisitDataSubTab
import org.armman.sakhi.data.visitform.VisitFormRepository
import org.armman.sakhi.data.visitform.VisitFormStep
import org.armman.sakhi.data.visitform.VisitRiskAssessment
import org.armman.sakhi.data.visitform.VisitRiskFinding
import java.time.LocalDate
import javax.inject.Inject

/** Whole-flow UI state. [furthestStep] gates forward tab navigation, mirroring Enrollment. */
data class VisitFormUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val currentStep: VisitFormStep = VisitFormStep.VISIT_DATA,
  val furthestStep: VisitFormStep = VisitFormStep.VISIT_DATA,
  val visitDataSubTab: VisitDataSubTab = VisitDataSubTab.TESTS,
  val visitData: VisitDataState = VisitDataState(),
  /** CR-016c Summary banner chips — the beneficiary's own recorded diagnoses. */
  val comorbidities: List<String> = emptyList(),
  /** FR-S-4.4 scaffold — set once CR-016b's threshold checks detect a danger sign. */
  val criticalCondition: CriticalCondition? = null,
) {
  /**
   * CR-016c: always recomputed from [visitData] — never stored separately, so
   * there is no risk of the Summary tab showing stale findings after an Edit.
   */
  val summaryTestsFindings: List<VisitRiskFinding> get() = VisitRiskAssessment.buildTestsFindings(visitData)
  val summarySymptomsFindings: List<VisitRiskFinding> get() = VisitRiskAssessment.buildSymptomsFindings(visitData)
  val summaryRiskLevel: RiskLevel
    get() = VisitRiskAssessment.overall((summaryTestsFindings + summarySymptomsFindings).map { it.riskLevel })
}

/** One-shot events — the screen collects these and performs the actual navigation. */
sealed interface VisitFormEvent {
  /** Draft discarded (confirmed exit, or a critical-condition dismissal) — leave the flow. */
  data object ExitForm : VisitFormEvent

  /** Feature not available in this CR (Referral submit — real save lands in CR-016d). */
  data object ComingSoon : VisitFormEvent
}

/**
 * State machine for the Visit Form stepper. CR-016a shipped the shell/gating;
 * CR-016b adds the Visit Data fields (Q1–56), their FR-S-4.7 validation, and
 * the FR-S-4.4 critical-pathway checks. Summary/Health Info/Referral bodies
 * and submit persistence remain CR-016c/d.
 */
@HiltViewModel
class VisitFormViewModel @Inject constructor(
  private val repository: VisitFormRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  val beneficiaryId: String = savedStateHandle[NAV_ARG_BENEFICIARY_ID] ?: ""
  val visitId: String = savedStateHandle[NAV_ARG_VISIT_ID] ?: ""
  val visitLabel: String = savedStateHandle[NAV_ARG_LABEL] ?: ""

  private val _uiState = MutableStateFlow(VisitFormUiState())
  val uiState: StateFlow<VisitFormUiState> = _uiState.asStateFlow()

  private val _events = Channel<VisitFormEvent>(Channel.BUFFERED)
  val events: Flow<VisitFormEvent> = _events.receiveAsFlow()

  init {
    loadContext()
  }

  /** Loads (or reloads after an error) the beneficiary's carried-forward visit context. */
  fun loadContext() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        require(beneficiaryId.isNotBlank() && visitId.isNotBlank()) {
          "Missing beneficiary id or visit id"
        }
        val context = repository.getVisitContext(beneficiaryId, visitId)
        _uiState.update {
          it.copy(
            isLoading = false,
            visitData = VisitDataState.from(context),
            comorbidities = context.comorbidities,
          )
        }
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      }
    }
  }

  // --- Stepper navigation ---------------------------------------------------

  /** Tab navigation: only steps already reached may be revisited. */
  fun goToStep(step: VisitFormStep) {
    if (step.ordinal > _uiState.value.furthestStep.ordinal) return
    _uiState.update { it.copy(currentStep = step) }
  }

  fun goToSubTab(tab: VisitDataSubTab) {
    _uiState.update { it.copy(visitDataSubTab = tab) }
  }

  /** Tests' own Next: blocked with the banner until the Tests fields are valid. */
  fun goToSymptoms() {
    if (_uiState.value.visitData.isTestsComplete) {
      updateVisitData { it.copy(showValidationBanner = false) }
      _uiState.update { it.copy(visitDataSubTab = VisitDataSubTab.SYMPTOMS) }
    } else {
      updateVisitData { it.copy(showValidationBanner = true) }
    }
  }

  /** Symptoms' own Next: blocked with the banner until the Symptoms fields are valid. */
  fun goToHistory() {
    if (_uiState.value.visitData.isSymptomsComplete) {
      updateVisitData { it.copy(showValidationBanner = false) }
      _uiState.update { it.copy(visitDataSubTab = VisitDataSubTab.HISTORY) }
    } else {
      updateVisitData { it.copy(showValidationBanner = true) }
    }
  }

  /**
   * History's own Next, and the Visit Data step's overall gate: blocked with
   * the banner until every sub-tab is valid (VD-27/29) — re-checked in full
   * here (not just History) since the sub-tab pills allow jumping straight to
   * History without visiting Tests/Symptoms first.
   */
  fun goToSummary() {
    if (_uiState.value.currentStep != VisitFormStep.VISIT_DATA) return
    if (_uiState.value.visitData.isComplete) {
      updateVisitData { it.copy(showValidationBanner = false) }
      moveTo(VisitFormStep.SUMMARY)
    } else {
      // Raise the banner directly (not via updateVisitData): the whole-form gate
      // must stay visible even when the on-screen sub-tab is itself complete but
      // another sub-tab the user skipped via the pills is not (VD-36).
      _uiState.update { it.copy(visitData = it.visitData.copy(showValidationBanner = true)) }
    }
  }

  /** CR-016c: Summary's Tests card "Edit" — jump back to Visit Data/Tests. */
  fun editTests() {
    _uiState.update { it.copy(currentStep = VisitFormStep.VISIT_DATA, visitDataSubTab = VisitDataSubTab.TESTS) }
  }

  /** CR-016c: Summary's Symptoms card "Edit" — jump back to Visit Data/Symptoms. */
  fun editSymptoms() {
    _uiState.update { it.copy(currentStep = VisitFormStep.VISIT_DATA, visitDataSubTab = VisitDataSubTab.SYMPTOMS) }
  }

  fun goToHealthInfo() = moveTo(VisitFormStep.HEALTH_INFO)

  fun goToReferral() = moveTo(VisitFormStep.REFERRAL)

  /** In-flow back: one step backwards, draft retained. From the first step, exits instead. */
  fun goBack() {
    val current = _uiState.value.currentStep
    if (current == VisitFormStep.VISIT_DATA) {
      _events.trySend(VisitFormEvent.ExitForm)
      return
    }
    val previous = VisitFormStep.entries[current.ordinal - 1]
    _uiState.update { it.copy(currentStep = previous) }
  }

  /** Submit is not implemented until CR-016d — stubbed as a one-shot toast. */
  fun onSubmit() {
    _events.trySend(VisitFormEvent.ComingSoon)
  }

  /**
   * Confirmed exit (FR-S-4.2): no partial-form persistence, ever. Drops the
   * entire in-memory draft and the visit remains OPEN.
   */
  fun exitForm() {
    _uiState.update {
      VisitFormUiState(isLoading = false, visitData = VisitDataState())
    }
    _events.trySend(VisitFormEvent.ExitForm)
  }

  // --- Visit Data — Q1–4 Visit tracking --------------------------------------

  fun setVisitDate(date: LocalDate) = updateVisitData { it.copy(visitDate = date) }

  fun setMetBeneficiary(value: Boolean) = updateVisitData {
    it.copy(metBeneficiary = value, notMetReason = if (value) null else it.notMetReason)
  }

  fun setNotMetReason(code: Int) = updateVisitData { it.copy(notMetReason = code) }

  // --- Visit Data — Q5–11 Pregnancy dating -----------------------------------

  fun setRchNumber(value: String) = updateVisitData { it.copy(rchNumber = value) }

  /** Editing LMP recalculates GA/EDD (derived getters) and re-gates the sonography photo. */
  fun setLmp(date: LocalDate) = updateVisitData {
    it.copy(lmp = date, sonographyPhotoUri = if (date == it.originalLmp) it.sonographyPhotoUri else null)
  }

  fun setHasSonographyReport(value: Boolean) = updateVisitData { it.copy(hasSonographyReport = value) }

  fun onSonographyPhotoCaptured(uri: String) = updateVisitData { it.copy(sonographyPhotoUri = uri) }

  // --- Visit Data — Tests sub-tab (Q12–16, Q23–32) ---------------------------

  fun setHeightCm(value: String) = updateVisitData { it.copy(heightCm = value) }

  fun setWeightKg(value: String) = updateVisitData { it.copy(weightKg = value) }

  fun setMuacCm(value: String) = updateVisitData { it.copy(muacCm = value) }

  fun setBpSystolic(value: String) {
    updateVisitData { it.copy(bpSystolic = value) }
    checkCriticalConditions()
  }

  fun setBpDiastolic(value: String) {
    updateVisitData { it.copy(bpDiastolic = value) }
    checkCriticalConditions()
  }

  fun setTemperatureF(value: String) = updateVisitData { it.copy(temperatureF = value) }

  fun setHemoglobin(value: String) {
    updateVisitData { it.copy(hemoglobin = value, hbConfirmed = false) }
    checkCriticalConditions()
  }

  fun confirmHemoglobin() = updateVisitData { it.copy(hbConfirmed = true) }

  fun setLastMealHours(value: String) = updateVisitData { it.copy(lastMealHours = value) }

  fun setBloodGlucose(value: String) {
    updateVisitData { it.copy(bloodGlucose = value) }
    checkCriticalConditions()
  }

  /** Q29: selecting "Normal" clears/disables the other options and vice versa. */
  fun toggleUrineTest(code: Int) = updateVisitData {
    it.copy(urineTest = toggleMulti(it.urineTest, code, setOf(VisitDataState.URINE_NORMAL)))
  }

  fun setFetalMovements(code: Int) = updateVisitData { it.copy(fetalMovements = code) }

  fun setFetalHeartRate(value: String) = updateVisitData { it.copy(fetalHeartRate = value) }

  fun setFundalHeightCm(value: String) = updateVisitData { it.copy(fundalHeightCm = value) }

  // --- Visit Data — Symptoms sub-tab (Q17–22) --------------------------------

  /** Q17: "No abnormal signs" is exclusive against every specific danger sign. */
  fun toggleDangerSign(code: Int) {
    updateVisitData {
      it.copy(dangerSigns = toggleMulti(it.dangerSigns, code, setOf(VisitDataState.DANGER_SIGN_NONE)))
    }
    checkCriticalConditions()
  }

  fun setPalmNails(code: Int) = updateVisitData { it.copy(palmNails = code) }

  fun setSclera(code: Int) = updateVisitData { it.copy(sclera = code) }

  fun setSkin(code: Int) = updateVisitData { it.copy(skin = code) }

  fun toggleSwelling(code: Int) {
    updateVisitData {
      it.copy(swelling = toggleMulti(it.swelling, code, setOf(VisitDataState.SWELLING_NONE)))
    }
    checkCriticalConditions()
  }

  fun toggleDehydration(code: Int) = updateVisitData {
    it.copy(dehydration = toggleMulti(it.dehydration, code, emptySet()))
  }

  // --- Visit Data — History sub-tab: Td doses (Q33) --------------------------

  fun setTdNone(checked: Boolean) = updateVisitData {
    if (checked) {
      it.copy(
        tdNone = true,
        td1 = false, td1Date = null,
        td2 = false, td2Date = null,
        tdBooster = false, tdBoosterDate = null,
      )
    } else {
      it.copy(tdNone = false)
    }
  }

  fun setTd1(checked: Boolean) = updateVisitData {
    it.copy(tdNone = false, td1 = checked, td1Date = if (checked) it.td1Date else null)
  }

  fun setTd1Date(date: LocalDate) = updateVisitData { it.copy(td1Date = date) }

  fun setTd2(checked: Boolean) = updateVisitData {
    it.copy(tdNone = false, td2 = checked, td2Date = if (checked) it.td2Date else null)
  }

  fun setTd2Date(date: LocalDate) = updateVisitData { it.copy(td2Date = date) }

  fun setTdBooster(checked: Boolean) = updateVisitData {
    it.copy(tdNone = false, tdBooster = checked, tdBoosterDate = if (checked) it.tdBoosterDate else null)
  }

  fun setTdBoosterDate(date: LocalDate) = updateVisitData { it.copy(tdBoosterDate = date) }

  // --- Visit Data — History sub-tab: Supplements & adherence (Q34–39) --------

  fun toggleFoodConsumed(code: Int) = updateVisitData {
    it.copy(foodConsumed24h = toggleMulti(it.foodConsumed24h, code, emptySet()))
  }

  fun toggleFoodAvoided(code: Int) = updateVisitData {
    it.copy(foodAvoided = toggleMulti(it.foodAvoided, code, emptySet()))
  }

  fun setTakingIfa(value: Boolean) = updateVisitData {
    it.copy(
      takingIfa = value,
      ifaTabletsConsumed = if (value) it.ifaTabletsConsumed else "",
      ifaNonConsumptionReasons = if (value) emptySet() else it.ifaNonConsumptionReasons,
    )
  }

  fun setIfaTabletsConsumed(value: String) = updateVisitData { it.copy(ifaTabletsConsumed = value) }

  fun toggleIfaNonConsumptionReason(code: Int) = updateVisitData {
    it.copy(ifaNonConsumptionReasons = toggleMulti(it.ifaNonConsumptionReasons, code, emptySet()))
  }

  fun setCalciumTaken(value: Boolean) = updateVisitData { it.copy(calciumTaken = value) }

  // --- Visit Data — History sub-tab: Birth prep & mental health (Q40–56) -----

  fun setVisitedFacilitySinceLastVisit(value: Boolean) = updateVisitData {
    it.copy(
      visitedFacilitySinceLastVisit = value,
      lastAncVisitDate = if (value) it.lastAncVisitDate else null,
    )
  }

  fun setLastAncVisitDate(date: LocalDate) = updateVisitData { it.copy(lastAncVisitDate = date) }

  fun setAdvisedDeliveryPlace(code: Int) = updateVisitData { it.copy(advisedDeliveryPlace = code) }

  fun setSickleCell(code: Int) = updateVisitData { it.copy(sickleCell = code) }

  fun toggleFamilyPlanningMethod(code: Int) = updateVisitData {
    it.copy(familyPlanningMethods = toggleMulti(it.familyPlanningMethods, code, emptySet()))
  }

  fun setFeelingStressed(value: Boolean) = updateVisitData { it.copy(feelingStressed = value) }

  fun setAdequateFamilySupport(value: Boolean) = updateVisitData { it.copy(adequateFamilySupport = value) }

  fun setPlanningMigration(value: Boolean) = updateVisitData { it.copy(planningMigration = value) }

  fun setUsgDone(value: Boolean) = updateVisitData {
    it.copy(
      usgDone = value,
      usgDate = if (value) it.usgDate else null,
      usgType = if (value) it.usgType else null,
      usgFinding = if (value) it.usgFinding else null,
    )
  }

  fun setUsgDate(date: LocalDate) = updateVisitData { it.copy(usgDate = date) }

  fun setUsgType(code: Int) = updateVisitData { it.copy(usgType = code) }

  fun setUsgFinding(code: Int) = updateVisitData { it.copy(usgFinding = code) }

  fun setTransportContactShared(value: Boolean) = updateVisitData { it.copy(transportContactShared = value) }

  fun setFundsArranged(value: Boolean) = updateVisitData { it.copy(fundsArranged = value) }

  fun setBirthCompanionIdentified(value: Boolean) = updateVisitData { it.copy(birthCompanionIdentified = value) }

  fun setRemarks(value: String) = updateVisitData { it.copy(remarks = value) }

  fun toggleCounsellingTopic(code: Int) = updateVisitData {
    it.copy(counsellingTopics = toggleMulti(it.counsellingTopics, code, emptySet()))
  }

  // --- Critical-condition scaffold (FR-S-4.4) --------------------------------

  /** Re-evaluated after every Tests/Symptoms field that can trigger a critical pathway. */
  private fun checkCriticalConditions() {
    val data = _uiState.value.visitData
    val messageRes = when {
      data.hasAnyDangerSign -> R.string.visit_form_critical_danger_sign
      data.hasCriticalHypertension -> R.string.visit_form_critical_hypertension
      data.hasCriticalAnaemia -> R.string.visit_form_critical_anaemia
      data.hasCriticalHypoglycaemia -> R.string.visit_form_critical_hypoglycaemia
      else -> null
    }
    if (messageRes != null) reportCriticalCondition(CriticalCondition(messageRes))
  }

  fun reportCriticalCondition(condition: CriticalCondition) {
    _uiState.update { it.copy(criticalCondition = condition) }
  }

  /** FR-S-4.4 Option B: closing the banner discards and exits the form — no partial save. */
  fun dismissCritical() {
    _uiState.update { it.copy(criticalCondition = null) }
    exitForm()
  }

  // --- Internal ---------------------------------------------------------------

  private fun moveTo(step: VisitFormStep) {
    _uiState.update {
      it.copy(
        currentStep = step,
        furthestStep = if (step.ordinal > it.furthestStep.ordinal) step else it.furthestStep,
      )
    }
  }

  private inline fun updateVisitData(crossinline transform: (VisitDataState) -> VisitDataState) {
    _uiState.update { state ->
      var next = transform(state.visitData)
      if (next.showValidationBanner) {
        // Clear as soon as the sub-tab actually on screen is valid, rather
        // than waiting on the other two sub-tabs the user hasn't reached yet.
        val activeSubTabComplete = when (state.visitDataSubTab) {
          VisitDataSubTab.TESTS -> next.isTestsComplete
          VisitDataSubTab.SYMPTOMS -> next.isSymptomsComplete
          VisitDataSubTab.HISTORY -> next.isHistoryComplete
        }
        if (activeSubTabComplete) next = next.copy(showValidationBanner = false)
      }
      state.copy(visitData = next)
    }
  }

  /**
   * Multi-select toggle with mutual exclusion. Selecting an [exclusive] code
   * clears everything else; selecting a normal code clears any exclusive codes.
   */
  private fun toggleMulti(current: Set<Int>, code: Int, exclusive: Set<Int>): Set<Int> = when {
    code in current -> current - code
    code in exclusive -> setOf(code)
    else -> (current + code) - exclusive
  }

  companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_VISIT_ID = "visitId"
    const val NAV_ARG_LABEL = "label"
  }
}
