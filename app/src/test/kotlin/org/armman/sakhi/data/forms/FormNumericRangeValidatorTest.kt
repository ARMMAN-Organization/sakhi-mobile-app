package org.armman.sakhi.data.forms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormNumericRangeValidatorTest {

  @Test
  fun `no range declared is always valid`() {
    assertTrue(FormNumericRangeValidator.isWithinRange(null, "999"))
  }

  @Test
  fun `unparseable text is treated as valid (not this validator's job)`() {
    val range = FormNumericRange(min = 0.0, max = 10.0)
    assertTrue(FormNumericRangeValidator.isWithinRange(range, "not-a-number"))
  }

  @Test
  fun `value below min is invalid`() {
    val range = FormNumericRange(min = 2.0, max = 15.0)
    assertFalse(FormNumericRangeValidator.isWithinRange(range, "1"))
  }

  @Test
  fun `value above max is invalid`() {
    val range = FormNumericRange(min = 2.0, max = 15.0)
    assertFalse(FormNumericRangeValidator.isWithinRange(range, "16"))
  }

  @Test
  fun `value within range is valid`() {
    val range = FormNumericRange(min = 2.0, max = 15.0)
    assertTrue(FormNumericRangeValidator.isWithinRange(range, "10"))
  }

  @Test
  fun `boundary values are valid (inclusive)`() {
    val range = FormNumericRange(min = 0.0, max = 14.0)
    assertTrue(FormNumericRangeValidator.isWithinRange(range, "0"))
    assertTrue(FormNumericRangeValidator.isWithinRange(range, "14"))
  }

  @Test
  fun `only a min declared still enforces the lower bound`() {
    val range = FormNumericRange(min = 1.0, max = null)
    assertFalse(FormNumericRangeValidator.isWithinRange(range, "0"))
    assertTrue(FormNumericRangeValidator.isWithinRange(range, "1000"))
  }
}
