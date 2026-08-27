package org.armman.sakhi.data.forms

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.schedule.VisitCodeType
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_VISIT_CODE_FORM_MAP = "visit_code_form_map"

/** Temporary diagnostic tag — same convention as [RemoteFormsRepository]'s TAG. */
private const val TAG = "SakhiSync"

/**
 * Hardcoded fallback, used only if the backend's `visit-code-form-map` has never been fetched
 * successfully (first run offline, or the endpoint errors/404s) — matches the two form codes that
 * were hardcoded in [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel] before CR-033/
 * CR-034. Values are literal strings, not references to `FORM_CODE_MOTHER`/`FORM_CODE_INFANT`
 * (which live in the `ui.visitform` package) — this is the data layer, and a data->ui import
 * would invert the app's dependency direction for the sake of avoiding two duplicated string
 * literals. Keep in sync with those constants if either ever changes.
 *
 * PP/INC/CCV/HR entries here are placeholders pending backend content, exactly as described in
 * `docs/test-cases/visit-form.md` (CR-033 — INC/CCV schema is a placeholder copy of INFANT_VISIT's;
 * CR-034 — every `*_HR` code routes to its base visit's form). Bug fix (2026-08-22): INC/CCV used
 * to alias "INFANT_VISIT" here, but the backend's live map now returns the distinct "INC_VISIT"/
 * "CCV_VISIT" codes for them — updated to match. See
 * [org.armman.sakhi.ui.visitform.FORM_CODES_INFANT_FAMILY] for where the app still treats all
 * three as one family until real INC/CCV-specific schemas ship.
 */
private val FALLBACK_MAP: Map<String, String> = mapOf(
  "ANC" to "ANC_VISIT",
  "ANC_HR" to "ANC_VISIT",
  "ANC_POST_EDD" to "ANC_VISIT",
  "DELIVERY" to "DELIVERY_VISIT",
  "PP" to "POSTPARTUM_VISIT",
  "NN" to "NEONATAL_VISIT",
  "INC" to "INC_VISIT",
  "INC_HR" to "INC_VISIT",
  "CCV" to "CCV_VISIT",
  "CCV_HR" to "CCV_VISIT",
)

private val MAP_TYPE = object : TypeToken<Map<String, String>>() {}.type

/**
 * Resolves a scheduled visit's [VisitCodeType] to the `formCode` string
 * [FormsApi.getActiveVersion]/[FormSubmissionApi.createSubmission] expect, via the backend's
 * `GET /forms/visit-code-form-map` (CR-033/CR-034).
 *
 * Same resilience shape as [RemoteFormsRepository]: always prefers a fresh live fetch, caches it
 * in memory for the process lifetime, and persists it so a map fetched once still resolves
 * offline. Never throws and [resolve] never returns null — a visit form must always have
 * *something* to load, so an unmapped/unknown [VisitCodeType] falls through to [FALLBACK_MAP],
 * then to `"ANC_VISIT"` as the last resort.
 */
@Singleton
class VisitCodeFormResolver @Inject constructor(
  private val formsApi: FormsApi,
  private val store: SecureKeyValueStore,
) {
  private val gson = Gson()
  private val mutex = Mutex()
  private var cachedMap: Map<String, String>? = null

  suspend fun resolve(visitCode: VisitCodeType): String {
    val map = currentMap()
    return map[visitCode.name] ?: FALLBACK_MAP[visitCode.name] ?: "ANC_VISIT"
  }

  private suspend fun currentMap(): Map<String, String> = mutex.withLock {
    val fetched = fetchMap()
    if (fetched != null) {
      store.putString(KEY_VISIT_CODE_FORM_MAP, gson.toJson(fetched))
      cachedMap = fetched
      return fetched
    }
    cachedMap?.let { return it }
    val persisted = readPersistedMap()
    if (persisted != null) cachedMap = persisted
    persisted ?: FALLBACK_MAP
  }

  private fun readPersistedMap(): Map<String, String>? {
    val json = store.getString(KEY_VISIT_CODE_FORM_MAP) ?: return null
    return try {
      gson.fromJson<Map<String, String>>(json, MAP_TYPE)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  private suspend fun fetchMap(): Map<String, String>? = try {
    val response = formsApi.getVisitCodeFormMap()
    if (!response.isSuccessful) {
      Log.w(TAG, "VisitCodeFormResolver.fetchMap(): HTTP ${response.code()} — ${response.errorBody()?.string()}")
    }
    response.takeIf { it.isSuccessful }?.body()?.takeIf { it.success }?.data
  } catch (e: Exception) {
    Log.w(TAG, "VisitCodeFormResolver.fetchMap(): threw ${e::class.simpleName} — ${e.message}")
    null
  }
}
