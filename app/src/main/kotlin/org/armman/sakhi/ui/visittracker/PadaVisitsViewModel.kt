package org.armman.sakhi.ui.visittracker

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.adhocform.AdHocFormDraftDao
import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visit.VisitStatus
import org.armman.sakhi.data.visit.VisitType
import org.armman.sakhi.data.visit.deriveBeneficiaryType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 500L

/**
 * UI state for a single pada's visit list. [visitsByType] holds both tabs' lists so the swipe
 * pager can show the adjacent page's real content.
 */
data class PadaVisitsUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val pada: String = "",
  val selectedTab: VisitType = VisitType.OPEN,
  val searchQuery: String = "",
  val visitsByType: Map<VisitType, List<Visit>> = emptyMap(),
  val openCount: Int = 0,
  val referralCount: Int = 0,
) {
  /** Convenience: the currently selected tab's list. */
  val visits: List<Visit>
    get() = visitsByType[selectedTab].orEmpty()
}

/**
 * Backed by `GET /padas/{padaId}/visits`: one call per tab (each returns both tabs' counts, but
 * only its own tab's rows), refetched together on load, retry, and search. Search is exact-match
 * server-side (encrypted names — no partial/fuzzy match), so typing is debounced rather than
 * filtered live like the old client-side mock.
 */
@HiltViewModel
class PadaVisitsViewModel @Inject constructor(
  private val visitRepository: VisitRepository,
  private val localEnrolmentBeneficiarySource: LocalEnrolmentBeneficiarySource,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val referralLinkDao: ReferralLinkDao,
  private val adHocFormDraftDao: AdHocFormDraftDao,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val padaId: String = savedStateHandle[NAV_ARG_PADA_ID] ?: ""
  private val padaName: String = savedStateHandle[NAV_ARG_PADA_NAME] ?: ""

  private val _uiState = MutableStateFlow(PadaVisitsUiState(pada = padaName))
  val uiState: StateFlow<PadaVisitsUiState> = _uiState.asStateFlow()

  /**
   * Holds the latest keystroke; [SEARCH_DEBOUNCE_MS] later that value triggers a real reload.
   *
   * Bug fix: this was a `MutableSharedFlow(extraBufferCapacity = 1)` written with `tryEmit`, which
   * silently DROPS an emission once the single buffer slot is full. Two keystrokes closer together
   * than the collector's turnaround (i.e. ordinary typing) meant the second was thrown away, and
   * `debounce` then settled on the STALE prefix — so a Sakhi typing "Sunita Sharma" could get the
   * results for "Sun". Since the search is exact-match server-side, that returns nothing and reads
   * as "beneficiary not found". A StateFlow always retains the newest value (conflating rather than
   * dropping), which is exactly the "latest wins" semantics debounce needs. `drop(1)` skips the
   * initial "" so this doesn't fire a duplicate no-op fetch on top of `loadVisits()`.
   */
  private val searchQueryChanges = MutableStateFlow("")

  init {
    loadVisits()
    viewModelScope.launch {
      searchQueryChanges
        .drop(1)
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .collect { fetchVisits(it) }
    }
  }

  /** Loads (or reloads after an error/retry) both tabs for the current search query. */
  fun loadVisits() {
    viewModelScope.launch { fetchVisits(_uiState.value.searchQuery) }
  }

  fun onTabSelected(tab: VisitType) {
    _uiState.update { it.copy(selectedTab = tab) }
  }

  /** Updates the input immediately; the network reload is debounced (see [searchQueryChanges]). */
  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    searchQueryChanges.value = query
  }

  private suspend fun fetchVisits(search: String) {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    val trimmedSearch = search.trim().takeIf { it.isNotBlank() }
    // Bharath, 2026-09-10 ("visible on Home, not on Visit Tracker"): a schedule that already
    // synced (e.g. an earlier Data Upload) is just as invisible to a STALE/incomplete
    // RemoteVisitRepository cache as one that never synced — the cache can predate the visit
    // entering its window either way. Restricting to unsynced-only here bought nothing: the real
    // double-count guard is [mergeLocalOverlay]'s own per-row (beneficiaryId, visitLabel) dedupe
    // below, which already drops a local row the server response already reports, synced or not.
    // So this always considers every currently open local visit/referral as a merge candidate.
    val localOverlay = localOverlayVisits(trimmedSearch)
    try {
      val open = visitRepository.getVisits(padaId, VisitStatus.OPEN, search = trimmedSearch)
      val referral = visitRepository.getVisits(padaId, VisitStatus.REFERRAL_FOLLOW_UP, search = trimmedSearch)
      val mergedOpen = mergeLocalOverlay(open.visits, localOverlay[VisitType.OPEN].orEmpty())
        .sortedForDisplay()
      val mergedReferral = mergeLocalOverlay(referral.visits, localOverlay[VisitType.REFERRAL_FOLLOWUP].orEmpty())
        .sortedForDisplay()
      _uiState.update {
        it.copy(
          isLoading = false,
          openCount = open.openCount + (mergedOpen.size - open.visits.size),
          referralCount = open.referralFollowUpCount + (mergedReferral.size - referral.visits.size),
          visitsByType = mapOf(
            VisitType.OPEN to mergedOpen,
            VisitType.REFERRAL_FOLLOWUP to mergedReferral,
          ),
        )
      }
    } catch (e: Exception) {
      // Generic error state for the UI; technical detail must not leak to users. The real cause
      // is still logged (not swallowed) — this was a silent catch-all with no trace at all, so a
      // failed load here was previously undiagnosable from a bug report alone.
      Log.e(TAG, "fetchVisits(padaId=$padaId, search='$search') failed: ${e::class.simpleName} — ${e.message}", e)
      // CR-VisitTracker offline overlay (bharath, 2026-09-10): the server call failed outright
      // (offline with nothing ever cached for this pada — RemoteVisitRepository only throws once
      // its own cache read also comes up empty). Rather than a blank error screen, show whatever
      // this device knows about its own open visits/pending follow-ups for this pada — a partial,
      // best-effort view beats nothing, and is exactly what a Sakhi mid-round offline needs.
      val overlayOpen = localOverlay[VisitType.OPEN].orEmpty()
      val overlayReferral = localOverlay[VisitType.REFERRAL_FOLLOWUP].orEmpty()
      if (overlayOpen.isEmpty() && overlayReferral.isEmpty()) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      } else {
        _uiState.update {
          it.copy(
            isLoading = false,
            hasError = false,
            openCount = overlayOpen.size,
            referralCount = overlayReferral.size,
            visitsByType = mapOf(
              VisitType.OPEN to overlayOpen.sortedForDisplay(),
              VisitType.REFERRAL_FOLLOWUP to overlayReferral.sortedForDisplay(),
            ),
          )
        }
      }
    }
  }

  /**
   * This device's own open visits and pending referral follow-ups for [padaName] — used both as
   * an additive merge candidate on top of a real server/cached response, and as the full result
   * when that call fails outright with nothing cached at all (see [fetchVisits]'s doc for why
   * merge-time dedupe, not a sync-state filter, is what keeps either use safe from double-counting).
   *
   * Scoped to beneficiaries THIS DEVICE enrolled — same limitation
   * [org.armman.sakhi.data.beneficiary.OfflineFirstBeneficiaryRepository]'s local source already
   * has: no cross-device visibility, and no server-side PII enrichment, so [Visit.village] and
   * [Visit.phoneNumber] fall back to whatever this device already has (or a dash).
   */
  private suspend fun localOverlayVisits(search: String?): Map<VisitType, List<Visit>> {
    val today = LocalDate.now()
    val localBeneficiaries = localEnrolmentBeneficiarySource.getLocalBeneficiaries(today)
      .filter { it.pada.equals(padaName, ignoreCase = true) }
      .associateBy { it.id }
    if (localBeneficiaries.isEmpty()) return emptyMap()

    // Every currently open schedule, synced or not — see [fetchVisits]'s doc for why the merge
    // step's own dedupe is what actually guards against double-counting, not this filter.
    //
    // BUG FIX (bharath, 2026-09-10 — "Shivangii" repeated once per future visit, ANC2 through
    // ANC5+, all shown as "Open" simultaneously): [VisitScheduleRepository.getAllActive] returns
    // every GENERATED row for a beneficiary, which after a full series is generated at enrolment
    // means her ENTIRE future ANC series, not just the one visit actually due today. Only ANC2's
    // window had opened; ANC3/ANC4/ANC5 were still months away. Missing this same "actionable
    // now" filter that [org.armman.sakhi.data.dashboard.LocalVisitCounts.toLocalVisitCounts] and
    // [org.armman.sakhi.data.visittracker.LocalPadaSummaryOverlay] already apply for counting
    // purposes — [isActionableNow] below is that same isInWindow-or-overdue rule, just missing
    // from this row-building path until now.
    val openVisits = visitScheduleRepository.getAllActive()
      .filter { it.localBeneficiaryId in localBeneficiaries && it.isActionableNow(today) }
      .mapNotNull { schedule ->
        localBeneficiaries[schedule.localBeneficiaryId]?.let { schedule.toLocalVisit(it, today) }
      }

    val pendingLinks = referralLinkDao.getPendingFollowUp()
      .filter { it.beneficiaryId in localBeneficiaries }

    // bharath, 2026-09-10 ("referral followup submitted offline still shows up as needing a
    // followup"): getPendingFollowUp() reads the cached ReferralLinkEntity.status, which only
    // moves off PENDING_FOLLOWUP once AdHocFormSubmissionCoordinator's online-only success path
    // runs (see ScheduleBackedBeneficiaryProfileRepository's own pendingSyncReferralIds doc for
    // the identical gap on the profile screen). A referral this device has already submitted a
    // follow-up for -- even if that submission is still queued for sync -- shouldn't keep
    // appearing on this worklist as something still to do.
    val submittedReferralIds = adHocFormDraftDao
      .getReferralFollowUpDraftsByReferralIds(pendingLinks.map { it.referralId })
      .filter { it.syncStatus != EnrollmentSyncStatus.SYNCED }
      .mapNotNull { it.referralId }
      .toSet()

    val referralVisits = pendingLinks
      .filterNot { it.referralId in submittedReferralIds }
      .mapNotNull { link ->
        val beneficiary = localBeneficiaries[link.beneficiaryId] ?: return@mapNotNull null
        // The referral's own visit date, when this device still has that schedule row — a truer
        // due date than [Beneficiary.scheduleDate] (that's the beneficiary's NEXT visit, not the
        // one this referral came from). Falls back to it only if the schedule was somehow purged.
        val visitDate = visitScheduleRepository.getByLocalScheduleUuid(link.localScheduleUuid)
          ?.scheduledDate ?: beneficiary.scheduleDate
        link.toLocalReferralVisit(beneficiary, visitDate)
      }

    val matches: (Visit) -> Boolean = { visit ->
      search == null || visit.beneficiaryName?.equals(search, ignoreCase = true) == true
    }
    return mapOf(
      VisitType.OPEN to openVisits.filter(matches),
      VisitType.REFERRAL_FOLLOWUP to referralVisits.filter(matches),
    )
  }

  /** In its window (startable now) or past it (overdue) — i.e. worth a card on this screen right
   * now. A schedule whose window hasn't opened yet is real future work, not a current visit.
   * "On/after windowStartDate" alone covers both cases (overdue implies past windowStartDate
   * too); written this way to read the same as the two-case rule it represents. */
  private fun VisitScheduleEntity.isActionableNow(today: LocalDate): Boolean =
    !today.isBefore(windowStartDate)

  /**
   * [VisitScheduleEntity] -> [Visit], for a schedule this device generated but has not uploaded.
   *
   * CRASH FIX (bharath, 2026-09-10): [id] was originally left null here, matching the server
   * contract's own "no id for a row with nothing server-assigned yet" convention. But
   * [PadaVisitsScreen]'s `LazyColumn` keys each row as `"$visitType-$beneficiaryId-${id ?: ""}"` —
   * a beneficiary with two concurrently open local schedules (not unusual: an HR visit alongside
   * the regular series, or simply two beneficiaries never colliding before because every
   * server-sourced [Visit.id] was already unique) then produced two rows with the exact same key,
   * which Compose throws `IllegalArgumentException` on instead of silently overwriting. Using
   * [localScheduleUuid] — already unique per schedule, and never consumed anywhere else on this
   * screen (grepped: nothing here calls an API keyed by [Visit.id]) — fixes the key collision
   * without touching the screen's own key function.
   */
  private fun VisitScheduleEntity.toLocalVisit(beneficiary: Beneficiary, today: LocalDate): Visit = Visit(
    id = localScheduleUuid,
    beneficiaryId = localBeneficiaryId,
    beneficiaryName = beneficiary.name,
    beneficiaryType = beneficiary.type,
    // Bharath, 2026-09-10 ("risk showing as Not yet assessed"): this device already computed her
    // real baseline risk on-device at enrolment (SRS FR-S-2.2/CR-034 —
    // [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment], read back via
    // [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource]) — no need to leave this
    // null and let [PadaVisitsScreen]'s `isAssessed = riskLevel != null` badge fall back to the
    // "server enrichment failed" neutral state that null is meant for on a real API row.
    riskLevel = beneficiary.riskLevel,
    visitType = VisitType.OPEN,
    pada = beneficiary.pada,
    village = UNKNOWN_LOCAL_FIELD,
    scheduleDate = scheduledDate,
    dueDate = windowEndDate,
    visitLabel = visitCode,
    daysRemaining = ChronoUnit.DAYS.between(today, windowEndDate).toInt(),
    phoneNumber = beneficiary.phoneNumber.takeIf { it.isNotBlank() },
  )

  /** A locally-cached pending referral follow-up -> [Visit], for the Referral Follow-up tab. */
  /** Same [id] fix as [toLocalVisit] — [ReferralLinkEntity.localScheduleUuid] is unique per row. */
  private fun org.armman.sakhi.data.referral.ReferralLinkEntity.toLocalReferralVisit(
    beneficiary: Beneficiary,
    visitDate: LocalDate,
  ): Visit {
    val label = "Referral Follow-up"
    return Visit(
      id = localScheduleUuid,
      beneficiaryId = beneficiaryId,
      beneficiaryName = beneficiary.name,
      beneficiaryType = deriveBeneficiaryType(label),
      // Same fix as toLocalVisit above — use this device's own computed risk instead of null.
      riskLevel = beneficiary.riskLevel,
      visitType = VisitType.REFERRAL_FOLLOWUP,
      pada = beneficiary.pada,
      village = UNKNOWN_LOCAL_FIELD,
      scheduleDate = visitDate,
      dueDate = visitDate,
      visitLabel = label,
      daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), visitDate).toInt(),
      phoneNumber = beneficiary.phoneNumber.takeIf { it.isNotBlank() },
    )
  }

  /**
   * Appends only the local rows not already represented in [serverVisits] — once a locally
   * generated schedule syncs and the server starts reporting it (with its own [Visit.id]), the
   * local synthetic copy must stop being added on top of it. Matched by (beneficiary, visit
   * label) rather than by [Visit.id]: the local copy's id is a [VisitScheduleEntity
   * .localScheduleUuid]/[org.armman.sakhi.data.referral.ReferralLinkEntity.localScheduleUuid],
   * never the server's own id space, so the two are never comparable directly.
   */
  private fun mergeLocalOverlay(serverVisits: List<Visit>, localVisits: List<Visit>): List<Visit> {
    if (localVisits.isEmpty()) return serverVisits
    val serverKeys = serverVisits.map { it.beneficiaryId to it.visitLabel }.toSet()
    val extra = localVisits.filterNot { (it.beneficiaryId to it.visitLabel) in serverKeys }
    return serverVisits + extra
  }

  /**
   * Bharath, 2026-09-10 ("the beneficiary I'm looking for isn't showing / so many entries") —
   * [mergeLocalOverlay] just appends local rows after the server ones, and the local rows
   * themselves come back in whatever order [VisitScheduleRepository.getAllActive] returns them
   * (beneficiary id, then date — an internal storage order, not a display order). On a pada with
   * many open visits, the one visit a Sakhi actually needs right now can end up buried well below
   * the fold. Re-sorts the WHOLE merged list — server-sourced and local-overlay rows alike — the
   * same way [org.armman.sakhi.data.visit.RemoteVisitRepository]'s own (private) sort already
   * orders a real API response: risk severity first, then soonest due date. Local-overlay rows
   * now carry this device's own computed [Beneficiary.riskLevel] too (see [toLocalVisit]), so they
   * sort by real severity exactly like server-sourced rows, not just by date.
   */
  private fun List<Visit>.sortedForDisplay(): List<Visit> =
    sortedWith(compareBy({ it.riskLevel.severityRank() }, { it.dueDate }))

  private fun RiskLevel?.severityRank(): Int = when (this) {
    RiskLevel.HIGH -> 0
    RiskLevel.MODERATE -> 1
    RiskLevel.MILD -> 2
    else -> 3
  }

  companion object {
    const val NAV_ARG_PADA_ID = "padaId"
    const val NAV_ARG_PADA_NAME = "padaName"

    /** [Visit.village] has no on-device equivalent for a locally-sourced overlay row — same
     * dash-fallback convention as [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource]. */
    private const val UNKNOWN_LOCAL_FIELD = "—"
  }
}

private const val TAG = "PadaVisitsTracker"
