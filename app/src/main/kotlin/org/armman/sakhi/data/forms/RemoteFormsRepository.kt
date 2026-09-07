package org.armman.sakhi.data.forms

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "form_active_version_"

/** Temporary diagnostic tag for the "couldn't load this visit's data" report (CR-026
 * debugging) — fetchActiveVersion() swallows every exception into a bare null, so there was
 * no way to tell offline/timeout/401/404/malformed-body apart from a bug report alone. */
private const val TAG = "SakhiSync"

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

  // registerTypeAdapter(FormVisibleWhen) — see FormVisibleWhenDeserializer's own doc: makes a
  // malformed visibleWhen value (seen as a JSON array in real ANC_VISIT content) degrade to
  // null instead of throwing and failing the WHOLE schemaJson parse.
  private val gson = GsonBuilder()
    .registerTypeAdapter(FormVisibleWhen::class.java, FormVisibleWhenDeserializer())
    .create()
  private val mutex = Mutex()
  private val cachedByFormCode = mutableMapOf<String, FormVersion>()

  override suspend fun getActiveVersion(formCode: String): FormVersion? = mutex.withLock {
    val fetched = fetchActiveVersion(formCode)
    if (fetched != null) {
      val patched = KnownSchemaGapPatch.apply(formCode, fetched)
      store.putString(KEY_PREFIX + formCode, gson.toJson(patched))
      cachedByFormCode[formCode] = patched
      return patched
    }

    // Live fetch failed (offline, 401, form not published yet, etc.) — fall back to memory, then
    // to whatever was last persisted, in that order. Both are re-patched on the way out too: the
    // in-memory/persisted copy may predate this patch shipping (an older cached version), and
    // KnownSchemaGapPatch.apply is a no-op once ARMMAN actually fixes the schema server-side (see
    // its own doc), so re-applying here on every read is cheap and never double-patches.
    cachedByFormCode[formCode]?.let { return KnownSchemaGapPatch.apply(formCode, it) }
    val persisted = readPersistedVersion(formCode)
    if (persisted != null) cachedByFormCode[formCode] = persisted
    persisted?.let { KnownSchemaGapPatch.apply(formCode, it) }
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
    val response = formsApi.getActiveVersion(formCode)
    if (!response.isSuccessful) {
      Log.w(TAG, "RemoteFormsRepository.fetchActiveVersion($formCode): HTTP ${response.code()} — ${response.errorBody()?.string()}")
    }
    response
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
  } catch (e: Exception) {
    // Offline, timeout, 404 (form not published yet), malformed body — all treated the same:
    // nothing new to cache, fall back to whatever's persisted/in-memory.
    Log.w(TAG, "RemoteFormsRepository.fetchActiveVersion($formCode): threw ${e::class.simpleName} — ${e.message}")
    null
  }
}
