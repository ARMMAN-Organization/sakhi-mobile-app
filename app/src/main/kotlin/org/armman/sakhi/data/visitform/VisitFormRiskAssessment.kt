package org.armman.sakhi.data.visitform

import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.forms.FormAnswers

/**
 * One row of the Summary tab's Tests review (CR-016c, re-wired onto [FormAnswers] on
 * 2026-08-08). [labelRes] is the field name string resource; [value]/[referenceRange] are plain
 * display strings.
 */
data class VisitFormRiskFinding(
  val labelRes: Int,
  val value: String,
  val referenceRange: String,
  val riskLevel: RiskLevel,
)

/**
 * Risk-tier assessment for the Summary tab (CR-016c) — ported from the retired
 * [org.armman.sakhi.data.visitform.VisitRiskAssessment] (now in `_to_delete/`, staged for the
 * user's own removal), which sourced these thresholds verbatim from the Excel `ANC visit form`
 * tab, column I. Only the pure per-vital functions and [buildTestsFindings] are ported for this
 * pass (bharath, 2026-08-08) — BP, Hemoglobin, and BMI (standing in for Weight, which has no
 * standalone risk band of its own in the spec). Height/temperature/glucose/MUAC/fetal
 * movements/heart rate/fundal height all had rows in the retired version too; add them here the
 * same way once their Summary card is needed.
 *
 * [hemoglobin]'s `sickleCellCode` parameter is always passed `null` from [buildTestsFindings] —
 * the retired code elevated Hb risk for a sickle-cell-positive answer using OLD 1-based Excel
 * option codes (`setOf(2, 3)`) that don't correspond to the live schema's string `value_code`s;
 * rather than guess the mapping, sickle-cell status isn't factored into Hb risk yet.
 */
object VisitFormRiskAssessment {

  private val RISK_SEVERITY_ORDER = listOf(RiskLevel.HIGH, RiskLevel.MODERATE, RiskLevel.MILD, RiskLevel.LOW)

  const val BP_MILD_SYSTOLIC = 135.0
  const val BP_MILD_DIASTOLIC = 85.0
  const val BP_MODERATE_SYSTOLIC = 140.0
  const val BP_MODERATE_DIASTOLIC = 90.0
  const val BP_SEVERE_SYSTOLIC = 160.0
  const val BP_SEVERE_DIASTOLIC = 110.0
  const val BP_HYPOTENSION_SYSTOLIC = 90.0

  const val HB_SEVERE_MAX = 7.0
  /** 7 - 9.9 g/dl (exclusive upper bound at 10.0), or sickle cell positive - see this object's doc. */
  const val HB_MODERATE_MAX = 10.0
  /** 10 - 10.9 g/dl (exclusive upper bound at 11.0). */
  const val HB_MILD_MAX = 11.0

  const val BMI_UNDERWEIGHT = 18.5
  const val BMI_OVERWEIGHT = 35.0

  fun bloodPressure(systolic: Double, diastolic: Double): RiskLevel = when {
    systolic < BP_HYPOTENSION_SYSTOLIC || systolic >= BP_SEVERE_SYSTOLIC || diastolic >= BP_SEVERE_DIASTOLIC ->
      RiskLevel.HIGH
    systolic >= BP_MODERATE_SYSTOLIC || diastolic >= BP_MODERATE_DIASTOLIC -> RiskLevel.MODERATE
    systolic >= BP_MILD_SYSTOLIC || diastolic >= BP_MILD_DIASTOLIC -> RiskLevel.MILD
    else -> RiskLevel.LOW
  }

  fun hemoglobin(hb: Double, sickleCellCode: Int?): RiskLevel = when {
    hb < HB_SEVERE_MAX -> RiskLevel.HIGH
    hb < HB_MODERATE_MAX || sickleCellCode != null -> RiskLevel.MODERATE
    hb < HB_MILD_MAX -> RiskLevel.MILD
    else -> RiskLevel.LOW
  }

  fun bmi(value: Double): RiskLevel =
    if (value < BMI_UNDERWEIGHT || value >= BMI_OVERWEIGHT) RiskLevel.HIGH else RiskLevel.LOW

  /** Worst-of aggregation - HIGH > MODERATE > MILD > LOW; empty list is LOW. */
  fun overall(levels: List<RiskLevel>): RiskLevel =
    RISK_SEVERITY_ORDER.firstOrNull { it in levels } ?: RiskLevel.LOW

  private fun List<VisitFormRiskFinding>.sortedByRisk(): List<VisitFormRiskFinding> =
    sortedBy { RISK_SEVERITY_ORDER.indexOf(it.riskLevel) }

  /** BP/Hemoglobin/Weight(via BMI) - the Summary tab's Tests card rows this pass, worst-first;
   * skips a row whose reading isn't answered yet rather than showing a blank card. */
  fun buildTestsFindings(answers: FormAnswers): List<VisitFormRiskFinding> = buildList {
    val systolic = answers.valueOf(VisitFormQuestionCodes.BLOOD_PRESSURE_SYSTOLIC)?.toDoubleOrNull()
    val diastolic = answers.valueOf(VisitFormQuestionCodes.BLOOD_PRESSURE_DIASTOLIC)?.toDoubleOrNull()
    val hb = answers.valueOf(VisitFormQuestionCodes.HAEMOGLOBIN)?.toDoubleOrNull()
    val heightCm = answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM)?.toDoubleOrNull()
    val weightKg = answers.valueOf(VisitFormQuestionCodes.WEIGHT_KG)?.toDoubleOrNull()

    if (systolic != null && diastolic != null) {
      add(
        VisitFormRiskFinding(
          labelRes = R.string.visit_form_vs_bp,
          value = "${systolic.toInt()}/${diastolic.toInt()} mmHg",
          referenceRange = "<135/85 mmHg",
          riskLevel = bloodPressure(systolic, diastolic),
        ),
      )
    }
    if (hb != null) {
      add(
        VisitFormRiskFinding(
          labelRes = R.string.visit_form_vd_hemoglobin,
          value = "$hb g/dl",
          referenceRange = "≥11 g/dl",
          riskLevel = hemoglobin(hb, sickleCellCode = null),
        ),
      )
    }
    // Weight has no standalone risk band in the spec - BMI (derived from this same height/weight)
    // stands in for its risk tier, same reasoning as VisitFormComputedFieldEvaluator.BMI.
    if (weightKg != null && heightCm != null && heightCm > 0.0) {
      val heightM = heightCm / 100.0
      val bmiValue = weightKg / (heightM * heightM)
      add(
        VisitFormRiskFinding(
          labelRes = R.string.visit_form_vd_weight,
          value = "$weightKg kg",
          referenceRange = "BMI 18.5–34.9",
          riskLevel = bmi(bmiValue),
        ),
      )
    }
  }.sortedByRisk()
}
