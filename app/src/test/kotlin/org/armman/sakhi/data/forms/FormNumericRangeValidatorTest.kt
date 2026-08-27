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

  @Test
  fun `exceedsMax rejects a right-length value already above max - the reported bug`() {
    // Spec row 33: household members, range 2..15. "16" needs only 2 digits, same as the valid
    // "15", so the digit cap alone lets it through — this is what the renderer now blocks on.
    val range = FormNumericRange(min = 2.0, max = 15.0)
    assertTrue(FormNumericRangeValidator.exceedsMax(range, "16"))
    assertTrue(FormNumericRangeValidator.exceedsMax(range, "99"))
  }

  @Test
  fun `exceedsMax accepts the max boundary and everything below it`() {
    val range = FormNumericRange(min = 2.0, max = 15.0)
    assertFalse(FormNumericRangeValidator.exceedsMax(range, "15"))
    assertFalse(FormNumericRangeValidator.exceedsMax(range, "2"))
    assertFalse(FormNumericRangeValidator.exceedsMax(range, "1"))
  }

  @Test
  fun `exceedsMax never blocks a value on the way to a legitimate entry`() {
    // Every prefix typed en route to "15" (i.e. "1") is <= 15, so exceedsMax must stay false for
    // all of them - this is the property that makes blocking on max safe at keystroke time.
    val range = FormNumericRange(min = 2.0, max = 15.0)
    "15".indices.forEach { i ->
      val prefix = "15".substring(0, i + 1)
      assertFalse(FormNumericRangeValidator.exceedsMax(range, prefix))
    }
  }

  @Test
  fun `exceedsMax with no max declared never blocks`() {
    assertFalse(FormNumericRangeValidator.exceedsMax(null, "999999"))
    assertFalse(FormNumericRangeValidator.exceedsMax(FormNumericRange(min = 1.0, max = null), "999999"))
  }

  @Test
  fun `exceedsMax treats unparseable text as not exceeding - not this function's job`() {
    val range = FormNumericRange(min = 2.0, max = 15.0)
    assertFalse(FormNumericRangeValidator.exceedsMax(range, ""))
    assertFalse(FormNumericRangeValidator.exceedsMax(range, "not-a-number"))
  }
}
