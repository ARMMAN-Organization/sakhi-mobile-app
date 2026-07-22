package org.armman.sakhi.data.auth

/**
 * Snapshot of the authenticated caller's profile from `/me`, limited to the fields the app
 * currently renders (Dashboard's Sakhi name; Profile's name/project/card/mobile/bank fields).
 * [displayName] is the only field `/me` always returns; the rest are nullable because a given
 * account may not have them set server-side yet — callers fall back to their own default per
 * field. There is still no `sakhiId`-equivalent field on `/me`, so
 * [org.armman.sakhi.data.profile.SakhiProfile.sakhiId] can't be sourced from here.
 */
data class CurrentUserProfile(
  val username: String,
  val displayName: String,
  val mobileNumber: String?,
  val projectName: String?,
  val cardNumber: String?,
  val maskedBankAccount: String?,
)

/**
 * Boundary for the authenticated caller's live profile (`/me`). Deliberately separate from
 * [AuthRepository] — this is best-effort profile enrichment, not part of the auth-critical
 * login/session path, and other screens (Dashboard, Profile) share it so they never drift
 * out of sync with independent copies of the same data.
 */
interface CurrentUserRepository {
  /**
   * Returns the caller's profile from `/me`, or `null` if it has never been fetched successfully
   * (first launch, offline with no prior fetch, 401, 5xx, malformed body). Never throws — callers
   * are expected to fall back to their own defaults, per field, when `null` comes back.
   *
   * A profile fetched while online is persisted, so a later call made while offline (including
   * after an app restart, or after a logout/re-login by the *same* Sakhi) still returns the last
   * successfully fetched profile instead of `null` — logging out must not discard this, since the
   * same Sakhi logging back in offline needs it to still be there.
   */
  suspend fun getProfile(): CurrentUserProfile?

  /**
   * Clears any cached/persisted profile, unconditionally. Exposed for completeness (e.g. a future
   * "switch device" flow); ordinary logout should use [clearIfDifferentUser] instead — see there
   * for why.
   */
  fun clear()

  /**
   * Clears the cached/persisted profile only if it belongs to a *different* username than
   * [username]. Call this right after a successful login (online or offline), not on logout: a
   * device belongs to one Sakhi at a time, and she routinely logs out/back in while offline, where
   * there's no way to re-fetch `/me` — unconditionally clearing on logout would strand her without
   * a name until connectivity returns. This still protects the rare case of the device being
   * reassigned to a different Sakhi.
   */
  fun clearIfDifferentUser(username: String)
}
