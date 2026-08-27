package org.armman.sakhi.data.forms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormVisibilityEvaluatorTest {

  private fun field(visibleWhen: FormVisibleWhen?) = FormFieldSchema(
    label = "Field",
    required = true,
    inputTypeRaw = "text",
    questionCode = "dependent_field",
    visibleWhen = visibleWhen,
  )

  @Test
  fun `no visibleWhen condition is always visible`() {
    assertTrue(FormVisibilityEvaluator.isVisible(field(null), FormAnswers()))
  }

  @Test
  fun `eq operator matches shows the field`() {
    val condition = FormVisibleWhen(field = "gate", value = "yes", operator = "eq")
    val answers = FormAnswers(singleValues = mapOf("gate" to "yes"))

    assertTrue(FormVisibilityEvaluator.isVisible(field(condition), answers))
  }

  @Test
  fun `eq operator mismatch hides the field`() {
    val condition = FormVisibleWhen(field = "gate", value = "yes", operator = "eq")
    val answers = FormAnswers(singleValues = mapOf("gate" to "no"))

    assertFalse(FormVisibilityEvaluator.isVisible(field(condition), answers))
  }

  @Test
  fun `governing field unanswered hides the dependent field`() {
    val condition = FormVisibleWhen(field = "gate", value = "yes", operator = "eq")

    assertFalse(FormVisibilityEvaluator.isVisible(field(condition), FormAnswers()))
  }

  @Test
  fun `unrecognized operator fails open and shows the field`() {
    val condition = FormVisibleWhen(field = "gate", value = "yes", operator = "neq")
    val answers = FormAnswers(singleValues = mapOf("gate" to "no"))

    assertTrue(FormVisibilityEvaluator.isVisible(field(condition), answers))
  }

  // --- gte / lt / isSet -------------------------------------------------------------------------
  // The reported bug: "When was your last pregnancy?" must only appear once Gravida >= 2. Before
  // these operators existed, a `gte` rule fell through to the unknown-operator branch above and the
  // question showed for every Gravida, including 1.

  private fun gravida(value: String) = FormAnswers(singleValues = mapOf("gravida" to value))

  private fun gteTwo(operator: String = "gte") =
    field(FormVisibleWhen(field = "gravida", value = "2", operator = operator))

  @Test
  fun `gte hides the field below the threshold and shows it at or above`() {
    assertFalse(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("1")))
    assertTrue(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("2")))
    assertTrue(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("5")))
    assertTrue(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("14")))
  }

  @Test
  fun `gte on an unanswered governing field stays hidden`() {
    assertFalse(FormVisibilityEvaluator.isVisible(gteTwo(), FormAnswers()))
    assertFalse(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("")))
  }

  @Test
  fun `a non-numeric answer fails open rather than hiding a question`() {
    // Losing a required answer costs a re-visit; showing a spare question costs a moment.
    assertTrue(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("abc")))
  }

  @Test
  fun `a non-numeric rule value fails open`() {
    val condition = FormVisibleWhen(field = "gravida", value = "two", operator = "gte")

    assertTrue(FormVisibilityEvaluator.isVisible(field(condition), gravida("1")))
  }

  @Test
  fun `lt is the strict inverse of gte at the boundary`() {
    val ltTwo = field(FormVisibleWhen(field = "gravida", value = "2", operator = "lt"))

    assertTrue(FormVisibilityEvaluator.isVisible(ltTwo, gravida("1")))
    assertFalse(FormVisibilityEvaluator.isVisible(ltTwo, gravida("2")))
    assertFalse(FormVisibilityEvaluator.isVisible(ltTwo, gravida("3")))
  }

  @Test
  fun `decimal answers compare numerically, not as strings`() {
    // "10" < "2" as a string compare — the bug this guards against.
    assertTrue(FormVisibilityEvaluator.isVisible(gteTwo(), gravida("10")))
  }

  @Test
  fun `isSet carries no value and asks only about presence`() {
    // The backend types `value` as optional, so an isSet rule sends none at all.
    val isSet = field(FormVisibleWhen(field = "gravida", value = null, operator = "isSet"))

    assertTrue(FormVisibilityEvaluator.isVisible(isSet, gravida("1")))
    assertTrue(FormVisibilityEvaluator.isVisible(isSet, gravida("0")))
    assertFalse(FormVisibilityEvaluator.isVisible(isSet, gravida("")))
    assertFalse(FormVisibilityEvaluator.isVisible(isSet, FormAnswers()))
  }

  // --- contains ----------------------------------------------------------------------------------
  // Added 2026-08-06 for the Td-dose date fields: `has_the_women_received_td_dose contains
  // "td_1_date"` gates td_1_date's own date field on whether that checkbox is checked. Unlike every
  // other operator, the governing field here is a MULTI-value answer.

  private fun containsTd1() =
    field(FormVisibleWhen(field = "td_dose", value = "td_1_date", operator = "contains"))

  @Test
  fun `contains shows the field when the value is one of the checked boxes`() {
    val answers = FormAnswers(multiValues = mapOf("td_dose" to listOf("td_1_date", "td_2_date")))

    assertTrue(FormVisibilityEvaluator.isVisible(containsTd1(), answers))
  }

  @Test
  fun `contains hides the field when the value is not among the checked boxes`() {
    val answers = FormAnswers(multiValues = mapOf("td_dose" to listOf("td_2_date")))

    assertFalse(FormVisibilityEvaluator.isVisible(containsTd1(), answers))
  }

  @Test
  fun `contains hides the field when the governing multiselect is unanswered`() {
    assertFalse(FormVisibilityEvaluator.isVisible(containsTd1(), FormAnswers()))
  }

  @Test
  fun `contains reads the multi-value map, not the single-value one`() {
    // A stray single-value entry under the same question code must not satisfy `contains` — the
    // two maps are deliberately separate (see FormAnswers), and this operator only reads multi.
    val answers = FormAnswers(singleValues = mapOf("td_dose" to "td_1_date"))

    assertFalse(FormVisibilityEvaluator.isVisible(containsTd1(), answers))
  }
}
