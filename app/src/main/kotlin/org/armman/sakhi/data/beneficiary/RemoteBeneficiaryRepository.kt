package org.armman.sakhi.data.beneficiary

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.BeneficiaryListItemDto
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_REMOTE_BENEFICIARY_CACHE_PREFIX = "remote_beneficiary_list_cache_"
private const val CASE_TYPE_CHILD = "CHILD"
private const val STATUS_JOURNEY_COMPLETE = "JOURNEY_COMPLETE"
private const val STATUS_CLOSED = "CLOSED"
private const val UNNAMED_REMOTE = "Unnamed beneficiary"
private const val PADA_UNRESOLVED = "—"
private const val VISIT_UNAVAILABLE = "—"

/** Both the disk cache and the in-memory [RemoteBeneficiaryRepository.cached] snapshot are scoped
 * by the session's subjectId — see the constructor doc for why: a device previously used by a
 * different Sakhi must never surface her cached caseload after a new Sakhi logs in. */
private fun cacheKeyFor(subjectId: String) = "$KEY_REMOTE_BENEFICIARY_CACHE_PREFIX$subjectId"

/**
 * Server-sourced beneficiaries for My Beneficiaries — the remote half of
 * [OfflineFirstBeneficiaryRepository], gated behind [RemoteBeneficiaryListFeatureFlag] until
 * `GET /beneficiaries` is scoped to the calling Sakhi.
 *
 * Same resilience shape as [org.armman.sakhi.data.motherlink.RemoteMotherLinkRepository]: prefer a
 * live fetch, persist every success to [SecureKeyValueStore], fall back to memory then disk when the
 * fetch fails — so a Sakhi who has opened the list once can still see it offline for the rest of the
 * day. The raw DTO is what gets cached (every field a plain string), not the mapped [Beneficiary]
 * domain object, so no custom `java.time` Gson adapter is needed the way
 * [org.armman.sakhi.data.motherlink.motherLinkGson] needs one for [LocalDate] fields — mapping to
 * the domain shape happens fresh on every read, live or cached.
 *
 * Both the disk cache and the in-memory [cached] snapshot are keyed by the current session's
 * subjectId (via [sessionStore]) — this used to be one device-global disk key with an unscoped
 * in-memory field, so a device previously used by a different Sakhi would keep showing her cached
 * caseload (from memory, even before touching disk) after a new Sakhi logged in but before her own
 * first successful fetch completed. Same "stale device cache" class of bug that
 * [org.armman.sakhi.data.auth.CurrentUserRepository.clearIfDifferentUser] guards against for the
 * `/me` profile, which this repository was missing on both its cache layers.
 *
 * Deliberately calls [BeneficiaryApi.listAll] with no caseType/status filter rather than
 * [BeneficiaryApi.list]'s narrower mother-only contract: My Beneficiaries needs both MOTHER and
 * CHILD rows across every status, bucketed into tabs client-side exactly the way
 * [LocalEnrolmentBeneficiarySource] already does for local rows.
 */
@Singleton
class RemoteBeneficiaryRepository @Inject constructor(
  private val beneficiaryApi: BeneficiaryApi,
  private val sessionStore: SessionStore,
  private val store: SecureKeyValueStore,
) {

  private val gson = Gson()
  private val mutex = Mutex()

  // Both null fields together mean "nothing cached in memory yet". [cachedForSakhiId] records
  // which Sakhi's session the in-memory [cached] list belongs to, so a mismatch (a different
  // Sakhi logged in since) is treated the same as a cold cache rather than served up.
  private var cached: List<BeneficiaryListItemDto>? = null
  private var cachedForSakhiId: String? = null

  /**
   * Null only when there is genuinely nothing to show yet — first run, offline, and nothing was
   * ever cached. Callers fall back to the local-only list in that case. A non-null empty list is a
   * real answer ("the server has nothing for this Sakhi"), not a failure.
   */
  suspend fun fetchRemoteBeneficiaries(today: LocalDate = LocalDate.now()): List<Beneficiary>? =
    mutex.withLock {
      val sakhiId = sessionStore.readSession()?.subjectId

      val fetched = fetchRows()
      if (fetched != null) {
        // Persisted even when empty: a Sakhi whose last case was closed must stop seeing a stale row.
        if (sakhiId != null) store.putString(cacheKeyFor(sakhiId), gson.toJson(fetched))
        cached = fetched
        cachedForSakhiId = sakhiId
        return fetched.mapNotNull { it.toRemoteBeneficiary(today) }
      }

      if (cachedForSakhiId == sakhiId) {
        cached?.let { return it.mapNotNull { row -> row.toRemoteBeneficiary(today) } }
      }
      val persisted = sakhiId?.let { readPersisted(it) }
      if (persisted != null) {
        cached = persisted
        cachedForSakhiId = sakhiId
      }
      persisted?.mapNotNull { it.toRemoteBeneficiary(today) }
    }

  private suspend fun fetchRows(): List<BeneficiaryListItemDto>? = try {
    beneficiaryApi.listAll(caseType = null, status = null)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.items
  } catch (e: Exception) {
    // Offline, timeout, malformed body — all treated the same: fall back to whatever's cached.
    null
  }

  private fun readPersisted(sakhiId: String): List<BeneficiaryListItemDto>? {
    val json = store.getString(cacheKeyFor(sakhiId)) ?: return null
    return try {
      gson.fromJson(json, object : TypeToken<List<BeneficiaryListItemDto>>() {}.type)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  /**
   * Null for a row that cannot become a [Beneficiary] at all — missing id. Everything else degrades
   * gracefully (a blank name renders as a placeholder, an unresolved pada as a dash) rather than
   * dropping the row, matching [LocalEnrolmentBeneficiarySource]'s best-effort philosophy: a
   * partially-readable server row is still worth showing.
   */
  private fun BeneficiaryListItemDto.toRemoteBeneficiary(today: LocalDate): Beneficiary? {
    val remoteId = id?.takeIf { it.isNotBlank() } ?: return null
    val status = currentStatus.toBeneficiaryStatus()
    val parsedRegistrationDate = registrationDate.toLocalDateOrNull()

    return Beneficiary(
      id = remoteId,
      name = pii?.fullName?.trim()?.takeIf { it.isNotBlank() } ?: UNNAMED_REMOTE,
      type = if (caseType.equals(CASE_TYPE_CHILD, ignoreCase = true)) {
        BeneficiaryType.INFANT
      } else {
        BeneficiaryType.MOTHER
      },
      // No risk model runs server-side — CR-034's baseline assessment only ever runs on-device
      // against the full enrolment answers, which this row does not carry. LOW is a placeholder,
      // exactly like a locally-sourced child's riskLevel; `isAssessed = false` below is what
      // actually tells the card (and any future risk filter) not to trust it.
      riskLevel = RiskLevel.LOW,
      status = status,
      visitState = if (status == BeneficiaryStatus.ACTIVE) VisitState.OPEN else null,
      // `beneficiary-service` now resolves villageId -> a display name server-side and sends it
      // as `villageName` (the resolver this comment used to say the app didn't have yet — see git
      // history). Still falls back to the same dash the local source uses for an unresolvable id,
      // since the backend enrichment itself degrades a stale/deleted villageId to null rather than
      // failing the whole row.
      pada = villageName?.trim()?.takeIf { it.isNotBlank() } ?: PADA_UNRESOLVED,
      scheduleDate = parsedRegistrationDate ?: today,
      // Visit schedules are generated entirely on-device (SRS FR-S-2.2) and never uploaded — a
      // remote-only row (no local draft on this device) has no schedule to show at all.
      visitLabel = VISIT_UNAVAILABLE,
      daysRemaining = 0,
      // The list row's `pii` block carries no mobile number (only the detail endpoint does, per
      // BeneficiaryPiiDto's own doc) — blank rather than an extra per-row detail call.
      phoneNumber = "",
      journeyCompletedIn = null,
      isAssessed = false,
      remoteBeneficiaryId = remoteId,
      registrationDate = parsedRegistrationDate,
    )
  }

  private fun String?.toBeneficiaryStatus(): BeneficiaryStatus = when (this?.uppercase()) {
    STATUS_JOURNEY_COMPLETE -> BeneficiaryStatus.JOURNEY_COMPLETE
    STATUS_CLOSED -> BeneficiaryStatus.CLOSED
    // ACTIVE, TRANSFERRED, REOPEN_REQUESTED, and anything unrecognised all read as still-open from
    // the Sakhi's perspective — none of them mean "done" or "closed" the way the app's own
    // BeneficiaryStatus models it. FLAGGED: collapsing TRANSFERRED/REOPEN_REQUESTED into ACTIVE is a
    // placeholder until My Beneficiaries has its own states for them; a Sakhi should eventually be
    // able to tell a transferred case apart from a normally-active one.
    else -> BeneficiaryStatus.ACTIVE
  }

  /**
   * `"2001-07-29T00:00:00.000Z"` → `2001-07-29`, null for anything unparseable. Mirrors
   * `RemoteMotherLinkRepository`'s identical helper — the date part is taken verbatim rather than
   * parsed as an instant so a calendar date stored at midnight UTC never shifts a day west of UTC.
   */
  private fun String?.toLocalDateOrNull(): LocalDate? {
    val datePart = this?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { LocalDate.parse(datePart) }.getOrNull()
  }
}
