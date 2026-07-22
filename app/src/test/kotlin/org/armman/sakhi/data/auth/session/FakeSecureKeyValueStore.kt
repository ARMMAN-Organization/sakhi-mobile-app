package org.armman.sakhi.data.auth.session

/** In-memory fake so [SessionStore] and [OfflineCredentialCache] are unit-testable without the
 * real Android Keystore-backed implementation (this repo has no Robolectric/instrumented test
 * setup). Shared across their test files plus [org.armman.sakhi.data.auth.RemoteAuthRepositoryTest]
 * and [org.armman.sakhi.ui.login.LoginViewModelTest], which exercise both classes together. */
class FakeSecureKeyValueStore : SecureKeyValueStore {
  private val values = mutableMapOf<String, String>()

  /** Test-only fault injection: when set, a `putString` for this key throws instead of writing,
   * to simulate a persistence failure mid-way through a multi-write flow (e.g. the atomic
   * session + offline-cache write in RemoteAuthRepository). */
  var failOnPutKey: String? = null

  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    if (key == failOnPutKey) throw RuntimeException("Simulated persistence failure for key=$key")
    values[key] = value
  }

  override fun remove(key: String) {
    values.remove(key)
  }

  /** Test-only escape hatch to simulate a corrupted/unreadable store (SS-5). */
  fun putRawCorrupted(key: String) {
    values[key] = "{not valid json"
  }
}
