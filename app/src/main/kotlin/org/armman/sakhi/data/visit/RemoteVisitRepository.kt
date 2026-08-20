package org.armman.sakhi.data.visit

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.visittracker.PadaVisitDto
import org.armman.sakhi.data.visittracker.VisitApi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val CACHE_KEY_PREFIX = "pada_visits_cache_"

/**
 * Wire shape persisted to disk — [Visit.scheduleDate]/[Visit.dueDate] are `java.time.LocalDate`,
 * which plain Gson cannot safely round-trip without a custom adapter, so the cached shape stores
 * them as ISO strings and re-parses on read (same convention as
 * [org.armman.sakhi.data.dashboard.RemoteDashboardRepository]'s `CachedDashboardSummary`).
 * [Visit.daysRemaining] is deliberately not cached — it's recomputed against "now" on every read
 * (fresh or cached), since a value cached yesterday would be wrong today.
 */
private data class CachedVisit(
  val id: String?,
  val beneficiaryId: String,
  val beneficiaryName: String?,
  val beneficiaryType: BeneficiaryType,
  val riskLevel: RiskLevel?,
  val visitType: VisitType,
  val pada: String,
  val village: String,
  val scheduleDate: String,
  val dueDate: String,
  val visitLabel: String,
  val phoneNumber: String?,
)

private data class CachedPadaVisits(
  val openCount: Int,
  val referralFollowUpCount: Int,
  val visits: List<CachedVisit>,
)

/**
 * Real [VisitRepository] backed by `GET /padas/{padaId}/visits`. Same fetch-then-cache-then-
 * fallback resilience shape as [org.armman.sakhi.data.visittracker.RemotePadaRepository], but
 * scoped per (pada, tab, day): the cache key includes the effective date, so a stale cache from a
 * previous day is never served as "today's" list — it's simply not looked up, since today's key
 * doesn't match yesterday's. Only the unfiltered (no search) fetch is cached; an offline search
 * falls back to filtering the cached full list by the same exact-match rule the API uses.
 */
@Singleton
class RemoteVisitRepository @Inject constructor(
  private val visitApi: VisitApi,
  private val store: SecureKeyValueStore,
) : VisitRepository {

  private val gson = Gson()
  private val mutex = Mutex()

  override suspend fun getVisits(
    padaId: String,
    status: VisitStatus,
    date: LocalDate?,
    search: String?,
  ): PadaVisitsResult = mutex.withLock {
    val effectiveDate = date ?: LocalDate.now()
    val trimmedSearch = search?.trim()?.takeIf { it.isNotBlank() }
    val fetched = fetchVisits(padaId, status, date, trimmedSearch)
    if (fetched != null) {
      // A search result is a subset of the pada's full list — only the unfiltered fetch is worth
      // persisting as "today's full list" for the next offline read.
      if (trimmedSearch == null) {
        store.putString(cacheKey(padaId, status, effectiveDate), gson.toJson(fetched.toCached()))
      }
      return@withLock fetched
    }
    val cached = readPersisted(padaId, status, effectiveDate)?.toDomain()
      ?: throw IllegalStateException("Failed to load visits for pada $padaId (status=$status)")
    if (trimmedSearch == null) cached else cached.filterByExactName(trimmedSearch)
  }

  private suspend fun fetchVisits(
    padaId: String,
    status: VisitStatus,
    date: LocalDate?,
    search: String?,
  ): PadaVisitsResult? = try {
    val response = visitApi.getVisits(
      padaId = padaId,
      status = status.toQueryValue(),
      date = date?.format(DateTimeFormatter.ISO_LOCAL_DATE),
      search = search,
    )
    response.takeIf { it.isSuccessful }?.body()?.takeIf { it.success }?.data?.let { data ->
      PadaVisitsResult(
        openCount = data.openCount ?: 0,
        referralFollowUpCount = data.referralFollowUpCount ?: 0,
        visits = data.visits.orEmpty().mapNotNull { it.toDomain(status) }.sortedBySeverityThenDueDate(),
      )
    }
  } catch (e: Exception) {
    // Offline, timeout, malformed body — all fall back to whatever's cached.
    null
  }

  private fun cacheKey(padaId: String, status: VisitStatus, date: LocalDate) =
    "$CACHE_KEY_PREFIX${padaId}_${status.name}_$date"

  private fun readPersisted(padaId: String, status: VisitStatus, date: LocalDate): CachedPadaVisits? {
    val json = store.getString(cacheKey(padaId, status, date)) ?: return null
    return try {
      gson.fromJson(json, CachedPadaVisits::class.java)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  /** Same exact-match rule as the server (see [VisitRepository.getVisits] doc) — safe to do
   * locally offline because a cached [Visit.beneficiaryName] is already the decrypted display
   * string from a prior successful fetch, not the encrypted wire value. */
  private fun PadaVisitsResult.filterByExactName(search: String): PadaVisitsResult = copy(
    visits = visits.filter { it.beneficiaryName?.equals(search, ignoreCase = true) == true },
  )

  private fun VisitStatus.toQueryValue() = when (this) {
    VisitStatus.OPEN -> "open"
    VisitStatus.REFERRAL_FOLLOW_UP -> "referral_follow_up"
  }

  /** [VisitStatus] is the API's `status` query param; [VisitType] is the domain/UI tab enum.
   * Kept as two separate types (rather than one) because the API's snake_case wire value
   * (`referral_follow_up`) and the UI's tab enum name (`REFERRAL_FOLLOWUP`) are independent
   * concerns that happen to enumerate the same two buckets. */
  private fun VisitStatus.toVisitType() = when (this) {
    VisitStatus.OPEN -> VisitType.OPEN
    VisitStatus.REFERRAL_FOLLOW_UP -> VisitType.REFERRAL_FOLLOWUP
  }

  /** Null only for a row missing its beneficiary id — nothing to key navigation or a list item by.
   * Every other field degrades to a safe default/null rather than dropping the row, per contract:
   * a degraded enrichment lookup still returns the base visit row. */
  private fun PadaVisitDto.toDomain(status: VisitStatus): Visit? {
    val benId = beneficiaryId?.takeIf { it.isNotBlank() } ?: return null
    val due = dueDate?.toLocalDateOrNull() ?: return null
    val scheduled = scheduledDate?.toLocalDateOrNull() ?: due
    val label = visitType?.trim()?.takeIf { it.isNotBlank() }
      ?: if (status == VisitStatus.REFERRAL_FOLLOW_UP) "Referral Follow-up" else "—"
    return Visit(
      id = visitId?.takeIf { it.isNotBlank() },
      beneficiaryId = benId,
      beneficiaryName = beneficiaryName?.trim()?.takeIf { it.isNotBlank() },
      beneficiaryType = deriveBeneficiaryType(label),
      riskLevel = riskLevel.toDomainRiskLevel(),
      visitType = status.toVisitType(),
      pada = padaName?.trim()?.takeIf { it.isNotBlank() } ?: "—",
      village = villageName?.trim()?.takeIf { it.isNotBlank() } ?: "—",
      scheduleDate = scheduled,
      dueDate = due,
      visitLabel = label,
      daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), due).toInt(),
      phoneNumber = phoneNumber?.trim()?.takeIf { it.isNotBlank() },
    )
  }

  private fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(this, DateTimeFormatter.ISO_LOCAL_DATE)
  } catch (e: Exception) {
    null
  }

  /**
   * `high`/`moderate`/`mild` map directly. `none` (a real "normal risk" grade) and any
   * null/unrecognized value both map to null domain-side — the app has no dedicated "normal"
   * badge distinct from "not assessed" (see [Visit.riskLevel]), so `none` renders as the existing
   * neutral badge rather than being misrepresented as [RiskLevel.LOW]. Flagged: this is a judgment
   * call, not a spec'd requirement — revisit if design wants `none` to look different from a
   * failed lookup.
   */
  private fun String?.toDomainRiskLevel(): RiskLevel? = when (this?.trim()?.lowercase()) {
    "high" -> RiskLevel.HIGH
    "moderate" -> RiskLevel.MODERATE
    "mild" -> RiskLevel.MILD
    else -> null
  }

  /** High → Moderate → Mild → (none/unassessed), then due date ascending — client-side ordering
   * until the API guarantees a sort order (pending backend addition #2). */
  private fun List<Visit>.sortedBySeverityThenDueDate(): List<Visit> =
    sortedWith(compareBy({ it.riskLevel.severityRank() }, { it.dueDate }))

  private fun RiskLevel?.severityRank(): Int = when (this) {
    RiskLevel.HIGH -> 0
    RiskLevel.MODERATE -> 1
    RiskLevel.MILD -> 2
    else -> 3
  }

  private fun PadaVisitsResult.toCached() = CachedPadaVisits(
    openCount = openCount,
    referralFollowUpCount = referralFollowUpCount,
    visits = visits.map { it.toCached() },
  )

  private fun Visit.toCached() = CachedVisit(
    id = id,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    beneficiaryType = beneficiaryType,
    riskLevel = riskLevel,
    visitType = visitType,
    pada = pada,
    village = village,
    scheduleDate = scheduleDate.toString(),
    dueDate = dueDate.toString(),
    visitLabel = visitLabel,
    phoneNumber = phoneNumber,
  )

  private fun CachedPadaVisits.toDomain() = PadaVisitsResult(
    openCount = openCount,
    referralFollowUpCount = referralFollowUpCount,
    visits = visits.map { it.toDomain() },
  )

  private fun CachedVisit.toDomain(): Visit {
    val due = LocalDate.parse(dueDate)
    return Visit(
      id = id,
      beneficiaryId = beneficiaryId,
      beneficiaryName = beneficiaryName,
      beneficiaryType = beneficiaryType,
      riskLevel = riskLevel,
      visitType = visitType,
      pada = pada,
      village = village,
      scheduleDate = LocalDate.parse(scheduleDate),
      dueDate = due,
      visitLabel = visitLabel,
      daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), due).toInt(),
      phoneNumber = phoneNumber,
    )
  }
}
