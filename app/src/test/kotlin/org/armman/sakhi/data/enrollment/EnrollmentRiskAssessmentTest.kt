package org.armman.sakhi.data.enrollment

import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.forms.DOB_QUESTION_CODE
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes as Q
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes.ValueCode as V
import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODE_CORRECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * CR-034 baseline enrollment risk. Case numbers match docs/test-cases/enrollment-baseline-risk.md
 * (A1..I53). Registration date is pinned in every test so age never depends on the clock.
 */
class EnrollmentRiskAssessmentTest {

  private val registrationDate = LocalDate.of(2026, 8, 5)

  /** Empty answers with only the registration date set — every test layers its fields on top. */
  private fun answers(): FormAnswers = FormAnswers()
    .withSingleValue(REGISTRATION_DATE_QUESTION_CODE_CORRECTED, registrationDate.toString())

  private fun FormAnswers.withAge(years: Long): FormAnswers =
    withSingleValue(DOB_QUESTION_CODE, registrationDate.minusYears(years).toString())

  private fun level(answers: FormAnswers): RiskLevel =
    EnrollmentRiskAssessment.baselineRiskLevel(answers, registrationDate)

  private fun conditions(answers: FormAnswers): List<EnrollmentRiskCondition> =
    EnrollmentRiskAssessment.findings(answers, registrationDate).map { it.condition }

  private fun findingFor(
    answers: FormAnswers,
    condition: EnrollmentRiskCondition,
  ): EnrollmentRiskFinding? =
    EnrollmentRiskAssessment.findings(answers, registrationDate).firstOrNull { it.condition == condition }

  // --- A. Age (HIGH + permanent) ------------------------------------------------

  @Test
  fun `A1 age 18 at registration is HIGH and permanent`() {
    val finding = findingFor(answers().withAge(18), EnrollmentRiskCondition.UNDERAGE)
    assertEquals(RiskLevel.HIGH, finding?.riskLevel)
    assertEquals(EnrollmentRiskAction.REFERRAL, finding?.action)
    assertTrue(finding?.isPermanent == true)
  }

  @Test
  fun `A2 age 19 is not an age risk`() {
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(answers().withAge(19)))
  }

  @Test
  fun `A3 age 34 is not an age risk`() {
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(answers().withAge(34)))
  }

  @Test
  fun `A4 age 35 is HIGH and permanent`() {
    val finding = findingFor(answers().withAge(35), EnrollmentRiskCondition.OVERAGE)
    assertEquals(RiskLevel.HIGH, finding?.riskLevel)
    assertTrue(finding?.isPermanent == true)
  }

  @Test
  fun `A5 age 36 is HIGH`() {
    assertEquals(RiskLevel.HIGH, level(answers().withAge(36)))
  }

  @Test
  fun `A6 missing date of birth raises no age finding`() {
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(answers()))
    assertEquals(RiskLevel.LOW, level(answers()))
  }

  @Test
  fun `A7 age is graded against the registration date, not today`() {
    // Registered 6 months ago at 34; she is 35 by the time the record is read. The registration-date
    // answer must win, so this stays LOW.
    val registeredAt = registrationDate.minusMonths(6)
    val onTheDay = FormAnswers()
      .withSingleValue(REGISTRATION_DATE_QUESTION_CODE_CORRECTED, registeredAt.toString())
      .withSingleValue(DOB_QUESTION_CODE, registeredAt.minusYears(34).toString())
    assertEquals(RiskLevel.LOW, EnrollmentRiskAssessment.baselineRiskLevel(onTheDay, registrationDate))
  }

  @Test
  fun `A8 malformed date of birth raises no finding`() {
    val malformed = answers().withSingleValue(DOB_QUESTION_CODE, "05-08-1988")
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(malformed))
  }

  // --- B. Obstetric counts ------------------------------------------------------

  private fun FormAnswers.count(code: String, value: String) = withSingleValue(code, value)

  @Test
  fun `B9 living children below para is HIGH`() {
    val a = answers()
      .count(FormObstetricRuleset.PARA, "2")
      .count(FormObstetricRuleset.LIVING_CHILDREN, "1")
    assertEquals(RiskLevel.HIGH, level(a))
    assertTrue(EnrollmentRiskCondition.LIVING_CHILDREN_BELOW_PARA in conditions(a))
  }

  @Test
  fun `B10 living children equal to para is not flagged`() {
    val a = answers()
      .count(FormObstetricRuleset.PARA, "2")
      .count(FormObstetricRuleset.LIVING_CHILDREN, "2")
    assertEquals(RiskLevel.LOW, level(a))
  }

  @Test
  fun `B11 living children without para is not flagged`() {
    val a = answers().count(FormObstetricRuleset.LIVING_CHILDREN, "1")
    assertEquals(RiskLevel.LOW, level(a))
  }

  @Test
  fun `B12 two abortions is HIGH`() {
    val a = answers().count(FormObstetricRuleset.ABORTIONS, "2")
    assertEquals(RiskLevel.HIGH, level(a))
    assertEquals(EnrollmentRiskAction.BOTH, findingFor(a, EnrollmentRiskCondition.RECURRENT_ABORTIONS)?.action)
  }

  @Test
  fun `B13 one abortion is not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().count(FormObstetricRuleset.ABORTIONS, "1")))
  }

  @Test
  fun `B14 blank abortions is not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().count(FormObstetricRuleset.ABORTIONS, "")))
  }

  @Test
  fun `B15 one still birth is HIGH`() {
    assertEquals(RiskLevel.HIGH, level(answers().count(FormObstetricRuleset.STILL_BIRTHS, "1")))
  }

  @Test
  fun `B16 zero still births is not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().count(FormObstetricRuleset.STILL_BIRTHS, "0")))
  }

  @Test
  fun `B17 gravida 4 is MODERATE`() {
    val a = answers().count(FormObstetricRuleset.GRAVIDA, "4")
    assertEquals(RiskLevel.MODERATE, level(a))
    assertTrue(EnrollmentRiskCondition.HIGH_GRAVIDITY in conditions(a))
  }

  @Test
  fun `B18 gravida 3 is not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().count(FormObstetricRuleset.GRAVIDA, "3")))
  }

  @Test
  fun `B19 non-numeric count is ignored without crashing`() {
    assertEquals(RiskLevel.LOW, level(answers().count(FormObstetricRuleset.ABORTIONS, "two")))
  }

  // --- C. Last delivery ---------------------------------------------------------

  @Test
  fun `C20 preterm last delivery is HIGH`() {
    val a = answers().withSingleValue(Q.LAST_DELIVERY_DURATION, V.TERM_PRE)
    assertEquals(RiskLevel.HIGH, level(a))
    assertEquals(
      EnrollmentRiskAction.HEALTH_MESSAGE,
      findingFor(a, EnrollmentRiskCondition.PRETERM_LAST_DELIVERY)?.action,
    )
  }

  @Test
  fun `C21 full term and post term are not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().withSingleValue(Q.LAST_DELIVERY_DURATION, "full_term")))
    assertEquals(RiskLevel.LOW, level(answers().withSingleValue(Q.LAST_DELIVERY_DURATION, "post_term")))
  }

  @Test
  fun `C22 caesarean with an interval under 3 years is HIGH`() {
    val a = answers()
      .withSingleValue(Q.LAST_DELIVERY_TYPE, V.DELIVERY_CAESARIAN)
      .withSingleValue(Q.LAST_PREGNANCY_INTERVAL, V.INTERVAL_UNDER_3_YEARS)
    assertEquals(RiskLevel.HIGH, level(a))
    assertTrue(EnrollmentRiskCondition.CAESAREAN_SHORT_INTERVAL in conditions(a))
  }

  @Test
  fun `C23 caesarean with an interval of 3 years or more is not flagged`() {
    val a = answers()
      .withSingleValue(Q.LAST_DELIVERY_TYPE, V.DELIVERY_CAESARIAN)
      .withSingleValue(Q.LAST_PREGNANCY_INTERVAL, "more_than_3_years_ago")
    assertEquals(RiskLevel.LOW, level(a))
  }

  @Test
  fun `C24 caesarean with no interval answered is not flagged`() {
    val a = answers().withSingleValue(Q.LAST_DELIVERY_TYPE, V.DELIVERY_CAESARIAN)
    assertEquals(RiskLevel.LOW, level(a))
    assertFalse(EnrollmentRiskCondition.CAESAREAN_SHORT_INTERVAL in conditions(a))
  }

  @Test
  fun `C25 normal or forceps delivery with a short interval is not flagged`() {
    val base = answers().withSingleValue(Q.LAST_PREGNANCY_INTERVAL, V.INTERVAL_UNDER_3_YEARS)
    assertEquals(RiskLevel.LOW, level(base.withSingleValue(Q.LAST_DELIVERY_TYPE, "normal")))
    assertEquals(RiskLevel.LOW, level(base.withSingleValue(Q.LAST_DELIVERY_TYPE, "forcep")))
  }

  @Test
  fun `C26 last delivery outcome still birth is HIGH`() {
    assertEquals(
      RiskLevel.HIGH,
      level(answers().withSingleValue(Q.LAST_DELIVERY_OUTCOME, V.OUTCOME_STILL_BIRTH)),
    )
  }

  @Test
  fun `C27 last delivery outcome live birth is not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().withSingleValue(Q.LAST_DELIVERY_OUTCOME, "live_birth")))
  }

  // --- D. Sickle cell -----------------------------------------------------------

  @Test
  fun `D28 sickle cell disease is HIGH with a referral`() {
    val a = answers().withSingleValue(Q.SICKLE_CELL_STATUS, V.SICKLE_CELL_DISEASE)
    assertEquals(RiskLevel.HIGH, level(a))
    assertEquals(EnrollmentRiskAction.BOTH, findingFor(a, EnrollmentRiskCondition.SICKLE_CELL_DISEASE)?.action)
  }

  @Test
  fun `D29 sickle cell trait is a health message only`() {
    val a = answers().withSingleValue(Q.SICKLE_CELL_STATUS, V.SICKLE_CELL_TRAIT)
    assertEquals(RiskLevel.LOW, level(a))
    assertEquals(
      EnrollmentRiskAction.HEALTH_MESSAGE,
      findingFor(a, EnrollmentRiskCondition.SICKLE_CELL_TRAIT)?.action,
    )
  }

  @Test
  fun `D30 normal, untested and unaware sickle cell answers are not flagged`() {
    listOf("tested_and_result_is_normal", "not_tested_yet", "don_t_know_not_aware").forEach { code ->
      val a = answers().withSingleValue(Q.SICKLE_CELL_STATUS, code)
      assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(a))
    }
  }

  // --- E. Moderate conditions (Q43 / Q58) --------------------------------------

  @Test
  fun `E31 a self-reported medical condition is MODERATE`() {
    val a = answers().withMultiValue(Q.SELF_MEDICAL_CONDITIONS, listOf("hypertension_high_bp"))
    assertEquals(RiskLevel.MODERATE, level(a))
  }

  @Test
  fun `E32 no known medical condition is not flagged`() {
    val a = answers().withMultiValue(Q.SELF_MEDICAL_CONDITIONS, listOf("no_known_medical_condition"))
    assertEquals(RiskLevel.LOW, level(a))
  }

  @Test
  fun `E33 don't know is not flagged`() {
    val a = answers().withMultiValue(Q.SELF_MEDICAL_CONDITIONS, listOf("don_t_know"))
    assertEquals(RiskLevel.LOW, level(a))
  }

  @Test
  fun `E34 an ANC1-reported condition is MODERATE with a referral`() {
    val a = answers().withMultiValue(
      Q.ANC1_HIGH_RISK_CONDITIONS,
      listOf("gestational_diabetes_in_previous_pregnancy"),
    )
    assertEquals(RiskLevel.MODERATE, level(a))
    assertEquals(
      EnrollmentRiskAction.REFERRAL,
      findingFor(a, EnrollmentRiskCondition.ANC1_REPORTED_CONDITION)?.action,
    )
  }

  @Test
  fun `E35 both none-codes are honoured even though the two questions spell them differently`() {
    val anc1None = answers().withMultiValue(
      Q.ANC1_HIGH_RISK_CONDITIONS,
      listOf("no_known_medical_condition_identified_during_check_up"),
    )
    val selfNone = answers().withMultiValue(Q.SELF_MEDICAL_CONDITIONS, listOf("no_known_medical_condition"))
    assertEquals(RiskLevel.LOW, level(anc1None))
    assertEquals(RiskLevel.LOW, level(selfNone))
  }

  @Test
  fun `E36 thalassemia, a Q58-only option, is MODERATE`() {
    assertEquals(
      RiskLevel.MODERATE,
      level(answers().withMultiValue(Q.SELF_MEDICAL_CONDITIONS, listOf("thalassemia"))),
    )
  }

  @Test
  fun `E37 an empty condition multiselect is not flagged`() {
    assertEquals(RiskLevel.LOW, level(answers().withMultiValue(Q.SELF_MEDICAL_CONDITIONS, emptyList())))
  }

  // --- F. Health-message-only findings ----------------------------------------

  @Test
  fun `F38 home delivery is a message and does not raise the level`() {
    val a = answers().withSingleValue(Q.LAST_DELIVERY_PLACE, V.DELIVERY_PLACE_HOME)
    assertEquals(RiskLevel.LOW, level(a))
    assertEquals(listOf(EnrollmentRiskCondition.HOME_DELIVERY), conditions(a))
  }

  @Test
  fun `F39 low birth weight is a message, above 2 point 5 kg is nothing`() {
    val low = answers().withSingleValue(Q.LAST_CHILD_BIRTH_WEIGHT, V.BIRTH_WEIGHT_UNDER_2_5_KG)
    assertEquals(RiskLevel.LOW, level(low))
    assertEquals(listOf(EnrollmentRiskCondition.LOW_BIRTH_WEIGHT), conditions(low))
    // Backend spells the negative band "greather_than_2_5_kg" — carried verbatim on purpose.
    val fine = answers().withSingleValue(Q.LAST_CHILD_BIRTH_WEIGHT, "greather_than_2_5_kg")
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(fine))
  }

  @Test
  fun `F40 previous delivery complications are a message, no complications is nothing`() {
    listOf(V.COMPLICATION_MISCARRIAGE, V.COMPLICATION_DURING_DELIVERY, V.COMPLICATION_WITH_BABY)
      .forEach { code ->
        val a = answers().withMultiValue(Q.PREVIOUS_DELIVERY_COMPLICATIONS, listOf(code))
        assertEquals(RiskLevel.LOW, level(a))
        assertEquals(listOf(EnrollmentRiskCondition.PREVIOUS_DELIVERY_COMPLICATIONS), conditions(a))
      }
    val none = answers().withMultiValue(Q.PREVIOUS_DELIVERY_COMPLICATIONS, listOf(V.NO_COMPLICATIONS))
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(none))
  }

  @Test
  fun `F41 substance use is a message, no is nothing`() {
    val used = answers().withMultiValue(Q.SUBSTANCE_USE, listOf("alcohol_use"))
    assertEquals(RiskLevel.LOW, level(used))
    assertEquals(listOf(EnrollmentRiskCondition.SUBSTANCE_USE), conditions(used))
    val none = answers().withMultiValue(Q.SUBSTANCE_USE, listOf(V.SUBSTANCE_NONE))
    assertEquals(emptyList<EnrollmentRiskCondition>(), conditions(none))
  }

  // --- G. Aggregation and ordering ---------------------------------------------

  @Test
  fun `G42 overall on an empty list is LOW`() {
    assertEquals(RiskLevel.LOW, EnrollmentRiskAssessment.overall(emptyList()))
  }

  @Test
  fun `G43 high, moderate and message findings together are overall HIGH`() {
    val a = answers()
      .count(FormObstetricRuleset.ABORTIONS, "2")
      .count(FormObstetricRuleset.GRAVIDA, "4")
      .withSingleValue(Q.LAST_DELIVERY_PLACE, V.DELIVERY_PLACE_HOME)
    assertEquals(RiskLevel.HIGH, level(a))
  }

  @Test
  fun `G44 only moderate findings are overall MODERATE`() {
    val a = answers()
      .count(FormObstetricRuleset.GRAVIDA, "4")
      .withMultiValue(Q.SELF_MEDICAL_CONDITIONS, listOf("thyroid_disorder"))
    assertEquals(RiskLevel.MODERATE, level(a))
  }

  @Test
  fun `G45 findings sort High to Low and ties keep field order`() {
    val a = answers()
      .withSingleValue(Q.LAST_DELIVERY_PLACE, V.DELIVERY_PLACE_HOME)
      .count(FormObstetricRuleset.GRAVIDA, "4")
      .count(FormObstetricRuleset.ABORTIONS, "2")
      .count(FormObstetricRuleset.STILL_BIRTHS, "1")
    val levels = EnrollmentRiskAssessment.findings(a, registrationDate).map { it.riskLevel }
    assertEquals(
      listOf(RiskLevel.HIGH, RiskLevel.HIGH, RiskLevel.MODERATE, RiskLevel.LOW),
      levels,
    )
    // Ties keep the evaluator's field order: abortions is evaluated before still births.
    assertEquals(
      listOf(EnrollmentRiskCondition.RECURRENT_ABORTIONS, EnrollmentRiskCondition.PREVIOUS_STILL_BIRTH),
      conditions(a).take(2),
    )
  }

  @Test
  fun `G46 each rule carries the action the spec assigns it`() {
    val a = answers()
      .withAge(36)
      .count(FormObstetricRuleset.PARA, "2")
      .count(FormObstetricRuleset.LIVING_CHILDREN, "1")
      .count(FormObstetricRuleset.GRAVIDA, "4")
      .withSingleValue(Q.LAST_DELIVERY_DURATION, V.TERM_PRE)
      .withSingleValue(Q.SICKLE_CELL_STATUS, V.SICKLE_CELL_DISEASE)
      .withSingleValue(Q.LAST_DELIVERY_PLACE, V.DELIVERY_PLACE_HOME)
    assertEquals(EnrollmentRiskAction.REFERRAL, findingFor(a, EnrollmentRiskCondition.OVERAGE)?.action)
    assertEquals(
      EnrollmentRiskAction.BOTH,
      findingFor(a, EnrollmentRiskCondition.LIVING_CHILDREN_BELOW_PARA)?.action,
    )
    assertEquals(EnrollmentRiskAction.BOTH, findingFor(a, EnrollmentRiskCondition.HIGH_GRAVIDITY)?.action)
    assertEquals(EnrollmentRiskAction.BOTH, findingFor(a, EnrollmentRiskCondition.SICKLE_CELL_DISEASE)?.action)
    assertEquals(
      EnrollmentRiskAction.HEALTH_MESSAGE,
      findingFor(a, EnrollmentRiskCondition.PRETERM_LAST_DELIVERY)?.action,
    )
    assertEquals(
      EnrollmentRiskAction.HEALTH_MESSAGE,
      findingFor(a, EnrollmentRiskCondition.HOME_DELIVERY)?.action,
    )
  }

  // --- I. Consistency guard ----------------------------------------------------

  @Test
  fun `I53 the full high-risk record raises every expected finding exactly once`() {
    val a = answers()
      .withAge(36)
      .count(FormObstetricRuleset.GRAVIDA, "5")
      .count(FormObstetricRuleset.PARA, "3")
      .count(FormObstetricRuleset.LIVING_CHILDREN, "1")
      .count(FormObstetricRuleset.ABORTIONS, "2")
      .count(FormObstetricRuleset.STILL_BIRTHS, "1")
      .withSingleValue(Q.LAST_PREGNANCY_INTERVAL, V.INTERVAL_UNDER_3_YEARS)
      .withSingleValue(Q.LAST_DELIVERY_DURATION, V.TERM_PRE)
      .withSingleValue(Q.LAST_DELIVERY_TYPE, V.DELIVERY_CAESARIAN)
      .withSingleValue(Q.LAST_DELIVERY_OUTCOME, V.OUTCOME_STILL_BIRTH)
      .withSingleValue(Q.SICKLE_CELL_STATUS, V.SICKLE_CELL_DISEASE)

    assertEquals(RiskLevel.HIGH, level(a))
    val found = conditions(a)
    val expectedHigh = listOf(
      EnrollmentRiskCondition.OVERAGE,
      EnrollmentRiskCondition.LIVING_CHILDREN_BELOW_PARA,
      EnrollmentRiskCondition.RECURRENT_ABORTIONS,
      EnrollmentRiskCondition.PREVIOUS_STILL_BIRTH,
      EnrollmentRiskCondition.PRETERM_LAST_DELIVERY,
      EnrollmentRiskCondition.CAESAREAN_SHORT_INTERVAL,
      EnrollmentRiskCondition.LAST_OUTCOME_STILL_BIRTH,
      EnrollmentRiskCondition.SICKLE_CELL_DISEASE,
    )
    expectedHigh.forEach { assertTrue("missing $it", it in found) }
    // Gravida 5 also earns the moderate high-gravidity tag; nothing is double-counted.
    assertEquals(expectedHigh.size + 1, found.size)
    assertEquals(found.size, found.distinct().size)
  }
}
