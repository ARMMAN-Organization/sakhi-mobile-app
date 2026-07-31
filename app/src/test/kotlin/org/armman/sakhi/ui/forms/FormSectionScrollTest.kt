package org.armman.sakhi.ui.forms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSectionScrollTest {

  @Test
  fun `resets to top when no error scroll is pending`() {
    assertTrue(FormSectionScroll.shouldResetToTop(hasPendingErrorScroll = false))
  }

  @Test
  fun `does not reset to top while an error scroll is pending`() {
    // The post-submit jump to the flagged field fires on the same tab switch; resetting here would
    // cancel it and leave the Sakhi with no visible reason for the failed submit.
    assertFalse(FormSectionScroll.shouldResetToTop(hasPendingErrorScroll = true))
  }
}
