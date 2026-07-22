package org.armman.sakhi.data.forms

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "form_active_version_"

/**
 * Real [FormsRepository] backed by `GET /forms/:formCode/active-version`.
 *
 * Same resilience shape as [org.armman.sakhi.data.lookup.RemoteLookupRepository]: caches each
 * formCode's version in memory for the process lifetime, and persists it to
 * [SecureKeyValueStore] so a version fetched once is still available offline — an enrollment
 * started offline needs *some* form to render, even if it's not the newest published version.
 *
 * Always prefers a fresh live fetch over the cache (no manual "check for updates" step) — this is
 * what makes a form update from ARMMAN reach the Sakhi automatically the next time she opens the
 * enrollment screen with connectivity, per SRS FR-S-4.5.
 */
@Singleton
class RemoteFormsRepository @Inject constructor(
  private val formsApi: FormsApi,
  private val store: SecureKeyValueStore,
) : FormsRepository {

  private val gson = Gson()
  private val mutex = Mutex()
  private val cachedByFormCode = mutableMapOf<String, FormVersion>()

  override suspend fun getActiveVersion(formCode: String): FormVersion? = mutex.withLock {
    val fetched = fetchActiveVersion(formCode)
    if (fetched != null) {
      store.putString(KEY_PREFIX + formCode, gson.toJson(fetched))
      cachedByFormCode[formCode] = fetched
      return fetched
    }

    // Live fetch failed (offline, 401, form not published yet, etc.) — fall back to memory, then
    // to whatever was last persisted, in that order.
    cachedByFormCode[formCode]?.let { return it }
    val persisted = readPersistedVersion(formCode)
    if (persisted != null) cachedByFormCode[formCode] = persisted
    persisted
  }

  private fun readPersistedVersion(formCode: String): FormVersion? {
    val json = store.getString(KEY_PREFIX + formCode) ?: return null
    return try {
      gson.fromJson(json, FormVersion::class.java)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  private suspend fun fetchActiveVersion(formCode: String): FormVersion? = try {
    formsApi.getActiveVersion(formCode)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
  } catch (e: Exception) {
    // Offline, timeout, 404 (form not published yet), malformed body — all treated the same:
    // nothing new to cache, fall back to whatever's persisted/in-memory.
    null
  }
}
