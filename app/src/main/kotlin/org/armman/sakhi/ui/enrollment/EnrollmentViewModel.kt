package org.armman.sakhi.ui.enrollment

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
import org.armman.sakhi.data.enrollment.EnrollmentRepository
import org.armman.sakhi.data.enrollment.EnrollmentSubmitResult
import org.armman.sakhi.data.geography.GeographyRepository
import org.armman.sakhi.data.profile.ProfileRepository
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** Who is being registered — mirrors the design's entry selector. */
enum class BeneficiaryType { CHILD, PREGNANT_WOMAN }

/**
 * Ordered steps of the enrollment flow. ENTRY is the type selector shown
 * before the stepper; COMPLETE is the success screen after submission.
 */
enum class EnrollmentStep { ENTRY, CONSENT, PERSONAL_INFO, HEALTH_HISTORY, SUMMARY, COMPLETE }

/** One-shot UI events (toasts) — collected by the screen. */
sealed interface EnrollmentEvent {
  /** Feature not available in this CR (Child flow, media, Visit Form). */
  data object ComingSoon : EnrollmentEvent

  /** Submit succeeded — drives the green "Data has been saved" toast (SM-8). */
  data object DataSaved : EnrollmentEvent
}

/**
 * Consent step answers — Excel "Revised App Form Final 20.3.26" Q1–4 plus the
 * four willingness checkboxes shown on the design's Consent frame.
 */
data class ConsentState(
  val videoPlayed: Boolean = false,
  val photoTaken: Boolean = false,
  /** App-private URI of the captured consent-form photo (Q4). */
  val photoUri: String? = null,
  val willingPersonalInfo: Boolean = false,
  val willingHealthHistory: Boolean = false,
  val willingDiagnosticTests: Boolean = false,
  val understandsReferral: Boolean = false,
  /** "Did we receive consent?" — null until answered (Q3). */
  val consentReceived: Boolean? = null,
) {
  /** Q3: No → stop form. Surfaced as a blocking message on the step. */
  val consentRefused: Boolean get() = consentReceived == false

  /**
   * Gate for advancing to Personal Info: all four checkboxes, the consent
   * photo captured (Q4, mandatory per the Excel spec), and consent not
   * explicitly refused. The design's Consent frame has NO "Did we receive
   * consent?" radio (Excel Q3) — the API is kept for when design adds it, so
   * an unanswered Q3 must not block.
   */
  val isComplete: Boolean
    get() = consentReceived != false &&
      willingPersonalInfo &&
      willingHealthHistory &&
      willingDiagnosticTests &&
      understandsReferral &&
      photoTaken
}

/** Whole-flow UI state. [furthestStep] gates forward tab navigation. */
data class EnrollmentUiState(
  val beneficiaryType: BeneficiaryType? = null,
  val currentStep: EnrollmentStep = EnrollmentStep.ENTRY,
  val furthestStep: EnrollmentStep = EnrollmentStep.ENTRY,
  val consent: ConsentState = ConsentState(),
  val personalInfo: PersonalInfoState = PersonalInfoState(),
  val healthHistory: HealthHistoryState = HealthHistoryState(),
  /** A repository save is in flight — guards double submits (SM-10). */
  val isSubmitting: Boolean = false,
  /** Last submit failed — Summary shows a retryable error (SM-9). */
  val submitFailed: Boolean = false,
  /** Backend-provided detail for [submitFailed] (validation/conflict message), when available —
   * null falls back to the generic retry message. */
  val submitErrorMessage: String? = null,
  /** Incremented on every blocked Next attempt (Personal Info/Health History) so the screen can
   * scroll back to the validation banner even if it was already showing — without this, tapping
   * Next while scrolled down to the button leaves the (off-screen) banner unnoticed and the step
   * looks like it silently did nothing. */
  val validationScrollTrigger: Int = 0,
) {
  /** Submit gate (SM-6): full draft valid and no save already running. */
  val canSubmit: Boolean
    get() = consent.isComplete && personalInfo.isComplete &&
      healthHistory.isComplete && !isSubmitting
}

/**
 * State machine for the enrollment flow. Draft lives in memory; on submit
 * (CR-015d) it is mapped to an [org.armman.sakhi.data.enrollment.EnrollmentRecord]
 * and saved through [EnrollmentRepository].
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
  private val geographyRepository: GeographyRepository,
  private val profileRepository: ProfileRepository,
  private val enrollmentRepository: EnrollmentRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(EnrollmentUiState())
  val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

  init {
    initializePersonalInfo()
  }

  // Buffered channel: events emitted before the UI collects are not lost,
  // and each event is delivered exactly once.
  private val _events = Channel<EnrollmentEvent>(Channel.BUFFERED)
  val events: Flow<EnrollmentEvent> = _events.receiveAsFlow()

  // --- Entry selector -------------------------------------------------------

  fun selectBeneficiaryType(type: BeneficiaryType) {
    if (type == BeneficiaryType.CHILD) {
      // Child flow is out of scope for CR-015: signal and keep PW-only latch.
      _events.trySend(EnrollmentEvent.ComingSoon)
      return
    }
    _uiState.update { it.copy(beneficiaryType = type) }
  }

  fun startEnrollment() {
    val state = _uiState.value
    if (state.beneficiaryType != BeneficiaryType.PREGNANT_WOMAN) return
    moveTo(EnrollmentStep.CONSENT)
  }

  // --- Consent step ---------------------------------------------------------

  fun setWillingPersonalInfo(checked: Boolean) =
    updateConsent { it.copy(willingPersonalInfo = checked) }

  fun setWillingHealthHistory(checked: Boolean) =
    updateConsent { it.copy(willingHealthHistory = checked) }

  fun setWillingDiagnosticTests(checked: Boolean) =
    updateConsent { it.copy(willingDiagnosticTests = checked) }

  fun setUnderstandsReferral(checked: Boolean) =
    updateConsent { it.copy(understandsReferral = checked) }

  fun setConsentReceived(received: Boolean) =
    updateConsent { it.copy(consentReceived = received) }

  /** Stub — real playback deferred; marks Q1 satisfied for flow purposes. */
  fun onPlayVideo() {
    updateConsent { it.copy(videoPlayed = true) }
    _events.trySend(EnrollmentEvent.ComingSoon)
  }

  /**
   * Live capture result (Q4). Retake replaces the previous URI. A cancelled
   * capture (success=false) leaves prior state untouched.
   */
  fun onConsentPhotoCaptured(uri: String) {
    updateConsent { it.copy(photoTaken = true, photoUri = uri) }
  }

  fun goToPersonalInfo() {
    if (!_uiState.value.consent.isComplete) return
    moveTo(EnrollmentStep.PERSONAL_INFO)
  }

  // --- Personal Info step (Excel Q5–34) --------------------------------------

  /** Prefill: local UUID, project name, Sakhi's geography assignment (Q8–14). */
  private fun initializePersonalInfo() {
    updatePersonalInfo { it.copy(beneficiaryId = UUID.randomUUID().toString()) }
    viewModelScope.launch {
      val projectName = profileRepository.getProfile().projectName
      val assignment = geographyRepository.getSakhiAssignment()
      val states = geographyRepository.getStates()
      val districts = geographyRepository.getDistricts(assignment.stateId)
      val blocks = geographyRepository.getBlocks(assignment.districtId)
      val villages = geographyRepository.getVillages(assignment.blockId)
      updatePersonalInfo {
        it.copy(
          projectName = projectName,
          states = states,
          districts = districts,
          blocks = blocks,
          villages = villages,
          stateId = assignment.stateId,
          districtId = assignment.districtId,
          blockId = assignment.blockId,
        )
      }
    }
  }

  /** The "No" radio is a deliberate no-op until ARMMAN defines the flow. */
  fun setLmpKnown(known: Boolean) {
    if (!known) return
    updatePersonalInfo { it.copy(lmpKnown = true) }
  }

  fun setLmp(date: LocalDate) {
    updatePersonalInfo { it.copy(lmp = date) }
    // Mirror gestational age into Health History so trimester (Q35) derives.
    val ga = _uiState.value.personalInfo.gestationalAgeWeeks
    updateHealthHistory { it.copy(gestationalAgeWeeks = ga) }
  }

  fun setFirstName(value: String) = updatePersonalInfo { it.copy(firstName = value) }

  fun setMiddleName(value: String) = updatePersonalInfo { it.copy(middleName = value) }

  fun setLastName(value: String) = updatePersonalInfo { it.copy(lastName = value) }

  /** DOB entry auto-derives age (floor of full years — Excel Q20). */
  fun setDob(date: LocalDate) = updatePersonalInfo {
    it.copy(dob = date, ageYears = PersonalInfoState.ageFromDob(date, LocalDate.now()))
  }

  /** Direct age entry (allowed without DOB — Excel Q20). */
  fun setAge(value: String) = updatePersonalInfo {
    it.copy(ageYears = value.toIntOrNull(), dob = null)
  }

  fun setAddress(value: String) = updatePersonalInfo { it.copy(address = value) }

  fun setMobileNumber(value: String) = updatePersonalInfo { it.copy(mobileNumber = value) }

  fun setPhoneOwner(code: Int) = updatePersonalInfo { it.copy(phoneOwner = code) }

  fun setNetworkAvailability(code: Int) =
    updatePersonalInfo { it.copy(networkAvailability = code) }

  fun setEducationSelf(code: Int) = updatePersonalInfo { it.copy(educationSelf = code) }

  fun setEducationPartner(code: Int) = updatePersonalInfo { it.copy(educationPartner = code) }

  fun setPartnerOccupation(code: Int) = updatePersonalInfo { it.copy(partnerOccupation = code) }

  fun setYearsInVillage(value: String) = updatePersonalInfo { it.copy(yearsInVillage = value) }

  fun setMigrationPattern(code: Int) = updatePersonalInfo { it.copy(migrationPattern = code) }

  fun setIncomeBand(code: Int) = updatePersonalInfo { it.copy(incomeBand = code) }

  fun setReligion(code: Int) = updatePersonalInfo { it.copy(religion = code) }

  fun setCategory(code: Int) = updatePersonalInfo { it.copy(category = code) }

  fun setHouseholdMembers(value: String) =
    updatePersonalInfo { it.copy(householdMembers = value) }

  fun setChildrenUnderFive(value: String) =
    updatePersonalInfo { it.copy(childrenUnderFive = value) }

  // Geography cascade (Q12–18): changing a parent clears every descendant
  // selection and reloads its option list; single-option lists auto-select.

  fun selectState(stateId: String) {
    updatePersonalInfo {
      it.copy(
        stateId = stateId,
        districtId = null, blockId = null, villageId = null,
        padaId = null, phcId = null, subCentreId = null,
        districts = emptyList(), blocks = emptyList(), villages = emptyList(),
        padas = emptyList(), phcs = emptyList(), subCentres = emptyList(),
      )
    }
    viewModelScope.launch {
      val districts = geographyRepository.getDistricts(stateId)
      updatePersonalInfo { it.copy(districts = districts) }
    }
  }

  fun selectDistrict(districtId: String) {
    updatePersonalInfo {
      it.copy(
        districtId = districtId,
        blockId = null, villageId = null, padaId = null, phcId = null, subCentreId = null,
        blocks = emptyList(), villages = emptyList(),
        padas = emptyList(), phcs = emptyList(), subCentres = emptyList(),
      )
    }
    viewModelScope.launch {
      val blocks = geographyRepository.getBlocks(districtId)
      updatePersonalInfo { it.copy(blocks = blocks) }
    }
  }

  fun selectBlock(blockId: String) {
    updatePersonalInfo {
      it.copy(
        blockId = blockId,
        villageId = null, padaId = null, phcId = null, subCentreId = null,
        villages = emptyList(), padas = emptyList(), phcs = emptyList(), subCentres = emptyList(),
      )
    }
    viewModelScope.launch {
      val villages = geographyRepository.getVillages(blockId)
      updatePersonalInfo { it.copy(villages = villages) }
    }
  }

  fun selectVillage(villageId: String) {
    updatePersonalInfo {
      it.copy(
        villageId = villageId,
        padaId = null, phcId = null, subCentreId = null,
        padas = emptyList(), phcs = emptyList(), subCentres = emptyList(),
      )
    }
    viewModelScope.launch {
      val padas = geographyRepository.getPadas(villageId)
      val phcs = geographyRepository.getPhcs(villageId)
      val subCentres = geographyRepository.getSubCentres(villageId)
      updatePersonalInfo {
        it.copy(
          padas = padas,
          phcs = phcs,
          subCentres = subCentres,
          // PHC/SC auto-populate from the village (Q17/Q18).
          padaId = padas.singleOrNull()?.id,
          phcId = phcs.singleOrNull()?.id,
          subCentreId = subCentres.singleOrNull()?.id,
        )
      }
    }
  }

  fun selectPada(padaId: String) = updatePersonalInfo { it.copy(padaId = padaId) }

  fun selectPhc(phcId: String) = updatePersonalInfo { it.copy(phcId = phcId) }

  fun selectSubCentre(subCentreId: String) =
    updatePersonalInfo { it.copy(subCentreId = subCentreId) }

  /** Next from Personal Info: blocked with the banner until valid (PI-23/25). */
  fun goToHealthHistory() {
    if (_uiState.value.currentStep != EnrollmentStep.PERSONAL_INFO) return
    if (_uiState.value.personalInfo.isComplete) {
      updatePersonalInfo { it.copy(showValidationBanner = false) }
      moveTo(EnrollmentStep.HEALTH_HISTORY)
    } else {
      updatePersonalInfo { it.copy(showValidationBanner = true) }
      bumpValidationScrollTrigger()
    }
  }

  // --- Health History step (Excel Q35–65) ------------------------------------

  fun setPlannedPregnancy(code: Int) = updateHealthHistory { it.copy(plannedPregnancy = code) }

  /** Q37 No → clear the conditional treatment type (Q38). */
  fun setTookTreatment(value: Boolean) = updateHealthHistory {
    it.copy(tookTreatment = value, treatmentType = if (value) it.treatmentType else null)
  }

  fun setTreatmentType(code: Int) = updateHealthHistory { it.copy(treatmentType = code) }

  /** Q39: clear the RCH number unless "card available" stays selected. */
  fun setRchStatus(code: Int) = updateHealthHistory {
    it.copy(
      rchStatus = code,
      rchNumber = if (code == HealthHistoryState.RCH_CARD_AVAILABLE) it.rchNumber else "",
    )
  }

  fun setRchNumber(value: String) = updateHealthHistory { it.copy(rchNumber = value) }

  /** Q41: leaving "ANC-1 completed" clears its conditional date + conditions. */
  fun setAncStatus(code: Int) = updateHealthHistory {
    if (code == HealthHistoryState.ANC1_COMPLETED) {
      it.copy(ancStatus = code)
    } else {
      it.copy(ancStatus = code, anc1Date = null, ancConditions = emptySet())
    }
  }

  fun setAnc1Date(date: LocalDate) = updateHealthHistory { it.copy(anc1Date = date) }

  fun toggleAncCondition(code: Int) = updateHealthHistory {
    it.copy(ancConditions = toggleMulti(it.ancConditions, code, ANC_EXCLUSIVE))
  }

  // Q44 — Td doses. "None" is exclusive against the three dated doses.
  fun setTdNone(checked: Boolean) = updateHealthHistory {
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

  fun setTd1(checked: Boolean) = updateHealthHistory {
    it.copy(tdNone = false, td1 = checked, td1Date = if (checked) it.td1Date else null)
  }

  fun setTd1Date(date: LocalDate) = updateHealthHistory { it.copy(td1Date = date) }

  fun setTd2(checked: Boolean) = updateHealthHistory {
    it.copy(tdNone = false, td2 = checked, td2Date = if (checked) it.td2Date else null)
  }

  fun setTd2Date(date: LocalDate) = updateHealthHistory { it.copy(td2Date = date) }

  fun setTdBooster(checked: Boolean) = updateHealthHistory {
    it.copy(
      tdNone = false,
      tdBooster = checked,
      tdBoosterDate = if (checked) it.tdBoosterDate else null,
    )
  }

  fun setTdBoosterDate(date: LocalDate) = updateHealthHistory { it.copy(tdBoosterDate = date) }

  // Q45–50 — counts. Changing Gravida to 1 clears the last-pregnancy block.
  fun setGravida(value: String) = updateHealthHistory {
    val next = it.copy(gravida = value)
    if ((value.toIntOrNull() ?: 0) > 1) next else next.clearLastPregnancy()
  }

  fun setPara(value: String) = updateHealthHistory { it.copy(para = value) }

  fun setLivingChildren(value: String) = updateHealthHistory { it.copy(livingChildren = value) }

  fun setAbortions(value: String) = updateHealthHistory { it.copy(abortions = value) }

  fun setStillBirths(value: String) = updateHealthHistory { it.copy(stillBirths = value) }

  fun setDeadChildren(value: String) = updateHealthHistory { it.copy(deadChildren = value) }

  /** Not in the Excel spec — feeds `/beneficiaries` `motherDetails.heightCm`/`weightKg` only. */
  fun setHeightCm(value: String) = updateHealthHistory { it.copy(heightCm = value) }

  fun setWeightKg(value: String) = updateHealthHistory { it.copy(weightKg = value) }

  // Q51–57 — last pregnancy.
  fun setLastPregnancyWhen(code: Int) = updateHealthHistory { it.copy(lastPregnancyWhen = code) }

  fun toggleDeliveryComplication(code: Int) = updateHealthHistory {
    it.copy(deliveryComplications = toggleMulti(it.deliveryComplications, code, COMPLICATIONS_EXCLUSIVE))
  }

  fun setLastDeliveryDuration(code: Int) =
    updateHealthHistory { it.copy(lastDeliveryDuration = code) }

  fun setLastDeliveryType(code: Int) = updateHealthHistory { it.copy(lastDeliveryType = code) }

  fun setLastDeliveryPlace(code: Int) = updateHealthHistory { it.copy(lastDeliveryPlace = code) }

  /** Q56 Still birth → clear the conditional birth weight (Q57). */
  fun setLastDeliveryOutcome(code: Int) = updateHealthHistory {
    it.copy(
      lastDeliveryOutcome = code,
      birthWeight = if (code == HealthHistoryState.DELIVERY_OUTCOME_LIVE) it.birthWeight else null,
    )
  }

  fun setBirthWeight(code: Int) = updateHealthHistory { it.copy(birthWeight = code) }

  // Q58–65 — self & family medical.
  fun toggleSelfCondition(code: Int) = updateHealthHistory {
    it.copy(selfConditions = toggleMulti(it.selfConditions, code, SELF_CONDITION_EXCLUSIVE))
  }

  fun toggleLongTermMed(code: Int) = updateHealthHistory {
    it.copy(longTermMeds = toggleMulti(it.longTermMeds, code, MEDS_EXCLUSIVE))
  }

  fun setSickleCell(code: Int) = updateHealthHistory { it.copy(sickleCell = code) }

  fun toggleSubstanceUse(code: Int) = updateHealthHistory {
    it.copy(substanceUse = toggleMulti(it.substanceUse, code, SUBSTANCE_EXCLUSIVE))
  }

  /** Q62 No → clear the conditional family conditions (Q63). */
  fun setFamilyHistory(value: Boolean) = updateHealthHistory {
    it.copy(familyHistory = value, familyConditions = if (value) it.familyConditions else emptySet())
  }

  fun toggleFamilyCondition(code: Int) = updateHealthHistory {
    it.copy(familyConditions = toggleMulti(it.familyConditions, code, emptySet()))
  }

  fun setMalnutrition(code: Int) = updateHealthHistory { it.copy(malnutrition = code) }

  fun setRemarks(value: String) = updateHealthHistory { it.copy(remarks = value) }

  /** Next from Health History: blocked with the banner until valid (HH-23/25). */
  fun goToSummary() {
    if (_uiState.value.currentStep != EnrollmentStep.HEALTH_HISTORY) return
    if (_uiState.value.healthHistory.isComplete) {
      updateHealthHistory { it.copy(showValidationBanner = false) }
      moveTo(EnrollmentStep.SUMMARY)
    } else {
      updateHealthHistory { it.copy(showValidationBanner = true) }
      bumpValidationScrollTrigger()
    }
  }

  // --- Stepper navigation ---------------------------------------------------

  /** Tab navigation: only steps already reached may be revisited. */
  fun goToStep(step: EnrollmentStep) {
    if (step == EnrollmentStep.ENTRY || step == EnrollmentStep.COMPLETE) return
    if (step.ordinal > _uiState.value.furthestStep.ordinal) return
    _uiState.update { it.copy(currentStep = step) }
  }

  /** In-flow back: one step backwards, draft retained. */
  fun goBack() {
    val current = _uiState.value.currentStep
    if (current == EnrollmentStep.ENTRY || current == EnrollmentStep.COMPLETE) return
    val previous = EnrollmentStep.entries[current.ordinal - 1]
    _uiState.update { it.copy(currentStep = previous) }
  }

  /**
   * CR-015d: maps the draft to an [org.armman.sakhi.data.enrollment.EnrollmentRecord],
   * saves it, then completes. Failure keeps the user on Summary with a
   * retryable error (SM-9); an in-flight save blocks re-entry (SM-10).
   */
  fun submit() {
    val state = _uiState.value
    if (state.currentStep != EnrollmentStep.SUMMARY || !state.canSubmit) return
    _uiState.update { it.copy(isSubmitting = true, submitFailed = false, submitErrorMessage = null) }
    viewModelScope.launch {
      // submitEnrollment (not saveEnrollment) — while online, this waits for the REAL backend
      // result before we decide whether to navigate, instead of advancing on a local save that
      // says nothing about whether the backend will accept the record.
      when (val result = enrollmentRepository.submitEnrollment(_uiState.value.toEnrollmentRecord())) {
        is EnrollmentSubmitResult.Synced, is EnrollmentSubmitResult.QueuedOffline -> {
          _events.trySend(EnrollmentEvent.DataSaved)
          _uiState.update { it.copy(isSubmitting = false) }
          moveTo(EnrollmentStep.COMPLETE)
        }

        is EnrollmentSubmitResult.DuplicateConflict -> {
          _uiState.update {
            it.copy(isSubmitting = false, submitFailed = true, submitErrorMessage = result.message)
          }
        }

        is EnrollmentSubmitResult.Failed -> {
          _uiState.update {
            it.copy(isSubmitting = false, submitFailed = true, submitErrorMessage = result.message)
          }
        }
      }
    }
  }

  fun onStartVisitForm() {
    _events.trySend(EnrollmentEvent.ComingSoon)
  }

  /** Exit confirmed by the user: drop the in-memory draft entirely. */
  fun exitEnrollment() {
    _uiState.value = EnrollmentUiState()
    initializePersonalInfo()
  }

  // --- Internal -------------------------------------------------------------

  private fun moveTo(step: EnrollmentStep) {
    _uiState.update {
      it.copy(
        currentStep = step,
        furthestStep = if (step.ordinal > it.furthestStep.ordinal) step else it.furthestStep,
      )
    }
  }

  /** Fires even if the banner was already showing (repeated taps while still invalid), so the
   * screen re-scrolls to it every time rather than only on the first blocked attempt. */
  private fun bumpValidationScrollTrigger() {
    _uiState.update { it.copy(validationScrollTrigger = it.validationScrollTrigger + 1) }
  }

  private inline fun updateConsent(crossinline transform: (ConsentState) -> ConsentState) {
    _uiState.update { it.copy(consent = transform(it.consent)) }
  }

  private inline fun updatePersonalInfo(
    crossinline transform: (PersonalInfoState) -> PersonalInfoState,
  ) {
    _uiState.update { state ->
      var next = transform(state.personalInfo)
      // PI-24: the banner clears as soon as every mandatory field is valid.
      if (next.showValidationBanner && next.isComplete) {
        next = next.copy(showValidationBanner = false)
      }
      state.copy(personalInfo = next)
    }
  }

  private inline fun updateHealthHistory(
    crossinline transform: (HealthHistoryState) -> HealthHistoryState,
  ) {
    _uiState.update { state ->
      var next = transform(state.healthHistory)
      // HH-24: the banner clears as soon as every mandatory field is valid.
      if (next.showValidationBanner && next.isComplete) {
        next = next.copy(showValidationBanner = false)
      }
      state.copy(healthHistory = next)
    }
  }

  /**
   * Multi-select toggle with mutual exclusion. Selecting an [exclusive] code
   * clears everything else; selecting a normal code clears any exclusive codes
   * (Excel: "No known condition" / "Don't know" vs specific conditions).
   */
  private fun toggleMulti(current: Set<Int>, code: Int, exclusive: Set<Int>): Set<Int> = when {
    code in current -> current - code
    code in exclusive -> setOf(code)
    else -> (current + code) - exclusive
  }

  private fun HealthHistoryState.clearLastPregnancy(): HealthHistoryState = copy(
    lastPregnancyWhen = null,
    deliveryComplications = emptySet(),
    lastDeliveryDuration = null,
    lastDeliveryType = null,
    lastDeliveryPlace = null,
    lastDeliveryOutcome = null,
    birthWeight = null,
  )

  private companion object {
    // Mutually-exclusive option codes per multi-select (1-based; see arrays.xml).
    val ANC_EXCLUSIVE = setOf(1, 16) // Q43: No known condition / Don't know
    val SELF_CONDITION_EXCLUSIVE = setOf(1, 17) // Q58: No known condition / Don't know
    val SUBSTANCE_EXCLUSIVE = setOf(1, 7) // Q61: No / Don't know
    val MEDS_EXCLUSIVE = setOf(1) // Q59: Not taking any long-term medication
    val COMPLICATIONS_EXCLUSIVE = setOf(4) // Q52: No complications
  }
}
