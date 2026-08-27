package org.armman.sakhi.ui.enrollment.steps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isCheckboxOptionEnabled] backs the Health History checkbox groups' greyed-out state
 * (Q43/Q52/Q58/Q59/Q61) — the bug this covers: the "No" / "Don't know" row and a real answer
 * row could both render checked with no visual cue that they conflict (see the substance-use
 * question, Q61). [org.armman.sakhi.ui.enrollment.EnrollmentViewModel.toggleMulti] already
 * clears the conflicting code on toggle; this is the matching UI-side disable so the exclusion
 * doesn't rely on the user noticing another box uncheck itself.
 */
class HealthHistoryStepTest {

  private val substanceExclusive = setOf(1, 7) // "No" / "Don't know"

  @Test
  fun `no codes selected keeps every option enabled`() {
    assertTrue(isCheckboxOptionEnabled(code = 1, codes = emptySet(), exclusiveCodes = substanceExclusive))
    assertTrue(isCheckboxOptionEnabled(code = 2, codes = emptySet(), exclusiveCodes = substanceExclusive))
  }

  @Test
  fun `selecting No disables every other option`() {
    val codes = setOf(1)
    assertFalse(isCheckboxOptionEnabled(code = 2, codes = codes, exclusiveCodes = substanceExclusive))
    assertFalse(isCheckboxOptionEnabled(code = 3, codes = codes, exclusiveCodes = substanceExclusive))
    // The other exclusive code ("Don't know") is also disabled while "No" is checked.
    assertFalse(isCheckboxOptionEnabled(code = 7, codes = codes, exclusiveCodes = substanceExclusive))
  }

  @Test
  fun `No itself stays enabled so it can be unchecked`() {
    assertTrue(isCheckboxOptionEnabled(code = 1, codes = setOf(1), exclusiveCodes = substanceExclusive))
  }

  @Test
  fun `selecting a real answer disables No and Don't know`() {
    val codes = setOf(2, 3) // chewing + smoking tobacco
    assertFalse(isCheckboxOptionEnabled(code = 1, codes = codes, exclusiveCodes = substanceExclusive))
    assertFalse(isCheckboxOptionEnabled(code = 7, codes = codes, exclusiveCodes = substanceExclusive))
    // Other real answers remain selectable alongside each other.
    assertTrue(isCheckboxOptionEnabled(code = 4, codes = codes, exclusiveCodes = substanceExclusive))
  }

  @Test
  fun `a checked code stays enabled even if it would otherwise be disabled`() {
    // Defensive: if bad data ever puts an exclusive and a normal code in the same set
    // (e.g. a pre-fix legacy record), the checked rows must stay tappable so the user
    // can actually resolve the conflict rather than being locked out of both.
    val codes = setOf(1, 2)
    assertTrue(isCheckboxOptionEnabled(code = 1, codes = codes, exclusiveCodes = substanceExclusive))
    assertTrue(isCheckboxOptionEnabled(code = 2, codes = codes, exclusiveCodes = substanceExclusive))
  }

  @Test
  fun `empty exclusive set never disables anything (eg family conditions)`() {
    assertTrue(isCheckboxOptionEnabled(code = 1, codes = setOf(3, 4), exclusiveCodes = emptySet()))
  }
}
