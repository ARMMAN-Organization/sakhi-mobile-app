package org.armman.sakhi.data.beneficiaryprofile

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.BeneficiaryDetailDto
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_REMOTE_PROFILE_CACHE_PREFIX = "remote_beneficiary_profile_cache_"
private const val CASE_TYPE_CHILD = "CHILD"
private const val STATUS_JOURNEY_COMPLETE = "JOURNEY_COMPLETE"
private const val STATUS_CLOSED = "CLOSED"
private const val UNNAMED_REMOTE = "Unnamed beneficiary"
private const val UNRESOLVED_PLACE = "—"

private fun cacheKeyFor(id: String) = "$KEY_REMOTE_PROFILE_CACHE_PREFIX$id"

/**
 * Fetches a single beneficiary's full detail from `GET /beneficiaries/{id}` for a beneficiary with
 * no local enrolment draft on this device — the "Not yet assessed" / remote-only rows
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository] already lists on My Beneficiaries,
 * whose "See Profile" tap previously fell through to [StaticBeneficiaryProfileRepository]'s
 * fourteen-sample-id fixture and threw [NoSuchElementException] for any real server id (CR-037,
 * reported bug: "We couldn't load this beneficiary").
 *
 * Same resilience shape as [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository]: a
 * successful fetch is cached (per beneficiary id, since unlike the list this is fetched one id at a
 * time) in [SecureKeyValueStore]; a failed fetch falls back to that cache rather than failing
 * outright, so a profile already opened once stays available offline for the rest of the day. Only
 * a genuinely uncached failure (first-ever open while offline, a real 404, a malformed response)
 * propagates — [getBeneficiary] then throws [NoSuchElementException], matching
 * [BeneficiaryProfileRepository]'s documented contract so [ScheduleBackedBeneficiaryProfileRepository]
 * and the ViewModel's existing generic catch-all both keep working unchanged.
 *
 * Two fields are deliberately left blank/empty rather than mapped, both intentional, not
 * oversights:
 *  - `husbandName` — hidden from this profile per product decision, not merely missing from the
 *    response.
 *  - `lastVisitStats` — `lastVisitVitals` is new on the detail response this cycle, but its exact
 *    JSON shape has not been confirmed against a live sample (see [BeneficiaryDetailDto]'s own
 *    doc). Mapping a guessed shape risks silently misreading a real clinical reading, so this stays
 *    empty until a confirmed sample lands — a follow-up to this same CR, not a separate one.
 */
@Singleton
class RemoteBeneficiaryProfileRepository @Inject constructor(
  private val beneficiaryApi: BeneficiaryApi,
  private val store: SecureKeyValueStore,
  private val localEnrolments: LocalEnrolmentBeneficiarySource,
) {

  private val gson = Gson()

  suspend fun getBeneficiary(id: String): BeneficiaryProfile {
    val fetched = fetchDetail(id)
    if (fetched != null) {
      store.putString(cacheKeyFor(id), gson.toJson(fetched))
      return fetched.toProfile(id)
    }

    val cached = readPersisted(id)
      ?: throw NoSuchElementException("Unknown or unreachable beneficiary id: $id")
    return cached.toProfile(id)
  }

  private suspend fun fetchDetail(id: String): BeneficiaryDetailDto? = try {
    beneficiaryApi.detail(id)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
  } catch (e: Exception) {
    // Offline, timeout, malformed body — all fall back to whatever's cached, same as
    // RemoteBeneficiaryRepository.fetchRows.
    null
  }

  private fun readPersisted(id: String): BeneficiaryDetailDto? {
    val json = store.getString(cacheKeyFor(id)) ?: return null
    return try {
      gson.fromJson(json, BeneficiaryDetailDto::class.java)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  private suspend fun BeneficiaryDetailDto.toProfile(id: String): BeneficiaryProfile {
    val isChild = caseType.equals(CASE_TYPE_CHILD, ignoreCase = true)
    val type = if (isChild) BeneficiaryType.INFANT else BeneficiaryType.MOTHER
    val dob = pii?.dateOfBirth.toLocalDateOrNull()

    return BeneficiaryProfile(
      id = id,
      name = pii?.fullName?.trim()?.takeIf { it.isNotBlank() } ?: UNNAMED_REMOTE,
      type = type,
      ageLabel = dob?.let { if (isChild) it.ageInMonthsLabel() else it.ageInYearsLabel() }.orEmpty(),
      village = localEnrolments.resolveGeographyId(pii?.villageId, isChild) ?: UNRESOLVED_PLACE,
      pada = localEnrolments.resolveGeographyId(pii?.padaId, isChild) ?: UNRESOLVED_PLACE,
      // Intentionally hidden from this profile — see class doc.
      husbandName = "",
      mobileNumber = pii?.mobileNumber?.trim().orEmpty(),
      status = currentStatus.toBeneficiaryStatus(),
      riskLevel = riskLevel.toRiskLevel(),
      lmp = motherCaseDetails?.lmpDate.toDisplayDate(),
      edd = motherCaseDetails?.eddDate.toDisplayDate(),
      // IdentityCard's mobile stat strip (Status | Risk | DOB) always renders DOB regardless
      // of case type — MOTHER vs CHILD only differ in whether LMP/EDD also show on the tablet
      // grid. Populating this only for CHILD left every mother's DOB stat blank in production —
      // fixed after the reported bug (komathi, a MOTHER, showed no DOB).
      dob = dob?.format(PROFILE_DATE_FORMAT),
      // No child weight source exists on this endpoint yet — part of the same still-pending
      // last-visit-vitals gap as lastVisitStats below. Left null rather than guessed.
      weight = null,
      diagnoses = riskConditionSummaries.orEmpty()
        .mapNotNull { it.conditionName?.trim()?.takeIf(String::isNotBlank) },
      // Deliberately empty — see class doc.
      lastVisitStats = emptyList(),
      registrationDate = registrationDate.toLocalDateOrNull(),
    )
  }

  private fun String?.toBeneficiaryStatus(): BeneficiaryStatus = when (this?.uppercase()) {
    STATUS_JOURNEY_COMPLETE -> BeneficiaryStatus.JOURNEY_COMPLETE
    STATUS_CLOSED -> BeneficiaryStatus.CLOSED
    // Mirrors RemoteBeneficiaryRepository.toBeneficiaryStatus: ACTIVE, TRANSFERRED,
    // REOPEN_REQUESTED, and anything unrecognised all read as still-open from the Sakhi's
    // perspective.
    else -> BeneficiaryStatus.ACTIVE
  }

  /** `none`/`mild`/`moderate`/`high` (BE's own aggregated vocabulary, worst grade across every
   * [RiskConditionSummaryDto] already applied server-side) mapped onto this app's existing
   * [RiskLevel] buckets — no enum change needed, the two vocabularies are the same four buckets
   * under different names. Anything unrecognised (including a null/blank value, e.g. a
   * beneficiary with no assessment yet) defaults to LOW/no-risk rather than a crash. */
  private fun String?.toRiskLevel(): RiskLevel = when (this?.lowercase()) {
    "high" -> RiskLevel.HIGH
    "moderate" -> RiskLevel.MODERATE
    "mild" -> RiskLevel.MILD
    else -> RiskLevel.LOW
  }

  /** `"2001-07-29T00:00:00.000Z"` -> `2001-07-29`, null for anything unparseable. Mirrors
   * RemoteBeneficiaryRepository's identical helper — the date part is taken verbatim rather than
   * parsed as an instant so a calendar date stored at midnight UTC never shifts a day west of UTC. */
  private fun String?.toLocalDateOrNull(): LocalDate? {
    val datePart = this?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { LocalDate.parse(datePart) }.getOrNull()
  }

  /** Same source dates as [toLocalDateOrNull], rendered the way the rest of the profile does
   * (matches [ScheduleBackedBeneficiaryProfileRepository]'s identical `PROFILE_DATE_FORMAT`
   * convention for a locally-enrolled beneficiary's LMP/EDD). */
  private fun String?.toDisplayDate(): String? = toLocalDateOrNull()?.format(PROFILE_DATE_FORMAT)

  private fun LocalDate.ageInYearsLabel(): String =
    ChronoUnit.YEARS.between(this, LocalDate.now()).coerceAtLeast(0).toString()

  private fun LocalDate.ageInMonthsLabel(): String =
    "${ChronoUnit.MONTHS.between(this, LocalDate.now()).coerceAtLeast(0)} mo"

  private companion object {
    val PROFILE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
  }
}
