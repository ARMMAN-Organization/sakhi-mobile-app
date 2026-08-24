package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.forms.FormAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class VisitFormComputedFieldEvaluatorTest {

  private val lmp = LocalDate.of(2026, 1, 1)

  private fun answersWith(weightKg: String?): FormAnswers = FormAnswers(
    singleValues = buildMap {
      put(VisitFormQuestionCodes.LMP, lmp.toString())
      if (weightKg != null) put(VisitFormQuestionCodes.WEIGHT_KG, weightKg)
    },
  )

  @Test
  fun `GESTATIONAL_WEIGHT_GAIN is Normal when gain meets the 0_2kg-per-week floor`() {
    // 20 weeks pregnant: visitDate = lmp + 20 weeks. 7 weeks into 2nd trimester (20-13) * 0.2 = 1.4kg minimum.
    val visitDate = lmp.plusWeeks(20)
    val answers = answersWith(weightKg = "55.4") // baseline 54.0 -> exactly 1.4kg gain

    val result = VisitFormComputedFieldEvaluator.compute(
      "GESTATIONAL_WEIGHT_GAIN",
      answers,
      visitDate,
      registrationWeightKg = 54.0,
    )

    assertEquals("Normal", result)
  }

  @Test
  fun `GESTATIONAL_WEIGHT_GAIN is Severe when gain falls below the expected minimum`() {
    val visitDate = lmp.plusWeeks(20)
    val answers = answersWith(weightKg = "55.0") // baseline 54.0 -> 1.0kg gain, below the 1.4kg floor

    val result = VisitFormComputedFieldEvaluator.compute(
      "GESTATIONAL_WEIGHT_GAIN",
      answers,
      visitDate,
      registrationWeightKg = 54.0,
    )

    assertEquals("Severe", result)
  }

  @Test
  fun `GESTATIONAL_WEIGHT_GAIN is null before the 2nd trimester`() {
    // 12 weeks pregnant - the spec's rate only applies from week 13 on.
    val visitDate = lmp.plusWeeks(12)
    val answers = answersWith(weightKg = "60.0")

    val result = VisitFormComputedFieldEvaluator.compute(
      "GESTATIONAL_WEIGHT_GAIN",
      answers,
      visitDate,
      registrationWeightKg = 54.0,
    )

    assertNull(result)
  }

  @Test
  fun `GESTATIONAL_WEIGHT_GAIN is null without a registration baseline weight`() {
    val visitDate = lmp.plusWeeks(20)
    val answers = answersWith(weightKg = "60.0")

    val result = VisitFormComputedFieldEvaluator.compute(
      "GESTATIONAL_WEIGHT_GAIN",
      answers,
      visitDate,
      registrationWeightKg = null,
    )

    assertNull(result)
  }

  @Test
  fun `GESTATIONAL_WEIGHT_GAIN is null without a current weight answer`() {
    val visitDate = lmp.plusWeeks(20)
    val answers = answersWith(weightKg = null)

    val result = VisitFormComputedFieldEvaluator.compute(
      "GESTATIONAL_WEIGHT_GAIN",
      answers,
      visitDate,
      registrationWeightKg = 54.0,
    )

    assertNull(result)
  }

  @Test
  fun `BMI computes for ANC_VISIT using its own WEIGHT_KG question code`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        VisitFormQuestionCodes.HEIGHT_CM to "160",
        VisitFormQuestionCodes.WEIGHT_KG to "64.0",
      ),
    )

    val result = VisitFormComputedFieldEvaluator.compute("BMI", answers, lmp)

    assertEquals("25.0", result)
  }

  @Test
  fun `BMI computes for POSTPARTUM_VISIT using its own current_weight_kg question code`() {
    // Bug fix regression test (2026-08-21): PP1's own schema uses "current_weight_kg", a
    // different question_code from ANC's WEIGHT_KG constant - BMI silently never computed on
    // PP1 before WEIGHT_KG_QUESTION_CODES existed.
    val answers = FormAnswers(
      singleValues = mapOf(
        VisitFormQuestionCodes.HEIGHT_CM to "160",
        "current_weight_kg" to "64.0",
      ),
    )

    val result = VisitFormComputedFieldEvaluator.compute("BMI", answers, lmp)

    assertEquals("25.0", result)
  }

  @Test
  fun `BMI is null without a height answer`() {
    val answers = FormAnswers(singleValues = mapOf("current_weight_kg" to "64.0"))

    val result = VisitFormComputedFieldEvaluator.compute("BMI", answers, lmp)

    assertNull(result)
  }

  @Test
  fun `BMI is null without any known weight answer`() {
    val answers = FormAnswers(singleValues = mapOf(VisitFormQuestionCodes.HEIGHT_CM to "160"))

    val result = VisitFormComputedFieldEvaluator.compute("BMI", answers, lmp)

    assertNull(result)
  }
}
