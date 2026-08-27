package org.armman.sakhi.data.rules

import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "rule_set_published_"
private const val TAG = "SakhiSync"

/**
 * Real [RuleSetRepository] backed by `GET /admin/rules/:setId`.
 *
 * Same resilience shape as [org.armman.sakhi.data.forms.RemoteFormsRepository] and
 * [org.armman.sakhi.data.lookup.RemoteLookupRepository]: caches each rule set's content in memory
 * for the process lifetime, and persists it to [SecureKeyValueStore] so a rule fetched once is
 * still available offline — a schedule generated offline needs *some* rule content, even if it's
 * not the newest published version.
 *
 * Always prefers a fresh live fetch over the cache (no manual "check for updates" step) — a rule
 * published by ARMMAN reaches the Sakhi automatically next time she has connectivity, same as a
 * form-schema update.
 */
@Singleton
class RemoteRuleSetRepository @Inject constructor(
  private val ruleSetApi: RuleSetApi,
  private val store: SecureKeyValueStore,
) : RuleSetRepository {

  private val mutex = Mutex()
  private val cachedInMemory = mutableMapOf<String, CachedRuleSet>()

  override suspend fun getPublishedRuleSet(ruleSetId: String): CachedRuleSet? = mutex.withLock {
    val fetched = fetchPublishedRuleSet(ruleSetId)
    if (fetched != null) {
      persist(ruleSetId, fetched)
      cachedInMemory[ruleSetId] = fetched
      return fetched
    }

    // Live fetch failed (offline, 401, rule set not published yet, etc.) — fall back to memory,
    // then to whatever was last persisted, in that order.
    cachedInMemory[ruleSetId]?.let { return it }
    val persisted = readPersisted(ruleSetId)
    if (persisted != null) cachedInMemory[ruleSetId] = persisted
    persisted
  }

  private suspend fun fetchPublishedRuleSet(ruleSetId: String): CachedRuleSet? = try {
    val response = ruleSetApi.getPublishedVersion(ruleSetId)
    if (!response.isSuccessful) {
      Log.w(
        TAG,
        "RemoteRuleSetRepository.fetchPublishedRuleSet($ruleSetId): " +
          "HTTP ${response.code()} — ${response.errorBody()?.string()}",
      )
    }
    response
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      // The endpoint only ever returns the currently-published version, but a defensive check
      // here costs nothing and documents the assumption the rest of this class relies on.
      ?.takeIf { it.status == "PUBLISHED" }
      ?.let { CachedRuleSet(it.ruleSetId, it.id, it.versionNo, it.rulesJson) }
  } catch (e: Exception) {
    // Offline, timeout, 404 (rule set unknown or not published — e.g. an environment that hasn't
    // run the seed script yet, see RuleSetIds's doc), malformed body — all treated the same:
    // nothing new to cache, fall back to whatever's persisted/in-memory.
    Log.w(
      TAG,
      "RemoteRuleSetRepository.fetchPublishedRuleSet($ruleSetId): " +
        "threw ${e::class.simpleName} — ${e.message}",
    )
    null
  }

  private fun persist(ruleSetId: String, ruleSet: CachedRuleSet) {
    store.putString(versionIdKey(ruleSetId), ruleSet.ruleVersionId)
    store.putString(versionNoKey(ruleSetId), ruleSet.versionNo)
    store.putString(jsonKey(ruleSetId), ruleSet.rulesJson.toString())
  }

  private fun readPersisted(ruleSetId: String): CachedRuleSet? {
    val ruleVersionId = store.getString(versionIdKey(ruleSetId)) ?: return null
    val versionNo = store.getString(versionNoKey(ruleSetId)) ?: return null
    val json = store.getString(jsonKey(ruleSetId)) ?: return null
    return try {
      CachedRuleSet(ruleSetId, ruleVersionId, versionNo, JsonParser.parseString(json).asJsonObject)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  private fun versionIdKey(ruleSetId: String) = KEY_PREFIX + ruleSetId + "_version_id"
  private fun versionNoKey(ruleSetId: String) = KEY_PREFIX + ruleSetId + "_version_no"
  private fun jsonKey(ruleSetId: String) = KEY_PREFIX + ruleSetId + "_json"
}
