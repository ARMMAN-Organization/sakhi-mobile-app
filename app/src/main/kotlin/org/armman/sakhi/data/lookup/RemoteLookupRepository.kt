package org.armman.sakhi.data.lookup

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "lookup_values_"

/**
 * Real [LookupRepository] backed by `GET /lookups/:categoryCode`.
 *
 * Same resilience shape as [org.armman.sakhi.data.auth.RemoteCurrentUserRepository]: caches each
 * category in memory for the process lifetime, and persists it to [SecureKeyValueStore] so a
 * category fetched once is still available offline (including after an app restart) — the
 * Enrollment form must be fillable without connectivity, and `beneficiaryTypeLookupId`/
 * `caseTypeLookupId` are required fields on submission, so there must be *something* to resolve
 * them from even with no network.
 *
 * Caveat: if a category has never been fetched successfully at all (fresh install, always
 * offline), there is nothing to fall back to and [getValues] returns empty — callers must handle
 * that (e.g. block submission with a clear "couldn't load categories, try again online" message).
 * A follow-up should prefetch known categories eagerly (e.g. at login) so they're warm before a
 * Sakhi ever starts an enrollment offline; not done here to keep this change scoped to the API
 * integration itself.
 */
@Singleton
class RemoteLookupRepository @Inject constructor(
  private val lookupApi: LookupApi,
  private val store: SecureKeyValueStore,
) : LookupRepository {

  private val gson = Gson()
  private val mutex = Mutex()
  private val cachedByCategory = mutableMapOf<String, List<LookupValue>>()

  override suspend fun getValues(categoryCode: String): List<LookupValue> {
    cachedByCategory[categoryCode]?.let { return it }
    return mutex.withLock {
      cachedByCategory[categoryCode]?.let { return it }

      val fetched = fetchValues(categoryCode)
      if (fetched != null) {
        store.putString(KEY_PREFIX + categoryCode, gson.toJson(fetched))
        cachedByCategory[categoryCode] = fetched
        return fetched
      }

      // Live fetch failed (offline, 401, weak network, category not seeded, etc.). Fall back to a
      // prior successful fetch if one was persisted — and cache that, it's real data. But if there
      // is NOTHING persisted, return empty WITHOUT caching it: caching an empty result here would
      // poison the in-memory cache for the whole process, so a later call (login/reconnect prefetch,
      // or a retry once the network recovers) would keep getting empty instead of re-fetching. This
      // is exactly what left `caseTypeLookupId` unresolvable at submit on a flaky network.
      val persisted = readPersistedValues(categoryCode)
      if (persisted != null) {
        cachedByCategory[categoryCode] = persisted
        return persisted
      }
      emptyList()
    }
  }

  private fun readPersistedValues(categoryCode: String): List<LookupValue>? {
    val json = store.getString(KEY_PREFIX + categoryCode) ?: return null
    return try {
      val type = object : TypeToken<List<LookupValue>>() {}.type
      gson.fromJson<List<LookupValue>>(json, type)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  private suspend fun fetchValues(categoryCode: String): List<LookupValue>? = try {
    lookupApi.getCategory(categoryCode)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      ?.values
      ?.filter { it.isActive }
      ?.sortedBy { it.sortOrder }
      ?.map { LookupValue(id = it.id, valueCode = it.valueCode, valueLabel = it.valueLabel) }
  } catch (e: Exception) {
    // Offline, timeout, 404 (category doesn't exist yet), malformed body — all treated the same:
    // nothing new to cache, fall back to whatever's persisted.
    null
  }
}
