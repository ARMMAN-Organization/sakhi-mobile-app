package org.armman.sakhi.data.auth

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_CURRENT_USER_PROFILE_JSON = "current_user_profile_json"

/**
 * Real [CurrentUserRepository] backed by `GET /me`.
 *
 * Caches the result in memory for the process lifetime — Dashboard and Profile both read
 * through this same singleton, so they issue one network call between them instead of two, and
 * always agree on the data. The profile is also persisted to [SecureKeyValueStore] as JSON: a
 * live fetch is always attempted first (so it stays fresh whenever the network is up), but if
 * that fetch fails — offline, timeout, 401, etc. — the last successfully fetched profile is read
 * back from disk instead of forcing the caller's static placeholders, including across an app
 * restart while offline — and across a logout/re-login by the same Sakhi, which is the common
 * case on a device that belongs to one Sakhi. [clearIfDifferentUser] is what actually runs on
 * login; see its doc for why [clear] is not called from logout.
 */
@Singleton
class RemoteCurrentUserRepository @Inject constructor(
  private val authApi: AuthApi,
  private val store: SecureKeyValueStore,
) : CurrentUserRepository {

  private val gson = Gson()
  private val mutex = Mutex()
  private var cachedProfile: CurrentUserProfile? = null

  override suspend fun getProfile(): CurrentUserProfile? {
    cachedProfile?.let { return it }
    return mutex.withLock {
      // Re-check: another caller may have populated the cache while we waited for the lock.
      cachedProfile?.let { return it }

      val fetched = fetchProfile()
      if (fetched != null) {
        store.putString(KEY_CURRENT_USER_PROFILE_JSON, gson.toJson(fetched))
        cachedProfile = fetched
        return fetched
      }

      // Live fetch failed — fall back to the last profile persisted from a prior successful
      // fetch, rather than treating "network unreachable right now" the same as "never fetched".
      val persisted = readPersistedProfile()
      cachedProfile = persisted
      persisted
    }
  }

  override fun clear() {
    cachedProfile = null
    store.remove(KEY_CURRENT_USER_PROFILE_JSON)
  }

  override fun clearIfDifferentUser(username: String) {
    val cachedUsername = cachedProfile?.username ?: readPersistedProfile()?.username
    if (cachedUsername != null && cachedUsername != username) {
      clear()
    }
  }

  /** Returns null if nothing was ever persisted, or the stored value is corrupted — treated the
   * same as "never fetched", never crashes. */
  private fun readPersistedProfile(): CurrentUserProfile? {
    val json = store.getString(KEY_CURRENT_USER_PROFILE_JSON) ?: return null
    return try {
      gson.fromJson(json, CurrentUserProfile::class.java)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  private suspend fun fetchProfile(): CurrentUserProfile? = try {
    val data = authApi.getMe()
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      ?: return null
    val displayName = data.displayName.takeIf { it.isNotBlank() } ?: return null
    CurrentUserProfile(
      username = data.username,
      displayName = displayName,
      mobileNumber = data.mobileNumber?.takeIf { it.isNotBlank() },
      projectName = data.projectName?.takeIf { it.isNotBlank() },
      cardNumber = data.cardNumber?.takeIf { it.isNotBlank() },
      maskedBankAccount = data.maskedBankAccount?.takeIf { it.isNotBlank() },
    )
  } catch (e: Exception) {
    // Offline, timeout, malformed body, etc. — profile enrichment is best-effort; the caller
    // falls back to the persisted or static default rather than surfacing an error.
    null
  }
}
