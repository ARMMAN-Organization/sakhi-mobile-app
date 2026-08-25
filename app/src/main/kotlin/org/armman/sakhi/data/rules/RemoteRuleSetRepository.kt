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
 * Real [RuleSetRepository] backed by rules-service's non-admin, SAKHI-accessible rule-pack sync
 * endpoints (see [RuleSetApi]'s class doc for the confirmed contract and what this replaces).
 *
 * Rewritten 2026-08-24 to fix two confirmed bugs in the previous implementation: SCHEDULE rule
 * sync called an ADMIN-only endpoint (permanent 403 for SAKHI), and RISK rule sync called an
 * endpoint that deliberately never returns `rulesJson` (a silent stub response, not an error).
 * Both categories now go through the same resolve-version-then-fetch-content flow, cached by
 * rule *set* id only — there is no longer a separate fetch-by-fixed-version-id path.
 *
 * ⚠ Cache-key change: content is now persisted under a key derived from the rule *set* id only
 * (previously RISK sets were persisted under a `rule_version_<versionId>` key). Any content
 * persisted by the pre-2026-08-24 build is simply not found under the new keys and is
 * re-fetched once, live — expected and harmless, not a migration bug, since that old persisted
 * RISK content was never real `rulesJson` to begin with (see [RuleSetApi]'s doc).
 *
 * Same resilience shape as before: caches each rule set's content in memory for the process
 * lifetime, and persists it to [SecureKeyValueStore] so a rule fetched once is still available
 * offline. Always prefers a fresh live check over the cache (no manual "check for updates" step),
 * but a `published-version` resolution matching what's already cached skips the `content` call
 * entirely — content is only re-downloaded when the published version actually changes.
 */
@Singleton
class RemoteRuleSetRepository @Inject constructor(
  private val ruleSetApi: RuleSetApi,
  private val store: SecureKeyValueStore,
) : RuleSetRepository {

  private val mutex = Mutex()
  private val cachedInMemory = mutableMapOf<String, CachedRuleSet>()

  override suspend fun getPublishedRuleSet(ruleSetId: String): CachedRuleSet? = mutex.withLock {
    val existing = cachedInMemory[ruleSetId] ?: readPersisted(ruleSetId)
    val resolved = try {
      resolveAndFetch(ruleSetId, existing)
    } catch (e: Exception) {
      Log.w(TAG, "RemoteRuleSetRepository.getPublishedRuleSet($ruleSetId): threw ${e::class.simpleName} — ${e.message}")
      null
    }
    if (resolved != null) {
      persist(ruleSetId, resolved)
      cachedInMemory[ruleSetId] = resolved
      return@withLock resolved
    }
    // Live path failed end-to-end (offline, 404, malformed body, resolved version had no content,
    // etc.) — fall back to whatever's cached, same "best-effort, never block the caller" contract
    // as before this rewrite.
    existing?.also { cachedInMemory[ruleSetId] = it }
  }

  override suspend fun prefetchRuleSets(ruleSetIds: List<String>) {
    if (ruleSetIds.isEmpty()) return
    mutex.withLock {
      try {
        val response = ruleSetApi.getPublishedContentBatch(ruleSetIds.joinToString(","))
        if (!response.isSuccessful) {
          Log.w(
            TAG,
            "RemoteRuleSetRepository.prefetchRuleSets: HTTP ${response.code()} — ${response.errorBody()?.string()}",
          )
          return@withLock
        }
        val items = response.body()?.takeIf { it.success }?.data.orEmpty()
        // An id absent from `items` means "nothing published for it right now" — confirmed batch
        // contract, silent, not an error. Leave that id's existing cache exactly as it was.
        for (item in items) {
          if (item.status != "PUBLISHED") continue
          val cached = CachedRuleSet(item.ruleSetId, item.versionId, item.versionNo, item.rulesJson)
          persist(item.ruleSetId, cached)
          cachedInMemory[item.ruleSetId] = cached
        }
      } catch (e: Exception) {
        Log.w(TAG, "RemoteRuleSetRepository.prefetchRuleSets: threw ${e::class.simpleName} — ${e.message}")
      }
    }
  }

  /**
   * Resolves the currently PUBLISHED versionId for [ruleSetId] and returns fresh content only if
   * it differs from [existing]'s cached version — otherwise returns [existing] unchanged, so an
   * unchanged published version costs one HTTP call, not two. Returns null if either call fails,
   * nothing is published, or the resolved version's content isn't PUBLISHED by the time it's
   * fetched (a retire-between-calls race — legitimate per the confirmed contract, not an error).
   */
  private suspend fun resolveAndFetch(ruleSetId: String, existing: CachedRuleSet?): CachedRuleSet? {
    val versionResponse = ruleSetApi.getPublishedVersionId(ruleSetId)
    if (!versionResponse.isSuccessful) {
      Log.w(TAG, "RemoteRuleSetRepository.resolveAndFetch($ruleSetId): published-version HTTP ${versionResponse.code()}")
      return null
    }
    val versionId = versionResponse.body()?.takeIf { it.success }?.data?.versionId ?: return null
    if (existing != null && existing.ruleVersionId == versionId) return existing

    val contentResponse = ruleSetApi.getRuleVersionContent(versionId)
    if (!contentResponse.isSuccessful) {
      Log.w(TAG, "RemoteRuleSetRepository.resolveAndFetch($ruleSetId): content HTTP ${contentResponse.code()}")
      return null
    }
    val content = contentResponse.body()?.takeIf { it.success }?.data ?: return null
    if (content.status != "PUBLISHED") return null
    return CachedRuleSet(content.ruleSetId, content.id, content.versionNo, content.rulesJson)
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
