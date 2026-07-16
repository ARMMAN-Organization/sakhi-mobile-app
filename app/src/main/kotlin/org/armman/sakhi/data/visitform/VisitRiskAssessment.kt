package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.ui.visitform.VisitDataState
import kotlin.math.abs

/**
 * One row of the Summary tab's Tests/Symptoms review (CR-016c). [labelRes] is the
 * field name string resource; [value]/[referenceRange] are plain display strings
 * (numeric, not prose — not translation-sensitive beyond digits/units already
 * baked into the label). [editTarget] tells the Summary UI which Visit Data
 * sub-tab "Edit" should jump back to.
 */
data class VisitRiskFinding(
  val labelRes: Int,
  val value: String,
  val referenceRange: String,
  val riskLevel: RiskLevel,
  val editTarget: VisitDataSubTab,
)

/**
 * Risk-tier assessment for the Summary tab (CR-016c), sourced verbatim from the
 * Excel `ANC visit form` tab, column I ("Risk condition calculations") — exact
 * thresholds cited in docs/test-cases/visit-form.md (VS-1..12). Only BP and
 * Hemoglobin have graded Mild/Moderate/Severe bands in that column; every other
 * vital is a single "at risk" threshold with no intermediate tier — confirmed
 * against the spec, not an implementation gap.
 *
 * These are pure functions over already-valid numeric input; callers (typically
 * [buildTestsFindings]) are responsible for parsing/blank-checking
 * [VisitDataState]'s raw string fields and honouring its conditional-visibility
 * rules (e.g. fetal/fundal fields only exist past 20 weeks) before calling in.
 */
object VisitRiskAssessment {

  /**
   * Severity order used both for [overall] (worst-of aggregation) and for
   * sorting the Summary tab's finding cards — highest risk first, per the
   * design/product convention: any list of risk-tagged items sorts High →
   * Moderate → Mild → Low, not by field/entry order.
   */
  private val RISK_SEVERITY_ORDER = listOf(RiskLevel.HIGH, RiskLevel.MODERATE, RiskLevel.MILD, RiskLevel.LOW)

  /** Q42 codes 2 (SCT carrier) / 3 (SCD) — see `visit_form_sickle_cell_options`. */
  private val SICKLE_CELL_POSITIVE_CODES = setOf(2, 3)

  /** Q18/Q19/Q20 shared option array — "Normal" is the 3rd/last item. */
  private const val PALM_NAILS_NORMAL = 3
  private const val SCLERA_NORMAL = 3
  private const val SKIN_NORMAL = 3

  /** Q22 dehydration — "No" is the 3rd/last item. */
  private const val DEHYDRATION_NONE = 3

  /** Q31 fetal movements — "Absent" is concerning, "Present" (code 2) is reassuring. */
  private const val FETAL_MOVEMENTS_ABSENT = 1

  // --- Thresholds — Excel `ANC visit form`, column I, verbatim -----------------

  const val BP_MILD_SYSTOLIC = 135.0
  const val BP_MILD_DIASTOLIC = 85.0
  const val BP_MODERATE_SYSTOLIC = 140.0
  const val BP_MODERATE_DIASTOLIC = 90.0
  const val BP_SEVERE_SYSTOLIC = 160.0
  const val BP_SEVERE_DIASTOLIC = 110.0
  const val BP_HYPOTENSION_SYSTOLIC = 90.0

  const val HB_SEVERE_MAX = 7.0
  /** 7 – 9.9 g/dl (exclusive upper bound at 10.0), or sickle cell positive. */
  const val HB_MODERATE_MAX = 10.0
  /** 10 – 10.9 g/dl (exclusive upper bound at 11.0). */
  const val HB_MILD_MAX = 11.0

  const val GLUCOSE_GDM_THRESHOLD = 140.0
  const val FEVER_THRESHOLD = 99.0
  const val HYPOTHERMIA_THRESHOLD = 96.0
  const val MUAC_RISK_THRESHOLD = 23.0
  const val HEIGHT_RISK_THRESHOLD = 145.0
  const val BMI_UNDERWEIGHT = 18.5
  const val BMI_OVERWEIGHT = 35.0
  const val FHR_MIN = 120.0
  const val FHR_MAX = 160.0
  const val FUNDAL_HEIGHT_TOLERANCE = 2.0

  // --- Per-vital assessment -----------------------------------------------------

  fun bloodPressure(systolic: Double, diastolic: Double): RiskLevel = when {
    systolic < BP_HYPOTENSION_SYSTOLIC || systolic >= BP_SEVERE_SYSTOLIC || diastolic >= BP_SEVERE_DIASTOLIC ->
      RiskLevel.HIGH
    systolic >= BP_MODERATE_SYSTOLIC || diastolic >= BP_MODERATE_DIASTOLIC -> RiskLevel.MODERATE
    systolic >= BP_MILD_SYSTOLIC || diastolic >= BP_MILD_DIASTOLIC -> RiskLevel.MILD
    else -> RiskLevel.LOW
  }

  /**
   * ASSUMPTION (flagged, not silently decided — the Excel phrasing "Moderate:
   * 7–9.9 g/dl (or sickle cell +ve)" is ambiguous): a sickle-cell-positive
   * status elevates to at least Moderate regardless of today's Hb reading, since
   * SCT/SCD carries a standing anaemia risk beyond any single measurement. A
   * severe reading (<7) still wins over sickle-cell-positive.
   */
  fun hemoglobin(hb: Double, sickleCellCode: Int?): RiskLevel = when {
    hb < HB_SEVERE_MAX -> RiskLevel.HIGH
    hb < HB_MODERATE_MAX || sickleCellCode in SICKLE_CELL_POSITIVE_CODES -> RiskLevel.MODERATE
    hb < HB_MILD_MAX -> RiskLevel.MILD
    else -> RiskLevel.LOW
  }

  fun bloodGlucose(value: Double): RiskLevel =
    if (value >= GLUCOSE_GDM_THRESHOLD) RiskLevel.HIGH else RiskLevel.LOW

  fun temperature(value: Double): RiskLevel =
    if (value >= FEVER_THRESHOLD || value < HYPOTHERMIA_THRESHOLD) RiskLevel.HIGH else RiskLevel.LOW

  fun muac(value: Double): RiskLevel = if (value < MUAC_RISK_THRESHOLD) RiskLevel.HIGH else RiskLevel.LOW

  fun height(value: Double): RiskLevel = if (value < HEIGHT_RISK_THRESHOLD) RiskLevel.HIGH else RiskLevel.LOW

  fun bmi(value: Double): RiskLevel =
    if (value < BMI_UNDERWEIGHT || value >= BMI_OVERWEIGHT) RiskLevel.HIGH else RiskLevel.LOW

  fun fetalHeartRate(value: Double): RiskLevel =
    if (value < FHR_MIN || value > FHR_MAX) RiskLevel.HIGH else RiskLevel.LOW

  fun fundalHeight(value: Double, gestationalAgeWeeks: Int): RiskLevel =
    if (abs(value - gestationalAgeWeeks) > FUNDAL_HEIGHT_TOLERANCE) RiskLevel.HIGH else RiskLevel.LOW

  /**
   * Symptoms (Q17–22) have no Excel numeric bands — this is a plain
   * "flagged vs not" read, not a clinical severity claim: LOW when the
   * none-of-the-above/normal option is selected, MODERATE otherwise.
   */
  fun symptomFlag(selected: Boolean): RiskLevel = if (selected) RiskLevel.MODERATE else RiskLevel.LOW

  /** Worst-of aggregation — HIGH > MODERATE > MILD > LOW; empty list is LOW. */
  fun overall(levels: List<RiskLevel>): RiskLevel =
    RISK_SEVERITY_ORDER.firstOrNull { it in levels } ?: RiskLevel.LOW

  /** High → Moderate → Mild → Low; stable, so same-risk findings keep their field order. */
  private fun List<VisitRiskFinding>.sortedByRisk(): List<VisitRiskFinding> =
    sortedBy { RISK_SEVERITY_ORDER.indexOf(it.riskLevel) }

  // --- Findings builders (parses VisitDataState's raw strings, honours visibility) --

  /** Q12–16, Q23–32 — Tests card rows, skipping any blank/inapplicable field, sorted worst-first. */
  fun buildTestsFindings(state: VisitDataState): List<VisitRiskFinding> = buildList {
    val weight = state.weightKg.toDoubleOrNull()
    val height = (state.heightLockedCm?.toDouble()) ?: state.heightCm.toDoubleOrNull()
    val systolic = state.bpSystolic.toDoubleOrNull()
    val diastolic = state.bpDiastolic.toDoubleOrNull()
    val hb = state.hemoglobin.toDoubleOrNull()
    val glucose = state.bloodGlucose.toDoubleOrNull()
    val temp = state.temperatureF.toDoubleOrNull()
    val muacValue = state.muacCm.toDoubleOrNull()

    if (systolic != null && diastolic != null) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vs_bp,
          value = "${systolic.toInt()}/${diastolic.toInt()} mmHg",
          referenceRange = "<135/85 mmHg",
          riskLevel = bloodPressure(systolic, diastolic),
          editTarget = VisitDataSubTab.TESTS,
        ),
      )
    }
    if (hb != null) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_hemoglobin,
          value = "$hb g/dl",
          referenceRange = "≥11 g/dl",
          riskLevel = hemoglobin(hb, state.sickleCell),
          editTarget = VisitDataSubTab.TESTS,
        ),
      )
    }
    if (glucose != null) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_blood_glucose,
          value = "$glucose mg/dl",
          referenceRange = "<140 mg/dl",
          riskLevel = bloodGlucose(glucose),
          editTarget = VisitDataSubTab.TESTS,
        ),
      )
    }
    if (temp != null) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_temperature,
          value = "$temp°F",
          referenceRange = "96–99°F",
          riskLevel = temperature(temp),
          editTarget = VisitDataSubTab.TESTS,
        ),
      )
    }
    if (muacValue != null) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_muac,
          value = "$muacValue cm",
          referenceRange = "≥23 cm",
          riskLevel = muac(muacValue),
          editTarget = VisitDataSubTab.TESTS,
        ),
      )
    }
    // Height/BMI are only meaningful when captured this visit (first visit only) —
    // on later visits the locked value is a carried-forward baseline, not a new
    // reading to flag as risk here.
    if (state.isHeightEditable && height != null) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_height,
          value = "$height cm",
          referenceRange = "≥145 cm",
          riskLevel = height(height),
          editTarget = VisitDataSubTab.TESTS,
        ),
      )
      val bmiValue = state.bmi
      if (bmiValue != null) {
        add(
          VisitRiskFinding(
            labelRes = org.armman.sakhi.R.string.visit_form_vd_bmi,
            value = "%.1f".format(bmiValue),
            referenceRange = "18.5–34.9",
            riskLevel = bmi(bmiValue),
            editTarget = VisitDataSubTab.TESTS,
          ),
        )
      }
    }
    if (state.showFetalAndFundalFields) {
      state.fetalMovements?.let {
        add(
          VisitRiskFinding(
            labelRes = org.armman.sakhi.R.string.visit_form_vd_fetal_movements,
            value = "",
            referenceRange = "",
            riskLevel = symptomFlag(it == FETAL_MOVEMENTS_ABSENT),
            editTarget = VisitDataSubTab.TESTS,
          ),
        )
      }
      val fhr = state.fetalHeartRate.toDoubleOrNull()
      val fundal = state.fundalHeightCm.toDoubleOrNull()
      val ga = state.gestationalAgeWeeks
      if (fhr != null) {
        add(
          VisitRiskFinding(
            labelRes = org.armman.sakhi.R.string.visit_form_vd_fetal_heart_rate,
            value = "$fhr bpm",
            referenceRange = "120–160 bpm",
            riskLevel = fetalHeartRate(fhr),
            editTarget = VisitDataSubTab.TESTS,
          ),
        )
      }
      if (fundal != null && ga != null) {
        add(
          VisitRiskFinding(
            labelRes = org.armman.sakhi.R.string.visit_form_vd_fundal_height,
            value = "$fundal cm",
            referenceRange = "GA ±2cm",
            riskLevel = fundalHeight(fundal, ga),
            editTarget = VisitDataSubTab.TESTS,
          ),
        )
      }
    }
  }.sortedByRisk()

  /** Q17–22 — Symptoms card rows; only shows fields the beneficiary actually answered, sorted worst-first. */
  fun buildSymptomsFindings(state: VisitDataState): List<VisitRiskFinding> = buildList {
    add(
      VisitRiskFinding(
        labelRes = org.armman.sakhi.R.string.visit_form_vd_danger_signs,
        value = "${state.dangerSigns.size}",
        referenceRange = "",
        riskLevel = symptomFlag(state.dangerSigns.any { it != VisitDataState.DANGER_SIGN_NONE }),
        editTarget = VisitDataSubTab.SYMPTOMS,
      ),
    )
    state.palmNails?.let {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_palm_nails,
          value = "",
          referenceRange = "",
          riskLevel = symptomFlag(it != PALM_NAILS_NORMAL),
          editTarget = VisitDataSubTab.SYMPTOMS,
        ),
      )
    }
    state.sclera?.let {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_sclera,
          value = "",
          referenceRange = "",
          riskLevel = symptomFlag(it != SCLERA_NORMAL),
          editTarget = VisitDataSubTab.SYMPTOMS,
        ),
      )
    }
    state.skin?.let {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_skin,
          value = "",
          referenceRange = "",
          riskLevel = symptomFlag(it != SKIN_NORMAL),
          editTarget = VisitDataSubTab.SYMPTOMS,
        ),
      )
    }
    if (state.swelling.isNotEmpty()) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_swelling,
          value = "",
          referenceRange = "",
          riskLevel = symptomFlag(state.swelling.any { it != VisitDataState.SWELLING_NONE }),
          editTarget = VisitDataSubTab.SYMPTOMS,
        ),
      )
    }
    if (state.dehydration.isNotEmpty()) {
      add(
        VisitRiskFinding(
          labelRes = org.armman.sakhi.R.string.visit_form_vd_dehydration,
          value = "",
          referenceRange = "",
          riskLevel = symptomFlag(state.dehydration.any { it != DEHYDRATION_NONE }),
          editTarget = VisitDataSubTab.SYMPTOMS,
        ),
      )
    }
  }.sortedByRisk()
}
