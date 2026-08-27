package org.armman.sakhi.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit coverage for the logout-banner visibility rules in [LoginBannerLogic.kt].
 * Verifies the fix for the banner persisting on the Login screen until force-close.
 */
class LoginBannerLogicTest {

  // --- shouldShowLogoutBanner --------------------------------------------------------------------

  @Test
  fun `banner shows when requested and not dismissed`() {
    assertTrue(shouldShowLogoutBanner(requested = true, dismissed = false))
  }

  @Test
  fun `banner hidden once dismissed even while still requested`() {
    // Dismissal (timeout or interaction) wins over the persistent nav argument — this is the bug fix.
    assertFalse(shouldShowLogoutBanner(requested = true, dismissed = true))
  }

  @Test
  fun `banner hidden when not requested`() {
    assertFalse(shouldShowLogoutBanner(requested = false, dismissed = false))
    assertFalse(shouldShowLogoutBanner(requested = false, dismissed = true))
  }

  // --- userHasInteractedWithLoginForm ------------------------------------------------------------

  @Test
  fun `no interaction when both fields empty`() {
    assertFalse(userHasInteractedWithLoginForm(username = "", password = ""))
  }

  @Test
  fun `interaction detected when username typed`() {
    assertTrue(userHasInteractedWithLoginForm(username = "a", password = ""))
  }

  @Test
  fun `interaction detected when password typed`() {
    assertTrue(userHasInteractedWithLoginForm(username = "", password = "x"))
  }

  @Test
  fun `interaction detected when both fields have input`() {
    assertTrue(userHasInteractedWithLoginForm(username = "user", password = "pass"))
  }
}
