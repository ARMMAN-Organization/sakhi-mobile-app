package org.armman.sakhi.data.visittracker

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PADA_SUMMARY_CACHE = "pada_summary_cache"

/** Zero-filled fallback for a missing/null bucket — every field on [PadaVisitBucket] is a plain
 * Int, so (like [PadaSummary] itself) it caches directly with no intermediate DTO needed. */
private val EMPTY_BUCKET = PadaVisitBucket(
  womenCount = 0,
  womenOverdueCount = 0,
  childCount = 0,
  childOverdueCount = 0,
)

/**
 * Real [PadaRepository] backed by `GET /sakhi/{sakhiId}/padas`. Same fetch-then-cache-then-
 * fallback resilience shape as [org.armman.sakhi.data.dashboard.RemoteDashboardRepository] — every
 * field on [PadaSummary] is a plain String/Int, so (unlike the dashboard repository) it can be
 * cached directly with no intermediate DTO for `java.time` safety.
 */
@Singleton
class RemotePadaRepository @Inject constructor(
  private val padaApi: PadaApi,
  private val sessionStore: SessionStore,
  private val store: SecureKeyValueStore,
) : PadaRepository {

  private val gson = Gson()
  private val mutex = Mutex()

  override suspend fun getPadaSummaries(): List<PadaSummary> = mutex.withLock {
    val fetched = fetchPadas()
    if (fetched != null) {
      // Persisted even when empty: a Sakhi with zero padas today must not keep seeing a stale list.
      store.putString(KEY_PADA_SUMMARY_CACHE, gson.toJson(fetched))
      return fetched
    }
    readPersisted() ?: throw IllegalStateException("No pada summaries available online or cached")
  }

  private suspend fun fetchPadas(): List<PadaSummary>? {
    val sakhiId = sessionStore.readSession()?.subjectId ?: return null
    return try {
      padaApi.getPadas(sakhiId)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
        ?.padas
        ?.mapNotNull { it.toDomain() }
    } catch (e: Exception) {
      // Offline, timeout, malformed body — all fall back to whatever's cached.
      null
    }
  }

  private fun readPersisted(): List<PadaSummary>? {
    val json = store.getString(KEY_PADA_SUMMARY_CACHE) ?: return null
    return try {
      gson.fromJson(json, object : TypeToken<List<PadaSummary>>() {}.type)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  /** Null only for a row missing its id — nothing to key search/navigation by. Every other field
   * degrades to a safe default rather than dropping the row. */
  private fun PadaDto.toDomain(): PadaSummary? {
    val id = padaId?.takeIf { it.isNotBlank() } ?: return null
    return PadaSummary(
      padaId = id,
      padaName = padaName?.trim()?.takeIf { it.isNotBlank() } ?: "—",
      villageName = villageName?.trim()?.takeIf { it.isNotBlank() } ?: "—",
      open = open.toDomain(),
      referralFollowUp = referralFollowUp.toDomain(),
      visitsRemainingCount = visitsRemainingCount ?: 0,
    )
  }

  private fun PadaVisitBucketDto?.toDomain(): PadaVisitBucket {
    if (this == null) return EMPTY_BUCKET
    return PadaVisitBucket(
      womenCount = womenCount ?: 0,
      womenOverdueCount = womenOverdueCount ?: 0,
      childCount = childCount ?: 0,
      childOverdueCount = childOverdueCount ?: 0,
    )
  }
}
