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
}
