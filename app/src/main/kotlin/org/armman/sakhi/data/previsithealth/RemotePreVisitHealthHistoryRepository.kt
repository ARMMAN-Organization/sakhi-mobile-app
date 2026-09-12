package org.armman.sakhi.data.previsithealth

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.visitform.VisitFormRiskAssessment
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Matches [org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper]'s date-label rationale:
 * pinned to [Locale.ENGLISH] regardless of device locale, since the pattern itself ("6 July") is
 * English-ordered. */
private val TREND_DATE_FORMAT: DateTimeFormatter =
  DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withZone(ZoneId.systemDefault())

/** FR-S-4.6 — the last 2 completed visits, no more. */
private const val HISTORY_LIMIT = 2

/** Placeholder shown for a vital a *specific* visit in the trend didn't capture, while the factor
 * overall still has data from another visit — distinct from a factor with no data at all across
 * every visit, which is dropped entirely (see [buildFactor]/[buildBloodPressureFactor]). */
private const val NO_READING = "—"

private val VISIT_HISTORY_LIST_TYPE = object : TypeToken<List<VisitHistoryEntryDto>>() {}.type

private const val TAG = "PreVisitHealthHistory"

/**
 * Real [PreVisitHealthHistoryRepository] backed by `GET /beneficiaries/:beneficiaryId/visit-history`
 * (FR-S-4.6). Replaces the retired `StaticPreVisitHealthHistoryRepository`'s 14-fixture stub, which
 * threw [NoSuchElementException] for every real, actually-enrolled beneficiary the moment she had
 * one completed visit — the reported "We couldn't load this beneficiary's health history" bug.
 *
 * ### Local id in, server id out
 * The screen's [beneficiaryId] nav argument is the *local* enrolment id — the same one
 * [org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper] and the rest of the profile screen
 * use. This endpoint needs the *server*-assigned beneficiary id instead, resolved the same way
 * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel] already does for the
 * reopen-request flow: read back whichever server id has synced onto this beneficiary's own
 * schedule rows.
 *
 * A beneficiary enrolled (and possibly visited) entirely offline has no server id yet — a normal,
 * expected state for this offline-first app, not an error. That case throws
 * [BeneficiaryNotSyncedException] rather than a generic failure, so the ViewModel can treat it as
 * "nothing to show yet, let her proceed to the visit form" instead of surfacing an error screen.
 *
 * [visitId] (the other nav argument) is intentionally unused here beyond the interface contract —
 * the endpoint returns a beneficiary's history regardless of which upcoming visit triggered the
 * screen; only the beneficiary is in scope, not the specific visit about to start.
 *
 * ### Offline fallback (cache-then-live, reported gap fixed here)
 * Once a beneficiary has synced (past her first visit), every subsequent open of this screen made
 * a *live-only* network call with nothing cached — so going offline (or hitting the Android
 * clock-skew/TLS restriction from changing the device date) hard-blocked every later visit behind
 * a Retry-only error screen, even though the beneficiary's very first visit had "worked offline"
 * moments earlier by accident (that case never needed the network — see
 * [BeneficiaryNotSyncedException] above). Now: a successful live fetch is cached in
 * [PreVisitHealthHistoryCacheDao] keyed by server beneficiary id; a network-layer failure
 * ([IOException] and its subtypes — offline, DNS, TLS/clock-skew, timeout) falls back to that
 * cache instead of failing outright. A real server rejection (e.g. HTTP 403/404) is NOT an
 * [IOException] and is not masked by this fallback — it still surfaces as before. If there is no
 * cache yet (this beneficiary's history was never successfully fetched on this device), the
 * original exception is rethrown and the screen's existing error+Retry state is unchanged — this
 * fallback only ever adds a success path, never removes the prior one.
 */
@Singleton
class RemotePreVisitHealthHistoryRepository @Inject constructor(
  private val api: PreVisitHealthHistoryApi,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val cacheDao: PreVisitHealthHistoryCacheDao,
) : PreVisitHealthHistoryRepository {

  private val gson = Gson()

  override suspend fun getHealthHistory(beneficiaryId: String, visitId: String): PreVisitHealthHistory {
    val serverBeneficiaryId = visitScheduleRepository.getForBeneficiary(beneficiaryId)
      .firstNotNullOfOrNull { it.serverBeneficiaryId }
      ?: throw BeneficiaryNotSyncedException(beneficiaryId)

    val liveVisits = try {
      val response = api.getVisitHistory(serverBeneficiaryId, limit = HISTORY_LIMIT)
      if (!response.isSuccessful) {
        throw NoSuchElementException(
          "GET visit-history failed for beneficiary $serverBeneficiaryId: HTTP ${response.code()}",
        )
      }
      response.body()?.takeIf { it.success }?.data?.visits.orEmpty()
    } catch (e: IOException) {
      // Network-layer failure, not a real server rejection — fall back to the last successful
      // fetch for this beneficiary rather than hard-failing the screen. Rethrow unchanged (same
      // exception the caller already knows how to log/classify) when nothing is cached yet.
      val cached = cacheDao.get(serverBeneficiaryId)
      if (cached == null) {
        Log.w(TAG, "Live fetch failed for $serverBeneficiaryId and no cache exists — rethrowing: ${e.message}")
        throw e
      }
      Log.i(TAG, "Live fetch failed for $serverBeneficiaryId (${e.javaClass.simpleName}: ${e.message}) — serving cached copy from ${cached.cachedAtEpochMillis}")
      return gson.fromJson<List<VisitHistoryEntryDto>>(cached.visitsJson, VISIT_HISTORY_LIST_TYPE)
        .toPreVisitHealthHistory()
    }

    Log.d(TAG, "Live fetch succeeded for $serverBeneficiaryId — caching ${liveVisits.size} visit(s)")
    cacheDao.upsert(
      PreVisitHealthHistoryCacheEntity(
        serverBeneficiaryId = serverBeneficiaryId,
        visitsJson = gson.toJson(liveVisits),
        cachedAtEpochMillis = System.currentTimeMillis(),
      ),
    )
    return liveVisits.toPreVisitHealthHistory()
  }
}

/**
 * Maps the API's newest-first visit list onto the five FR-S-4.6 vitals. Each factor's trend reads
 * oldest-to-newest with the final column relabeled "Last Visit" (matches
 * `PreVisitHealthHistoryViewModelTest`'s existing expectations), so the list is reversed once here
 * up front rather than in every per-factor builder.
 *
 * A factor with no data in *any* returned visit is dropped entirely, rather than shown as an empty
 * card — same "skip a row whose reading isn't answered yet" rule [VisitFormRiskAssessment
 * .buildTestsFindings] already applies to the Visit Form's own Summary tab.
 */
private fun List<VisitHistoryEntryDto>.toPreVisitHealthHistory(): PreVisitHealthHistory {
  val chronological = asReversed()

  val factors = listOfNotNull(
    buildFactor(
      chronological,
      factorName = "Anaemia",
      measureLabel = "Hemoglobin",
      valueOf = { it.vitals?.hemoglobin?.value },
      classify = { hb -> VisitFormRiskAssessment.hemoglobin(hb, sickleCellCode = null) },
    ),
    buildBloodPressureFactor(chronological),
    buildFactor(
      chronological,
      factorName = "Weight",
      measureLabel = "Weight",
      valueOf = { it.vitals?.weight?.value },
    ),
    buildFactor(
      chronological,
      factorName = "Blood Sugar",
      measureLabel = "Blood Sugar",
      valueOf = { it.vitals?.bloodSugar?.value },
    ),
    buildFactor(
      chronological,
      factorName = "Temperature",
      measureLabel = "Temperature",
      valueOf = { it.vitals?.temperature?.value },
    ),
  )

  // FR-S-4.6: at most 5 risk-factor cards. Hb/BP/Weight/Blood Sugar/Temperature is already
  // exactly 5, so .take(5) is a defensive cap, not expected to trim anything today.
  val (riskFactors, nonRiskVitals) = factors.partition { it.riskLevel != null }
  return PreVisitHealthHistory(riskFactors = riskFactors.take(5), nonRiskVitals = nonRiskVitals)
}

/** "Last Visit" on the final (most recent) column; every earlier column gets its completion date.
 * Falls back to the visit code if [VisitHistoryEntryDto.completedAt] is missing/unparseable rather
 * than dropping the column — a malformed date on one field shouldn't hide a real vital reading. */
private fun labelFor(entry: VisitHistoryEntryDto, isLast: Boolean): String {
  if (isLast) return "Last Visit"
  val completedAt = entry.completedAt ?: return entry.visitCode.orEmpty()
  return runCatching { TREND_DATE_FORMAT.format(Instant.parse(completedAt)) }
    .getOrDefault(entry.visitCode.orEmpty())
}

/** Builds one non-BP [RiskFactorTrend], or null if no visit in [visits] captured this vital at
 * all. [classify] is only supplied for vitals with a known risk band today (Hb) — Weight/Blood
 * Sugar/Temperature have none yet (same incremental state [VisitFormRiskAssessment] documents for
 * its own Summary-tab findings), so they always land in [PreVisitHealthHistory.nonRiskVitals]. */
private fun buildFactor(
  visits: List<VisitHistoryEntryDto>,
  factorName: String,
  measureLabel: String,
  valueOf: (VisitHistoryEntryDto) -> String?,
  classify: ((Double) -> RiskLevel)? = null,
): RiskFactorTrend? {
  if (visits.none { valueOf(it) != null }) return null

  val values = visits.mapIndexed { index, entry ->
    val raw = valueOf(entry)
    val riskLevel = classify?.let { fn -> raw?.toDoubleOrNull()?.let(fn) }
    TrendValue(
      label = labelFor(entry, isLast = index == visits.lastIndex),
      value = raw ?: NO_READING,
      abnormal = riskLevel != null && riskLevel != RiskLevel.LOW,
    )
  }

  val latestRiskLevel = classify?.let { fn ->
    visits.last().let(valueOf)?.toDoubleOrNull()?.let(fn)?.takeIf { it != RiskLevel.LOW }
  }

  return RiskFactorTrend(factorName, measureLabel, latestRiskLevel, values)
}

/** Blood pressure's own builder — two numbers per reading rather than [buildFactor]'s single
 * string, so it can't reuse that function directly. Same drop-if-never-captured and
 * last-column-relabeled rules apply. */
private fun buildBloodPressureFactor(visits: List<VisitHistoryEntryDto>): RiskFactorTrend? {
  fun VisitHistoryEntryDto.reading(): Pair<Int, Int>? {
    val bp = vitals?.bloodPressure ?: return null
    val systolic = bp.systolic ?: return null
    val diastolic = bp.diastolic ?: return null
    return systolic to diastolic
  }

  if (visits.none { it.reading() != null }) return null

  val values = visits.mapIndexed { index, entry ->
    val reading = entry.reading()
    val riskLevel = reading?.let { (systolic, diastolic) ->
      VisitFormRiskAssessment.bloodPressure(systolic.toDouble(), diastolic.toDouble())
    }
    TrendValue(
      label = labelFor(entry, isLast = index == visits.lastIndex),
      value = reading?.let { (systolic, diastolic) -> "$systolic/$diastolic" } ?: NO_READING,
      abnormal = riskLevel != null && riskLevel != RiskLevel.LOW,
    )
  }

  val latestRiskLevel = visits.last().reading()?.let { (systolic, diastolic) ->
    VisitFormRiskAssessment.bloodPressure(systolic.toDouble(), diastolic.toDouble())
      .takeIf { it != RiskLevel.LOW }
  }

  return RiskFactorTrend("Hypertension", "Blood Pressure", latestRiskLevel, values)
}
