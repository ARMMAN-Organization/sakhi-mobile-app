package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormCrossFieldValidatorTest {

  private val lteRule = FormCrossFieldRule(
    rule = "LTE",
    fields = listOf("para_number_of_births_after_24_weeks", "gravida_total_number_of_pregnancies"),
  )

  private val sumEqualsRule = FormCrossFieldRule(
    rule = "SUM_EQUALS",
    fields = listOf("living_children", "still_births", "abortions_pregnancy_losses_before_24_weeks"),
    equals = "gravida_total_number_of_pregnancies",
  )

  @Test
  fun `LTE passes when left is less than or equal to right`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        "para_number_of_births_after_24_weeks" to "1",
        "gravida_total_number_of_pregnancies" to "2",
      ),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(lteRule), answers).isEmpty())
  }

  @Test
  fun `LTE fails when left exceeds right`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        "para_number_of_births_after_24_weeks" to "3",
        "gravida_total_number_of_pregnancies" to "2",
      ),
    )
    assertEquals(listOf(lteRule), FormCrossFieldValidator.violatedRules(listOf(lteRule), answers))
  }

  @Test
  fun `LTE not evaluable yet when a field is unanswered`() {
    val answers = FormAnswers(singleValues = mapOf("gravida_total_number_of_pregnancies" to "2"))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(lteRule), answers).isEmpty())
  }

  @Test
  fun `SUM_EQUALS passes when the sum matches`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        "living_children" to "1",
        "still_births" to "1",
        "abortions_pregnancy_losses_before_24_weeks" to "1",
        "gravida_total_number_of_pregnancies" to "3",
      ),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(sumEqualsRule), answers).isEmpty())
  }

  @Test
  fun `SUM_EQUALS fails when the sum does not match`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        "living_children" to "1",
        "still_births" to "0",
        "abortions_pregnancy_losses_before_24_weeks" to "0",
        "gravida_total_number_of_pregnancies" to "2",
      ),
    )
    assertEquals(listOf(sumEqualsRule), FormCrossFieldValidator.violatedRules(listOf(sumEqualsRule), answers))
  }

  @Test
  fun `unknown rule type is never a violation`() {
    val rule = FormCrossFieldRule(rule = "SOME_FUTURE_RULE", fields = listOf("a", "b"))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(rule), FormAnswers()).isEmpty())
  }
}
