package org.armman.sakhi.data.auth

/** Configurable fake — returns [profile] as-is, including null for "not available yet".
 * The [displayName] constructor param is a convenience for tests that only care about the name
 * (username defaults to "fake.sakhi"); set [profile] directly for tests that need the other
 * fields, or a specific username, too. [clearCallCount]/[clearIfDifferentUserCalls] let tests
 * assert the right invalidation path ran. */
class FakeCurrentUserRepository(displayName: String? = "Fake Name") : CurrentUserRepository {
  var profile: CurrentUserProfile? = displayName?.let {
    CurrentUserProfile(
      username = "fake.sakhi",
      displayName = it,
      mobileNumber = null,
      projectName = null,
      cardNumber = null,
      maskedBankAccount = null,
    )
  }
  var clearCallCount = 0
  var clearIfDifferentUserCalls = mutableListOf<String>()

  override suspend fun getProfile(): CurrentUserProfile? = profile

  override fun clear() {
    clearCallCount++
    profile = null
  }

  override fun clearIfDifferentUser(username: String) {
    clearIfDifferentUserCalls.add(username)
    if (profile != null && profile?.username != username) {
      clear()
    }
  }
}
