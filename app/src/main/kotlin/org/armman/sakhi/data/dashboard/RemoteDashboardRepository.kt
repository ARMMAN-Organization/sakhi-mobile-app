package org.armman.sakhi.data.dashboard

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_DASHBOARD_CACHE_PREFIX = "dashboard_summary_cache_"

/** The on-disk cache key is scoped per-Sakhi (by session subjectId), not a single device-global
 * key. Without this, a device previously used by a different Sakhi would still surface her stale
 * cached [DashboardSummary.sakhiName] (and counts) after a new Sakhi logs in but before her first
 * successful fetch completes — the same "stale device cache" class of bug that
 * [org.armman.sakhi.data.auth.CurrentUserRepository.clearIfDifferentUser] guards against for the
 * `/me` profile, which this cache was missing. */
private fun cacheKeyFor(subjectId: String) = "$KEY_DASHBOARD_CACHE_PREFIX$subjectId"

/** Wire shape persisted to disk — [DashboardSummary.lastSyncedAt] is a `java.time.Instant`, which
 * plain Gson cannot safely round-trip without a custom adapter, so the cached shape stores it as
 * the original ISO string instead and re-parses on read (same reasoning as
 * [org.armman.sakhi.data.motherlink.RemoteMotherLinkRepository]'s own doc comment about avoiding a
 * custom `java.time` Gson adapter). */
private data class CachedDashboardSummary(
  val sakhiName: String,
  val lastSyncedAt: String?,
  val totalActiveBeneficiaries: Int,
  val activeMothersCount: Int,
  val activeChildrenCount: Int,
  val activeMothersHighRiskCount: Int,
  val activeChildrenHighRiskCount: Int,
  val activeMothersPercent: Double,
  val activeChildrenPercent: Double,
  val accompaniedReferralsCount: Int,
  val pendingFollowUpsCount: Int,
  val dueVisitsCount: Int,
  val overdueVisitsCount: Int,
  val endingSoonVisitsCount: Int,
)

/**
 * Real [DashboardRepository] backed by `GET /sakhi/{sakhiId}/dashboard`. Same
 * fetch-then-cache-then-fallback resilience shape as
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository] — but
 * [DashboardRepository.getSummary] is non-nullable (existing interface, unchanged), so when there
 * is genuinely nothing to show (first launch, offline, nothing ever cached) this throws rather
 * than returning null. [org.armman.sakhi.ui.home.HomeViewModel.loadSummary] already catches any
 * exception into `HomeUiState.Error`, so no ViewModel change was needed for this repository swap.
 */
@Singleton
class RemoteDashboardRepository @Inject constructor(
  private val dashboardApi: DashboardApi,
  private val sessionStore: SessionStore,
  private val store: SecureKeyValueStore,
) : DashboardRepository {

  private val gson = Gson()
  private val mutex = Mutex()

  override suspend fun getSummary(): DashboardSummary = mutex.withLock {
    val sakhiId = sessionStore.readSession()?.subjectId

    val fetched = fetchSummary(sakhiId)
    if (fetched != null && sakhiId != null) {
      store.putString(cacheKeyFor(sakhiId), gson.toJson(fetched.toCached()))
      return fetched
    }
    if (fetched != null) {
      // No session to key the cache by — still fine to show, just nothing to persist.
      return fetched
    }
    sakhiId?.let { readPersisted(it) }?.toDomain()
      ?: throw IllegalStateException("No dashboard summary available online or cached")
  }

  private suspend fun fetchSummary(sakhiId: String?): DashboardSummary? {
    if (sakhiId == null) return null
    return try {
      dashboardApi.getDashboard(sakhiId)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
        ?.toDomain()
    } catch (e: Exception) {
      // Offline, timeout, malformed body — all fall back to whatever's cached.
      null
    }
  }

  private fun readPersisted(sakhiId: String): CachedDashboardSummary? {
    val json = store.getString(cacheKeyFor(sakhiId)) ?: return null
    return try {
      gson.fromJson(json, CachedDashboardSummary::class.java)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  /** Null only when the response carries no usable Sakhi name at all — everything else degrades
   * to a safe default (0 / 0.0) rather than dropping the whole summary. */
  private fun DashboardDataDto.toDomain(): DashboardSummary? {
    val name = sakhi?.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return DashboardSummary(
      sakhiName = name,
      lastSyncedAt = lastSyncedAt?.toInstantOrNull(),
      totalActiveBeneficiaries = beneficiarySummary?.totalActiveBeneficiaries ?: 0,
      activeMothersCount = beneficiarySummary?.activeMothersCount ?: 0,
      activeChildrenCount = beneficiarySummary?.activeChildrenCount ?: 0,
      activeMothersHighRiskCount = beneficiarySummary?.activeMothersHighRiskCount ?: 0,
      activeChildrenHighRiskCount = beneficiarySummary?.activeChildrenHighRiskCount ?: 0,
      activeMothersPercent = beneficiarySummary?.activeMothersPercent ?: 0.0,
      activeChildrenPercent = beneficiarySummary?.activeChildrenPercent ?: 0.0,
      accompaniedReferralsCount = referralSummary?.accompaniedReferralsCount ?: 0,
      pendingFollowUpsCount = referralSummary?.pendingFollowUpsCount ?: 0,
      dueVisitsCount = visitSummary?.dueVisitsCount ?: 0,
      overdueVisitsCount = visitSummary?.overdueVisitsCount ?: 0,
      endingSoonVisitsCount = visitSummary?.endingSoonVisitsCount ?: 0,
    )
  }

  private fun DashboardSummary.toCached() = CachedDashboardSummary(
    sakhiName = sakhiName,
    lastSyncedAt = lastSyncedAt?.toString(),
    totalActiveBeneficiaries = totalActiveBeneficiaries,
    activeMothersCount = activeMothersCount,
    activeChildrenCount = activeChildrenCount,
    activeMothersHighRiskCount = activeMothersHighRiskCount,
    activeChildrenHighRiskCount = activeChildrenHighRiskCount,
    activeMothersPercent = activeMothersPercent,
    activeChildrenPercent = activeChildrenPercent,
    accompaniedReferralsCount = accompaniedReferralsCount,
    pendingFollowUpsCount = pendingFollowUpsCount,
    dueVisitsCount = dueVisitsCount,
    overdueVisitsCount = overdueVisitsCount,
    endingSoonVisitsCount = endingSoonVisitsCount,
  )

  private fun CachedDashboardSummary.toDomain() = DashboardSummary(
    sakhiName = sakhiName,
    lastSyncedAt = lastSyncedAt?.toInstantOrNull(),
    totalActiveBeneficiaries = totalActiveBeneficiaries,
    activeMothersCount = activeMothersCount,
    activeChildrenCount = activeChildrenCount,
    activeMothersHighRiskCount = activeMothersHighRiskCount,
    activeChildrenHighRiskCount = activeChildrenHighRiskCount,
    activeMothersPercent = activeMothersPercent,
    activeChildrenPercent = activeChildrenPercent,
    accompaniedReferralsCount = accompaniedReferralsCount,
    pendingFollowUpsCount = pendingFollowUpsCount,
    dueVisitsCount = dueVisitsCount,
    overdueVisitsCount = overdueVisitsCount,
    endingSoonVisitsCount = endingSoonVisitsCount,
  )
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
