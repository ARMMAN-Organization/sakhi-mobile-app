package org.armman.sakhi.data.visitform

import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.ui.visitform.VisitDataState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-016c Summary tab — worst-of aggregation ([VisitRiskAssessment.overall])
 * and the High → Moderate → Mild → Low sort order applied to the Tests/
 * Symptoms finding cards (product convention: any risk-tagged list sorts
 * worst-first, not by field order).
 */
class VisitRiskAssessmentTest {

  // heightLockedCm set so height/BMI (only shown on the first, editable-height
  // visit) stay out of the Tests findings — keeps each test focused on the
  // fields it's actually asserting about.
  private fun baseState() = VisitDataState(heightLockedCm = 150)

  @Test
  fun `overall returns the single worst risk level present`() {
    assertEquals(
      RiskLevel.HIGH,
      VisitRiskAssessment.overall(listOf(RiskLevel.LOW, RiskLevel.HIGH, RiskLevel.MILD)),
    )
    assertEquals(RiskLevel.MODERATE, VisitRiskAssessment.overall(listOf(RiskLevel.LOW, RiskLevel.MODERATE)))
    assertEquals(RiskLevel.MILD, VisitRiskAssessment.overall(listOf(RiskLevel.LOW, RiskLevel.MILD)))
  }

  @Test
  fun `overall on an empty list is LOW`() {
    assertEquals(RiskLevel.LOW, VisitRiskAssessment.overall(emptyList()))
  }

  @Test
  fun `Tests findings sort High to Low, ties keep their original field order`() {
    val state = baseState().copy(
      bpSystolic = "136", // MILD (>=135 systolic, below the 140 Moderate band)
      bpDiastolic = "80",
      hemoglobin = "9.5", // MODERATE (7 <= Hb < 10)
      bloodGlucose = "100", // LOW (< 140 GDM threshold)
      temperatureF = "99.5", // HIGH (>= 99.0 fever threshold)
      muacCm = "25", // LOW (>= 23cm threshold)
    )

    val findings = VisitRiskAssessment.buildTestsFindings(state)

    assertEquals(
      listOf(RiskLevel.HIGH, RiskLevel.MODERATE, RiskLevel.MILD, RiskLevel.LOW, RiskLevel.LOW),
      findings.map { it.riskLevel },
    )
    assertEquals(R.string.visit_form_vd_temperature, findings.first().labelRes)
    // Glucose is built before MUAC in the field order, and the sort is
    // stable — so among the two LOW findings, Glucose stays first.
    val lowFindings = findings.filter { it.riskLevel == RiskLevel.LOW }
    assertEquals(R.string.visit_form_vd_blood_glucose, lowFindings[0].labelRes)
    assertEquals(R.string.visit_form_vd_muac, lowFindings[1].labelRes)
  }

  @Test
  fun `Symptoms findings sort High to Low`() {
    val state = baseState().copy(
      dangerSigns = setOf(3), // flagged -> MODERATE
      palmNails = 3, // "Normal" option -> LOW
      sclera = 1, // not normal -> MODERATE
      skin = 3, // "Normal" option -> LOW
    )

    val findings = VisitRiskAssessment.buildSymptomsFindings(state)

    // symptomFlag() never returns HIGH/MILD, but the two MODERATE findings
    // (danger signs, sclera) must still sort ahead of both LOW ones.
    assertEquals(
      listOf(RiskLevel.MODERATE, RiskLevel.MODERATE, RiskLevel.LOW, RiskLevel.LOW),
      findings.map { it.riskLevel },
    )
  }
}
