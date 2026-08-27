package org.armman.sakhi.data.forms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNumberRuleTest {

  @Test
  fun `exactly 10 digits is complete`() {
    assertTrue(MobileNumberRule.isComplete("6382325824"))
  }

  @Test
  fun `fewer or more than 10 digits is not complete`() {
    assertFalse(MobileNumberRule.isComplete("63823"))
    assertFalse(MobileNumberRule.isComplete("638232582411"))
  }

  @Test
  fun `null or blank is not complete`() {
    assertFalse(MobileNumberRule.isComplete(null))
    assertFalse(MobileNumberRule.isComplete(""))
  }

  @Test
  fun `non-digits are not complete`() {
    assertFalse(MobileNumberRule.isComplete("63823 5824"))
    assertFalse(MobileNumberRule.isComplete("63823a5824"))
  }
}
