package org.armman.sakhi.ui.login

/**
 * Pure presentation rules for the Login screen's "logged out successfully" banner, extracted
 * from [LoginScreen] so they're testable without a Compose UI test harness (this repo has none —
 * no `createComposeRule` usage exists anywhere; see FormUploadStatusPresentation.kt for the same
 * pattern). The 4s auto-dismiss timer itself lives in the composable as a coroutine side-effect
 * and is not covered here.
 */

/**
 * Whether the logout success banner should currently render.
 *
 * @param requested came from the `loggedOut` nav argument — true only right after a logout.
 * @param dismissed set once the banner has been dismissed (timeout elapsed or user interacted);
 *   dismissal is sticky so the banner never reappears.
 */
fun shouldShowLogoutBanner(requested: Boolean, dismissed: Boolean): Boolean =
  requested && !dismissed

/**
 * Whether the user has started interacting with the login form, which should immediately dismiss
 * the logout banner. Any typed character in either field counts as interaction.
 */
fun userHasInteractedWithLoginForm(username: String, password: String): Boolean =
  username.isNotEmpty() || password.isNotEmpty()
