package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The digit cap is what stops `6666` reaching a "2 digit, Range 2 to 15" field (form spec row 33).
 * It is deliberately separate from [FormNumericRangeValidator], which reports a right-length but
 * out-of-range value (`16`) as a message the Sakhi can read rather than a silent keystroke block.
 */
class FormNumericInputRuleTest {

  private fun numberField(questionCode: String, min: Double? = null, max: Double? = null) =
    FormFieldSchema(
      label = questionCode,
      required = true,
      inputTypeRaw = "number",
      questionCode = questionCode,
      numericRange = if (min == null && max == null) null else FormNumericRange(min, max),
    )

  @Test
  fun `cap follows the digit count of the range maximum`() {
    // Spec row 33: household members, range 2..15 -> "2 digit".
    assertEquals(2, FormNumericInputRule.maxDigits(numberField("household", min = 2.0, max = 15.0)))
    // Spec row 47: living children, range 0..14 -> also 2 digits.
    assertEquals(2, FormNumericInputRule.maxDigits(numberField("living_children", min = 0.0, max = 14.0)))
    assertEquals(1, FormNumericInputRule.maxDigits(numberField("single_digit", max = 9.0)))
    assertEquals(3, FormNumericInputRule.maxDigits(numberField("triple", max = 100.0)))
  }

  @Test
  fun `a capped field rejects the reported bug's input`() {
    val cap = requireNotNull(FormNumericInputRule.maxDigits(numberField("household", 2.0, 15.0)))

    // "6666" is what QA managed to type; the cap is what keeps it out of the answer.
    assertEquals("66", "6666".take(cap))
  }

  @Test
  fun `no range means no cap`() {
    assertNull(FormNumericInputRule.maxDigits(numberField("unbounded")))
    assertNull(FormNumericInputRule.maxDigits(numberField("min_only", min = 1.0)))
  }

  @Test
  fun `children under five is capped at one digit despite having no range`() {
    // Spec row 34 says "1 digit" and states no range, so the schema has no numericRange to derive
    // a cap from — the reported bug: the field accepted "11".
    val field = numberField(CHILDREN_UNDER_FIVE_QUESTION_CODE)
    val cap = requireNotNull(FormNumericInputRule.maxDigits(field))

    assertEquals(1, cap)
    assertEquals("1", "11".take(cap))
  }

  @Test
  fun `the explicit cap takes precedence over a schema range - documenting the retirement hazard`() {
    // maxDigits() checks EXPLICIT_MAX_DIGITS before numericRange, so if the backend later declares a
    // wider range for this field the hardcoded 1 would silently override it. That precedence is
    // intentional (the stopgap must work today) but makes deleting the entry part of the schema
    // change, not an optional tidy-up.
    val field = numberField(CHILDREN_UNDER_FIVE_QUESTION_CODE, min = 0.0, max = 20.0)

    assertEquals(1, FormNumericInputRule.maxDigits(field))
  }

  @Test
  fun `an implausibly wide range is treated as uncapped`() {
    // Better to leave a field uncapped than to invent a limit that looks deliberate.
    assertNull(FormNumericInputRule.maxDigits(numberField("huge", max = 1e12)))
  }

  @Test
  fun `a negative maximum is not a usable cap`() {
    assertNull(FormNumericInputRule.maxDigits(numberField("negative", max = -5.0)))
  }

  @Test
  fun `non-number input types are never capped`() {
    val textField = FormFieldSchema(
      label = "Name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "first_name",
      numericRange = FormNumericRange(2.0, 15.0),
    )

    assertNull(FormNumericInputRule.maxDigits(textField))
  }

  @Test
  fun `mobile number keeps its own fixed-length rule`() {
    // No numericRange in the schema, so this rule stays out of the way and MobileNumberRule's
    // 10-digit cap applies in the renderer.
    assertNull(FormNumericInputRule.maxDigits(numberField(MobileNumberRule.QUESTION_CODE)))
  }
}
