package org.armman.sakhi.ui.visitform

import org.armman.sakhi.data.visitform.VisitContext
import java.time.LocalDate

/** Field-level validation outcomes for the Visit Data step (Q1–56). */
enum class VisitDataFieldError {
  /** Mandatory field left empty (shown after a blocked Next attempt). */
  REQUIRED,

  /** Numeric value outside its Excel-defined range (FR-S-4.7 Category 2). */
  RANGE,

  /** Date is in the future where the field forbids it. */
  DATE_FUTURE,

  /** Date is not after the required anchor (e.g. USG date must be > LMP). */
  DATE_TOO_EARLY,

  /** Hb differs from the last visit's by more than ±2 g/dl — confirm or redo. */
  HB_CONFIRM_NEEDED,
}

/**
 * Visit Data step (Excel `ANC visit form` tab, Q1–56), split across the
 * Tests / Symptoms / History sub-tabs per the CR-016 grouping decision. Risk
 * severity / Summary banner content is CR-016c — this state only captures and
 * validates raw values, plus the FR-S-4.4 critical-pathway checks the
 * ViewModel evaluates after each relevant update.
 *
 * Dropdown/radio answers are 1-based option codes; multi-selects are a [Set]
 * of 1-based codes — same convention as Enrollment's `HealthHistoryState`.
 */
data class VisitDataState(
  // --- Context (loaded once, not directly user-editable) --------------------
  val visitTypeLabel: String = "",
  val originalLmp: LocalDate? = null,
  val heightLockedCm: Int? = null,
  val previousHb: Double? = null,

  // Q1–4 — Visit tracking.
  val visitDate: LocalDate? = null,
  val metBeneficiary: Boolean? = null,
  val notMetReason: Int? = null,

  // Q5–11 — Pregnancy dating validation.
  val rchNumber: String = "",
  val lmp: LocalDate? = null,
  val hasSonographyReport: Boolean? = null,
  val sonographyPhotoUri: String? = null,

  // Q12–16, Q23–32 — Tests sub-tab: anthropometry & diagnostics.
  val heightCm: String = "",
  val weightKg: String = "",
  val muacCm: String = "",
  val bpSystolic: String = "",
  val bpDiastolic: String = "",
  val temperatureF: String = "",
  val hemoglobin: String = "",
  val hbConfirmed: Boolean = false,
  val lastMealHours: String = "",
  val bloodGlucose: String = "",
  val urineTest: Set<Int> = emptySet(),
  val fetalMovements: Int? = null,
  val fetalHeartRate: String = "",
  val fundalHeightCm: String = "",

  // Q17–22 — Symptoms sub-tab.
  val dangerSigns: Set<Int> = emptySet(),
  val palmNails: Int? = null,
  val sclera: Int? = null,
  val skin: Int? = null,
  val swelling: Set<Int> = emptySet(),
  val dehydration: Set<Int> = emptySet(),

  // Q33 — Td doses (History sub-tab).
  val tdNone: Boolean = false,
  val td1: Boolean = false,
  val td1Date: LocalDate? = null,
  val td2: Boolean = false,
  val td2Date: LocalDate? = null,
  val tdBooster: Boolean = false,
  val tdBoosterDate: LocalDate? = null,

  // Q34–39 — Supplements & adherence (History sub-tab).
  val foodConsumed24h: Set<Int> = emptySet(),
  val foodAvoided: Set<Int> = emptySet(),
  val takingIfa: Boolean? = null,
  val ifaTabletsConsumed: String = "",
  val ifaNonConsumptionReasons: Set<Int> = emptySet(),
  val calciumTaken: Boolean? = null,

  // Q40–55 — Birth preparedness & mental health (History sub-tab).
  val visitedFacilitySinceLastVisit: Boolean? = null,
  val lastAncVisitDate: LocalDate? = null,
  val advisedDeliveryPlace: Int? = null,
  val sickleCell: Int? = null,
  val familyPlanningMethods: Set<Int> = emptySet(),
  val feelingStressed: Boolean? = null,
  val adequateFamilySupport: Boolean? = null,
  val planningMigration: Boolean? = null,
  val usgDone: Boolean? = null,
  val usgDate: LocalDate? = null,
  val usgType: Int? = null,
  val usgFinding: Int? = null,
  val transportContactShared: Boolean? = null,
  val fundsArranged: Boolean? = null,
  val birthCompanionIdentified: Boolean? = null,
  val remarks: String = "",

  // Q56 — Counselling checklist (History sub-tab; advisory, never blocks completion).
  val counsellingTopics: Set<Int> = emptySet(),

  /** True after a blocked Next attempt — drives the banner + empty-field errors. */
  val showValidationBanner: Boolean = false,
) {

  companion object {
    /** Seeds a fresh [VisitDataState] from the beneficiary's carried-forward context. */
    fun from(context: VisitContext): VisitDataState = VisitDataState(
      visitTypeLabel = context.visitTypeLabel,
      originalLmp = context.lmp,
      heightLockedCm = context.heightCm,
      previousHb = context.previousHb,
      visitDate = LocalDate.now(),
      rchNumber = context.rchNumber,
      lmp = context.lmp,
      heightCm = context.heightCm?.toString() ?: "",
      advisedDeliveryPlace = context.advisedDeliveryPlace,
      sickleCell = context.sickleCell,
    )

    const val GESTATIONAL_AGE_FETAL_FIELDS_WEEKS = 20

    const val HEIGHT_MIN = 120
    const val HEIGHT_MAX = 190
    const val MUAC_MIN = 10
    const val MUAC_MAX = 40
    const val BP_SYSTOLIC_MIN = 70
    const val BP_SYSTOLIC_MAX = 300
    const val BP_DIASTOLIC_MIN = 40
    const val BP_DIASTOLIC_MAX = 130
    const val TEMPERATURE_MIN = 94.0
    const val TEMPERATURE_MAX = 105.0
    const val HB_MIN = 1.0
    const val HB_MAX = 18.0
    const val HB_STALE_DELTA = 2.0
    const val GLUCOSE_MIN = 40
    const val GLUCOSE_MAX = 400
    const val FUNDAL_HEIGHT_MIN = 10
    const val FUNDAL_HEIGHT_MAX = 50
    const val FETAL_HR_MIN = 60
    const val FETAL_HR_MAX = 220
    const val IFA_TABLETS_MIN = 0
    const val IFA_TABLETS_MAX = 35
    const val WEIGHT_MIN = 25.0
    const val WEIGHT_MAX = 100.0

    /** Q23 severe-hypertension threshold for the FR-S-4.4 critical pathway. */
    const val BP_SYSTOLIC_CRITICAL = 160
    const val BP_DIASTOLIC_CRITICAL = 110

    /** Q26 severe-anaemia threshold. */
    const val HB_CRITICAL = 7.0

    /** Q28 hypoglycaemia threshold. */
    const val GLUCOSE_CRITICAL_LOW = 70

    /** Q17 option 13: "No abnormal signs and symptoms" (mutually exclusive with the rest). */
    const val DANGER_SIGN_NONE = 13

    /** Q17 codes 7 (Dizziness) / 8 (Breathlessness) — the symptoms the Excel pairs with a
     * severe BP/Hb reading for the critical pathway (Q23/Q26 critical-pathway notes). */
    val DANGER_SIGNS_SEVERE_ACCOMPANYING = setOf(7, 8)

    /** Q17 code 7 (Dizziness) — closest listed proxy for "cold sweat" (Q28's critical note). */
    val DANGER_SIGNS_COLD_SWEAT = setOf(7)

    /** Q21 codes indicating swelling accompanying severe BP (hand/face). */
    const val SWELLING_NONE = 1

    /** Q36/Q39 Yes/No are stored as booleans directly (no dedicated option array). */

    /** Q29 option: "Normal" — exclusive against Infection/Sugar/Protein. */
    const val URINE_NORMAL = 1
  }

  // --- Derived ---------------------------------------------------------------

  /** Q10: current gestational age in weeks, floor((today − LMP)/7). */
  val gestationalAgeWeeks: Int?
    get() = lmp?.let { java.time.temporal.ChronoUnit.DAYS.between(it, LocalDate.now()) / 7 }?.toInt()

  /** Q11: EDD = LMP + 280 days. */
  val edd: LocalDate?
    get() = lmp?.plusDays(280)

  /** Q14: BMI = weight / height(m)². */
  val bmi: Double?
    get() {
      val w = weightKg.toDoubleOrNull() ?: return null
      val h = (heightLockedCm?.toString() ?: heightCm).toDoubleOrNull() ?: return null
      if (h <= 0) return null
      val meters = h / 100.0
      return w / (meters * meters)
    }

  /** Q6/Q8: LMP was edited away from the value on file (gates Q9 sonography photo). */
  val isLmpEdited: Boolean get() = originalLmp != null && lmp != originalLmp

  // --- Conditional visibility -----------------------------------------------

  val showNotMetReason: Boolean get() = metBeneficiary == false
  val showSonographyUpload: Boolean get() = isLmpEdited && hasSonographyReport == true
  val isHeightEditable: Boolean get() = heightLockedCm == null
  val showFetalAndFundalFields: Boolean
    get() = (gestationalAgeWeeks ?: 0) >= GESTATIONAL_AGE_FETAL_FIELDS_WEEKS
  val showTreatmentIfaCount: Boolean get() = takingIfa == true
  val showIfaNonConsumptionReasons: Boolean get() = takingIfa == false
  val showLastAncVisitDate: Boolean get() = visitedFacilitySinceLastVisit == true
  val showUsgDetails: Boolean get() = usgDone == true

  // --- Field validation --------------------------------------------------------

  private val requiredOrNull: VisitDataFieldError?
    get() = if (showValidationBanner) VisitDataFieldError.REQUIRED else null

  private fun blankOrRange(value: String, min: Double, max: Double, required: Boolean): VisitDataFieldError? {
    if (value.isBlank()) return if (required) requiredOrNull else null
    val n = value.toDoubleOrNull() ?: return VisitDataFieldError.RANGE
    return if (n < min || n > max) VisitDataFieldError.RANGE else null
  }

  val visitDateError: VisitDataFieldError?
    get() {
      val date = visitDate ?: return requiredOrNull
      return if (date.isAfter(LocalDate.now())) VisitDataFieldError.DATE_FUTURE else null
    }

  val heightError: VisitDataFieldError?
    get() = if (!isHeightEditable) null else blankOrRange(heightCm, HEIGHT_MIN.toDouble(), HEIGHT_MAX.toDouble(), required = true)

  val weightError: VisitDataFieldError?
    get() = blankOrRange(weightKg, WEIGHT_MIN, WEIGHT_MAX, required = true)

  val muacError: VisitDataFieldError?
    get() = blankOrRange(muacCm, MUAC_MIN.toDouble(), MUAC_MAX.toDouble(), required = true)

  val bpSystolicError: VisitDataFieldError?
    get() = blankOrRange(bpSystolic, BP_SYSTOLIC_MIN.toDouble(), BP_SYSTOLIC_MAX.toDouble(), required = true)

  val bpDiastolicError: VisitDataFieldError?
    get() = blankOrRange(bpDiastolic, BP_DIASTOLIC_MIN.toDouble(), BP_DIASTOLIC_MAX.toDouble(), required = true)

  val temperatureError: VisitDataFieldError?
    get() = blankOrRange(temperatureF, TEMPERATURE_MIN, TEMPERATURE_MAX, required = true)

  /** Q26: range check, then the ±2 g/dl stale-value confirmation (not a hard block). */
  val hemoglobinError: VisitDataFieldError?
    get() {
      blankOrRange(hemoglobin, HB_MIN, HB_MAX, required = true)?.let { return it }
      val value = hemoglobin.toDoubleOrNull() ?: return null
      val previous = previousHb ?: return null
      val stale = kotlin.math.abs(value - previous) > HB_STALE_DELTA
      return if (stale && !hbConfirmed) VisitDataFieldError.HB_CONFIRM_NEEDED else null
    }

  val bloodGlucoseError: VisitDataFieldError?
    get() = blankOrRange(bloodGlucose, GLUCOSE_MIN.toDouble(), GLUCOSE_MAX.toDouble(), required = true)

  val fetalHeartRateError: VisitDataFieldError?
    get() = if (!showFetalAndFundalFields) null
      else blankOrRange(fetalHeartRate, FETAL_HR_MIN.toDouble(), FETAL_HR_MAX.toDouble(), required = true)

  val fundalHeightError: VisitDataFieldError?
    get() = if (!showFetalAndFundalFields) null
      else blankOrRange(fundalHeightCm, FUNDAL_HEIGHT_MIN.toDouble(), FUNDAL_HEIGHT_MAX.toDouble(), required = true)

  val ifaTabletsError: VisitDataFieldError?
    get() = if (!showTreatmentIfaCount) null
      else blankOrRange(ifaTabletsConsumed, IFA_TABLETS_MIN.toDouble(), IFA_TABLETS_MAX.toDouble(), required = true)

  /** Q41: ANC visit date must not be in the future. */
  val lastAncVisitDateError: VisitDataFieldError?
    get() {
      if (!showLastAncVisitDate) return null
      val date = lastAncVisitDate ?: return requiredOrNull
      return if (date.isAfter(LocalDate.now())) VisitDataFieldError.DATE_FUTURE else null
    }

  /** Q49: USG date must be after LMP and not in the future. */
  val usgDateError: VisitDataFieldError?
    get() {
      if (!showUsgDetails) return null
      val date = usgDate ?: return requiredOrNull
      if (date.isAfter(LocalDate.now())) return VisitDataFieldError.DATE_FUTURE
      val anchor = lmp ?: return null
      return if (!date.isAfter(anchor)) VisitDataFieldError.DATE_TOO_EARLY else null
    }

  /** Q33: Td dose date ordering + no future dates — identical rule to Enrollment. */
  val tdDateError: VisitDataFieldError?
    get() {
      val dates = listOfNotNull(
        td1Date.takeIf { td1 },
        td2Date.takeIf { td2 },
        tdBoosterDate.takeIf { tdBooster },
      )
      if (dates.any { it.isAfter(LocalDate.now()) }) return VisitDataFieldError.DATE_FUTURE
      if (td1 && td2 && td1Date != null && td2Date != null && td1Date.isAfter(td2Date)) {
        return VisitDataFieldError.DATE_TOO_EARLY
      }
      if (td2 && tdBooster && td2Date != null && tdBoosterDate != null && td2Date.isAfter(tdBoosterDate)) {
        return VisitDataFieldError.DATE_TOO_EARLY
      }
      return null
    }

  // --- Critical-pathway detection (FR-S-4.4) ---------------------------------

  /** BP systolic/diastolic ≥ critical threshold plus an accompanying danger sign. */
  val hasCriticalHypertension: Boolean
    get() {
      val systolic = bpSystolic.toDoubleOrNull() ?: return false
      val diastolic = bpDiastolic.toDoubleOrNull() ?: return false
      val severe = systolic > BP_SYSTOLIC_CRITICAL || diastolic > BP_DIASTOLIC_CRITICAL
      val hasSymptom = dangerSigns.any { it in DANGER_SIGNS_SEVERE_ACCOMPANYING } ||
        swelling.any { it != SWELLING_NONE }
      return severe && hasSymptom
    }

  /** Hb < 7 plus dizziness. */
  val hasCriticalAnaemia: Boolean
    get() {
      val value = hemoglobin.toDoubleOrNull() ?: return false
      return value < HB_CRITICAL && dangerSigns.any { it in DANGER_SIGNS_SEVERE_ACCOMPANYING }
    }

  /** Blood glucose < 70 plus dizziness/cold sweat. */
  val hasCriticalHypoglycaemia: Boolean
    get() {
      val value = bloodGlucose.toDoubleOrNull() ?: return false
      return value < GLUCOSE_CRITICAL_LOW && dangerSigns.any { it in DANGER_SIGNS_COLD_SWEAT }
    }

  /** Any Q17 danger sign other than "No abnormal signs" — immediate stop-and-refer. */
  val hasAnyDangerSign: Boolean
    get() = dangerSigns.any { it != DANGER_SIGN_NONE }

  // --- Completion gate ---------------------------------------------------------
  // Split per sub-tab (Tests/Symptoms/History) so each can gate its own Next
  // button (CR-016b UX follow-up) — grouping mirrors the field-ownership
  // comments on the properties above and in the sub-tab composables.

  private val testsMissingSelections: Boolean
    get() {
      val required = mutableListOf<Boolean>()
      required += urineTest.isEmpty()
      required += (showFetalAndFundalFields && fetalMovements == null)
      return required.any { it }
    }

  private val symptomsMissingSelections: Boolean
    get() {
      val required = mutableListOf<Boolean>()
      required += dangerSigns.isEmpty()
      required += palmNails == null
      required += sclera == null
      required += skin == null
      required += swelling.isEmpty()
      required += dehydration.isEmpty()
      return required.any { it }
    }

  private val historyMissingSelections: Boolean
    get() {
      val required = mutableListOf<Boolean>()
      required += metBeneficiary == null
      required += (showNotMetReason && notMetReason == null)
      required += rchNumber.isBlank()
      required += lmp == null
      required += hasSonographyReport == null
      required += (showSonographyUpload && sonographyPhotoUri == null)
      required += tdSelectionMissing
      required += foodConsumed24h.isEmpty()
      required += foodAvoided.isEmpty()
      required += takingIfa == null
      required += (showIfaNonConsumptionReasons && ifaNonConsumptionReasons.isEmpty())
      required += calciumTaken == null
      required += visitedFacilitySinceLastVisit == null
      required += advisedDeliveryPlace == null
      required += sickleCell == null
      required += familyPlanningMethods.isEmpty()
      required += feelingStressed == null
      required += adequateFamilySupport == null
      required += planningMigration == null
      required += usgDone == null
      required += (showUsgDetails && (usgType == null || usgFinding == null))
      required += transportContactShared == null
      required += fundsArranged == null
      required += birthCompanionIdentified == null
      // Q56 counselling checklist is advisory only — never required.
      return required.any { it }
    }

  /** Q33: no Td dose ticked at all (neither "None" nor any individual dose). */
  internal val tdSelectionMissing: Boolean
    get() = !tdNone && !td1 && !td2 && !tdBooster

  /** Tests sub-tab gate (Q12–16, Q23–32) — its own Next button (Tests → Symptoms). */
  val isTestsComplete: Boolean
    get() = heightError == null && weightError == null && muacError == null &&
      bpSystolicError == null && bpDiastolicError == null && temperatureError == null &&
      hemoglobinError == null && bloodGlucoseError == null && fetalHeartRateError == null &&
      fundalHeightError == null && !testsMissingSelections

  /** Symptoms sub-tab gate (Q17–22) — its own Next button (Symptoms → History). */
  val isSymptomsComplete: Boolean
    get() = !symptomsMissingSelections

  /** History sub-tab gate (Q1–4, Q5–11, Q33–56) — its own Next button (History → Summary). */
  val isHistoryComplete: Boolean
    get() = metBeneficiary != false && // Q3=No ends the visit — handled outside the stepper (VD-2).
      visitDateError == null && ifaTabletsError == null && lastAncVisitDateError == null &&
      usgDateError == null && tdDateError == null && !historyMissingSelections

  /** Gate for advancing to Summary (VD-27/29) — every sub-tab must be complete. */
  val isComplete: Boolean
    get() = isTestsComplete && isSymptomsComplete && isHistoryComplete
}
