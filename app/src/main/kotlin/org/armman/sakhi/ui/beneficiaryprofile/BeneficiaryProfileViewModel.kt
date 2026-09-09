package org.armman.sakhi.ui.beneficiaryprofile

import java.time.LocalDate
import java.util.UUID
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.lmpchange.LmpChangeRepository
import org.armman.sakhi.data.lmpchange.LocalLmpChangeAppliedStore
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.reopen.ReopenRepository
import org.armman.sakhi.data.reopen.ReopenRequestReason
import org.armman.sakhi.data.reopen.ReopenSubmissionException
import org.armman.sakhi.data.schedule.ScheduleContext
import org.armman.sakhi.data.schedule.ScheduleRuleSource
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.visitform.VisitFormRepository
import javax.inject.Inject

/**
 * What the Delivery footer button should show and do for a MOTHER profile (CR-042). Computed in
 * [BeneficiaryProfileViewModel.loadProfile] from [DeliverySessionRepository.getActiveForBeneficiary]
 * (the in-progress session pointer, if any) together with [BeneficiaryProfileUiState.hasDeliveryRecorded]
 * (the pre-existing PP-schedule idempotency check — kept as-is rather than replaced, since a null
 * active session is ambiguous between "never started" and "fully done" and that flag is what
 * resolves it).
 */
sealed interface DeliveryButtonState {
  /** No delivery session has ever started for this mother. Tapping mints a fresh session id and
   * opens the `DELIVERY_VISIT` form. */
  data object NotStarted : DeliveryButtonState

  /** A session row exists but the `DELIVERY_VISIT` form itself was never submitted (the Sakhi
   * started, then left before submitting). Tapping resumes the SAME [sessionUuid] rather than
   * minting a new one — see [org.armman.sakhi.ui.delivery.DeliverySessionViewModel.sessionUuid]'s
   * doc for why that distinction matters. Currently unreachable in practice: nothing in
   * [org.armman.sakhi.data.delivery.DeliveryFormSubmissionCoordinator] writes a session row before
   * a successful submit (see [DeliverySessionStep.DELIVERY_FORM]'s own doc) — kept for when that
   * changes, rather than assumed impossible. */
  data class ResumeDeliveryForm(val sessionUuid: String) : DeliveryButtonState

  /** `DELIVERY_VISIT` submitted; at least one live-born child still needs `CHILD_REGISTRATION`.
   * Tapping opens [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationScreen] for the SAME
   * [sessionUuid] — that screen resolves which specific child (of up to three) comes next itself,
   * from [org.armman.sakhi.data.delivery.DeliverySessionEntity.nextChildIndexToRegister], the same
   * way [ResumeDeliveryForm] resumes its own session id rather than this state needing to track the
   * child index too. */
  data class ChildRegistrationPending(val sessionUuid: String) : DeliveryButtonState

  /** The next actionable step is an already-generated visit (PP1, or a same-session NN once that
   * step is reachable — see [org.armman.sakhi.data.schedule.sameSessionNnVisit]). Tapping opens it
   * directly via the existing Visit Form route, same as [org.armman.sakhi.data.beneficiaryprofile
   * .ProfileVisit]'s own Start Visit action. [label] is the schedule row's own `visitCode`
   * (e.g. "PP1") — reused as-is rather than re-derived, matching every other visit-form call site's
   * literal-label convention (see [org.armman.sakhi.ui.navigation.AppNavHost]'s `PADA_VISITS`
   * composable for the same pattern). */
  data class ResumeVisit(val localScheduleUuid: String, val label: String) : DeliveryButtonState

  /** Every applicable step for this delivery is complete — or, defensively, a PP schedule exists
   * but no active session or resumable visit could be found, which reads the same to the Sakhi
   * either way ("already recorded", button disabled). */
  data object Completed : DeliveryButtonState

  /** Not a MOTHER profile. [Footer] doesn't render a Delivery button at all in this case (matches
   * the pre-existing `beneficiaryType == MOTHER` guard) — kept as an explicit state rather than
   * nullable so every call site has to handle it. */
  data object NotApplicable : DeliveryButtonState
}

/** UI state for the Beneficiary Profile screen. */
data class BeneficiaryProfileUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val profile: BeneficiaryProfile? = null,
  /**
   * Whether Start Visit can actually open a form for this beneficiary.
   *
   * False only when [VisitFormRepository.canStartVisit] itself fails or the id is blank — a
   * Sakhi's own enrolment now resolves via [org.armman.sakhi.data.visitform.StaticVisitFormRepository]'s
   * synthetic-context fallback (CR-026 interim). The screen shows the "coming soon" message for
   * the false case.
   */
  val canStartVisit: Boolean = false,
  /** True when [profile] is CLOSED and at least one reopen request for this beneficiary already
   * has `supervisorStatus == "PENDING"` — the Footer shows "Reopen pending review" instead of a
   * tappable Reopen button in that case. Best-effort, same as [canStartVisit]: a fetch failure
   * just leaves this false rather than failing the whole profile load. */
  val hasPendingReopenRequest: Boolean = false,
  /** Task-6 (rejection half): true when [profile] is CLOSED and at least one reopen request for
   * this beneficiary already has `supervisorStatus == "REJECTED"` and none is currently PENDING —
   * the Footer shows a "reopen request was rejected" banner above the (now-tappable-again) Reopen
   * button in that case. A rejection does not change [BeneficiaryProfile.status] server-side (she
   * stays CLOSED — only APPROVED reactivates her), so this is purely a notification signal, not a
   * status change like [hasApprovedReopenRequest]'s poll. Best-effort, same as
   * [hasPendingReopenRequest]: a fetch failure just leaves this false rather than failing the
   * whole profile load. */
  val hasRejectedReopenRequest: Boolean = false,
  /** Task 4 (LMP/Reopen/Referral/Audit task list): true when this MOTHER has at least one LMP
   * change request whose `supervisorStatus` is `"REJECTED"` and none is currently PENDING — the
   * profile shows a "LMP correction request was rejected" banner in that case. Mirrors
   * [hasRejectedReopenRequest] exactly, just for the LMP flow instead of Reopen. Best-effort: a
   * fetch failure just leaves this false rather than failing the whole profile load. */
  val hasRejectedLmpChangeRequest: Boolean = false,
  /**
   * CR-Closure-02: true when [profile] is CLOSED and its [BeneficiaryProfile.closureReasonCode]
   * is not one of the death/miscarriage/abortion reasons the SRS Beneficiary Reopen form excludes
   * (per the design-discussion transcript: "it is not happening in case of death... miscarriage...
   * abortion... it is only happening in case of migration and if they have closed it by
   * mistake"). Defaults to eligible (true) when the reason is unknown -- e.g. closed on another
   * device, or before this field existed -- rather than hiding Reopen outright for those cases;
   * see [org.armman.sakhi.data.beneficiary.Beneficiary.closureReasonCode]'s doc for why the reason
   * can be genuinely unknown even for a real CLOSED beneficiary. A Supervisor still has final say
   * on the request itself, so over-offering the button here is the lower-risk direction until
   * CR-Closure-01's backend ask #1 (`GET /closures` readback) removes the ambiguity entirely.
   */
  val isReopenEligible: Boolean = false,
  val isSubmittingReopen: Boolean = false,
  /**
   * True once this MOTHER's delivery has already been recorded — CR-042 (Delivery Event
   * Session). Backed by [VisitScheduleRepository.hasScheduleOfType] against [VisitCodeType.PP]
   * rather than a server-side `currentPhase` field: [org.armman.sakhi.data.schedule.VisitScheduleCoordinator.onDeliveryRecorded]
   * generates the PP series exactly once per beneficiary (its own per-family idempotency guard),
   * so "a PP schedule exists locally" is already the authoritative, offline-safe answer to
   * "has delivery happened" — no network round trip needed, and it can't disagree with the local
   * schedule the app itself is the author of. Best-effort like [canStartVisit]/
   * [hasPendingReopenRequest]: a lookup failure leaves this false rather than failing the whole
   * profile load — a Sakhi blocked from a delivery she hasn't done yet is worse than one who can
   * tap it and see a real error. Superseded for the Footer's actual button copy/enablement by
   * [deliveryButtonState] (session-aware); kept as its own field since [deliveryButtonState]'s
   * [DeliveryButtonState.Completed] fallback still reads it.
   */
  val hasDeliveryRecorded: Boolean = false,
  /** Session-aware Delivery button state — see [DeliveryButtonState]'s own doc. */
  val deliveryButtonState: DeliveryButtonState = DeliveryButtonState.NotApplicable,
)

/** Fire-and-forget outcomes from [BeneficiaryProfileViewModel.submitReopenRequest] — the screen
 * shows a Toast for either and does not otherwise change navigation. */
sealed interface BeneficiaryProfileEvent {
  data object ReopenRequested : BeneficiaryProfileEvent
  data class ReopenFailed(val message: String) : BeneficiaryProfileEvent
}

@HiltViewModel
class BeneficiaryProfileViewModel @Inject constructor(
  private val repository: BeneficiaryProfileRepository,
  private val visitFormRepository: VisitFormRepository,
  private val reopenRepository: ReopenRepository,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val deliverySessionRepository: DeliverySessionRepository,
  private val statusOverrideStore: LocalBeneficiaryStatusOverrideStore,
  /** Tasks 3/4 (LMP/Reopen/Referral/Audit task list). */
  private val lmpChangeRepository: LmpChangeRepository,
  private val visitScheduleCoordinator: VisitScheduleCoordinator,
  private val scheduleRuleSource: ScheduleRuleSource,
  private val lmpChangeAppliedStore: LocalLmpChangeAppliedStore,
  /** Task 8 (LMP/Reopen/Referral/Audit task list). */
  private val referralRepository: ReferralRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val beneficiaryId: String = savedStateHandle[NAV_ARG_ID] ?: ""

  private val _uiState = MutableStateFlow(BeneficiaryProfileUiState())
  val uiState: StateFlow<BeneficiaryProfileUiState> = _uiState.asStateFlow()

  private val _events = Channel<BeneficiaryProfileEvent>(Channel.BUFFERED)
  val events: Flow<BeneficiaryProfileEvent> = _events.receiveAsFlow()

  init {
    loadProfile()
  }

  /**
   * Loads (or reloads after an error) the beneficiary detail for this id.
   *
   * Also called from [BeneficiaryProfileScreen]'s `LaunchedEffect(Unit)` on every fresh entry into
   * composition, not just here in init — this ViewModel is retained on its NavBackStackEntry, so
   * an init-only load would show stale data (e.g. a visit still "Open" right after its own Submit
   * flow popped back to this exact screen instance) on every return visit, not just the first. The
   * one redundant reload this causes on first entry is a cheap, idempotent price for that.
   */
  fun loadProfile() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        // A blank id means the screen was opened without its nav argument.
        require(beneficiaryId.isNotBlank()) { "Missing beneficiary id" }
        // Task 8 (LMP/Reopen/Referral/Audit task list): pull any Supervisor follow-up decision
        // (LAPSE/REFILL) made since this device last saw this beneficiary's referrals, BEFORE the
        // profile fetch below — repository.getBeneficiary reads the cached referral_links rows
        // synchronously to build each visit card's referral chip
        // (see ProfileVisitMapper.toProfileVisits), so refreshing first is what makes a Supervisor's
        // decision show up on this same load rather than only after a second reload. Best-effort,
        // same as every other poll in this function: see ReferralRepository.refreshReferralStatuses's
        // doc for the still-open SAKHI-role and resubmission-semantics caveats.
        runCatching { referralRepository.refreshReferralStatuses(beneficiaryId) }
        var profile = repository.getBeneficiary(beneficiaryId)
        // Bug fix (2026-09-04): every reopen-request lookup below (hasApprovedReopenRequest here,
        // hasPendingReopenRequest/hasRejectedReopenRequest further down) used to query with
        // `beneficiaryId` directly — the nav arg, which is a LOCAL uuid for a beneficiary sourced
        // from LocalEnrolmentBeneficiarySource (the common case: a beneficiary this device
        // enrolled). The backend's reopen-requests endpoints are keyed by the SERVER beneficiary
        // id, same as the LMP-change endpoints below and same as submitReopenRequest already
        // resolves correctly — so every GET 404'd with "Beneficiary case not found" (confirmed
        // on-device via the api-calls.jsonl log), and being best-effort
        // (runCatching { }.getOrDefault(false)), that 404 silently became "false": a Sakhi could
        // never see her request as pending, approved, or rejected — only ever the pre-request
        // "Reopen" state — no matter what the supervisor did. Resolved here the same way
        // submitReopenRequest already does, once, and reused for every reopen check below (also
        // replaces the old separate lmpServerBeneficiaryId resolution further down — same value,
        // same reasoning, no need to compute it twice).
        val serverBeneficiaryId = runCatching {
          visitScheduleRepository.getForBeneficiary(beneficiaryId).firstNotNullOfOrNull { it.serverBeneficiaryId }
        }.getOrNull() ?: beneficiaryId
        // CR-Closure-04 (CR-Closure-01 item #10): the backend has no webhook for a reopen
        // decision (confirmed 2026-08-31) — this poll is what stands in for one. Only worth
        // asking when CLOSED; an ACTIVE/JOURNEY_COMPLETE beneficiary can't have an approved
        // request waiting to be noticed. Best-effort, same rationale as hasPendingReopenRequest
        // below: a check failure just leaves her CLOSED for now, retried on the next profile load
        // (this function also runs on every screen re-entry, not just init — see this function's
        // own doc above).
        if (profile.status == BeneficiaryStatus.CLOSED) {
          // Bug fix (2026-09-09): AdHocFormSubmissionCoordinator.submitClosure's lapse sweep
          // (lapseAllOpenVisits) is a one-shot best-effort call at closure-submit time -- wrapped
          // in runCatching, with no other call site anywhere in the app. If it fails, or races a
          // visit-schedule row not yet written when closure was submitted (e.g. a just-generated
          // PP1), that row stays GENERATED/OPEN forever with nothing left to ever sweep it again.
          // Reported: "Beneficiary status marked 'Death', but PP1 remains active and available
          // for processing." Self-heals here instead: every profile load for an already-CLOSED
          // beneficiary re-runs the same sweep, so a straggler visit gets cancelled -- and then
          // hidden, see RETIRED_STATUSES in ProfileVisitMapper -- the next time her profile is
          // opened, regardless of why the original submit-time sweep missed it. A beneficiary who
          // is already fully lapsed costs one cheap no-op UPDATE (0 rows affected), so this runs
          // unconditionally rather than trying to detect the original failure.
          val lapsedCount = runCatching { visitScheduleRepository.lapseAllOpenVisits(beneficiaryId) }
            .getOrDefault(0)
          if (lapsedCount > 0) {
            profile = repository.getBeneficiary(beneficiaryId)
          }

          val approved = runCatching { reopenRepository.hasApprovedReopenRequest(serverBeneficiaryId) }
            .getOrDefault(false)
          if (approved) {
            // The backend contract says APPROVED already reactivated the beneficiary and resumed
            // her visit schedules server-side — but for a locally enrolled beneficiary, THIS
            // app's own status read comes from statusOverrideStore, not a server round trip (see
            // LocalEnrolmentBeneficiarySource.buildBeneficiary), so that local CLOSED override has
            // to be cleared explicitly before a re-fetch will actually show ACTIVE. A remote-only
            // beneficiary has no override to clear — repository.getBeneficiary already re-reads
            // her currentStatus from the server every time (see RemoteBeneficiaryProfileRepository)
            // — so this call is a harmless no-op for that case.
            statusOverrideStore.clearOverride(beneficiaryId)
            profile = repository.getBeneficiary(beneficiaryId)
          }
        }
        // Task 3 (LMP/Reopen/Referral/Audit task list): poll for an approved LMP correction and
        // regenerate the ANC schedule exactly once per approved request — see
        // LocalLmpChangeAppliedStore's own doc for why the idempotency guard is required here
        // (unlike the reopen-approval poll above, onLmpOrEddApproved is NOT safe to call on every
        // profile load; it unconditionally supersedes the beneficiary's open visits). Only a
        // MOTHER can have an LMP on file at all. Best-effort end to end: any failure anywhere in
        // this block just leaves the schedule as it was, retried on the next profile load, same
        // as every other poll in this function.
        // The LMP-change-request endpoints are keyed by the SERVER beneficiary id, not the local
        // enrolment id `beneficiaryId` usually is for an on-device beneficiary (see
        // ScheduleBackedBeneficiaryProfileRepository.getBeneficiary's own doc: `profile.id` is the
        // local id in the common case). Without this, every lmpChangeRepository call below 404s
        // silently (runCatching swallows it) and an approved LMP correction never regenerates the
        // schedule. Reuses [serverBeneficiaryId] resolved above (same lookup, same reasoning —
        // no need to resolve it twice).
        if (profile.type == BeneficiaryType.MOTHER) {
          val approvedLmpChange = runCatching { lmpChangeRepository.approvedLmpChangeRequest(serverBeneficiaryId) }
            .getOrNull()
          val approvedRequestId = approvedLmpChange?.id
          val approvedNewLmpDate = approvedLmpChange?.newLmpDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
          val registrationDate = profile.registrationDate
          if (approvedRequestId != null && approvedNewLmpDate != null && registrationDate != null &&
            lmpChangeAppliedStore.getAppliedRequestId(beneficiaryId) != approvedRequestId
          ) {
            val regenerated = runCatching {
              visitScheduleCoordinator.onLmpOrEddApproved(
                ScheduleContext(
                  localBeneficiaryId = beneficiaryId,
                  registrationDate = registrationDate,
                  lmp = approvedNewLmpDate,
                  edd = approvedNewLmpDate.plusDays(scheduleRuleSource.eddOffsetDays().toLong()),
                ),
              )
            }.isSuccess
            if (regenerated) {
              // Marked applied BEFORE the re-fetch below, not after — a crash/process-death right
              // here must not leave this request perpetually un-applied (the schedule is already
              // correctly regenerated by this point; only the profile's own display data hasn't
              // been re-read yet, which the very next profile load will simply do again).
              lmpChangeAppliedStore.setAppliedRequestId(beneficiaryId, approvedRequestId)
              profile = repository.getBeneficiary(beneficiaryId)
            }
          }
        }
        // Failing this check must not fail the whole screen — the profile is still worth showing
        // without a working Start Visit button.
        val canStartVisit = runCatching { visitFormRepository.canStartVisit(beneficiaryId) }
          .getOrDefault(false)
        // CR-042: only a MOTHER can have a delivery recorded against her; skip the lookup for an
        // infant profile rather than asking a question that can never be true for one.
        val hasDeliveryRecorded = if (profile.type == BeneficiaryType.MOTHER) {
          runCatching { visitScheduleRepository.hasScheduleOfType(beneficiaryId, VisitCodeType.PP) }
            .getOrDefault(false)
        } else {
          false
        }
        val deliveryButtonState = if (profile.type == BeneficiaryType.MOTHER) {
          computeDeliveryButtonState(hasDeliveryRecorded)
        } else {
          DeliveryButtonState.NotApplicable
        }
        // Only worth asking for a CLOSED beneficiary — an ACTIVE/JOURNEY_COMPLETE one can't have a
        // pending reopen request. Best-effort: see hasPendingReopenRequest's own doc.
        val hasPendingReopenRequest = if (profile.status == BeneficiaryStatus.CLOSED) {
          runCatching { reopenRepository.hasPendingReopenRequest(serverBeneficiaryId) }.getOrDefault(false)
        } else {
          false
        }
        // Task-6 (rejection half): only meaningful for a CLOSED beneficiary with no request still
        // PENDING — if a fresh request is already in flight, that one deserves the "pending
        // review" copy, not a stale rejection banner from a prior request. Best-effort, same
        // rationale as hasPendingReopenRequest above.
        val hasRejectedReopenRequest = if (profile.status == BeneficiaryStatus.CLOSED && !hasPendingReopenRequest) {
          runCatching { reopenRepository.hasRejectedReopenRequest(serverBeneficiaryId) }.getOrDefault(false)
        } else {
          false
        }
        val isReopenEligible = profile.status == BeneficiaryStatus.CLOSED &&
          profile.closureReasonCode !in NON_REOPENABLE_CLOSURE_REASONS
        // Task 4: only meaningful for a MOTHER with no LMP change request still PENDING — a
        // fresh resubmission in flight deserves no banner at all yet, not a stale rejection
        // banner from a prior request. Same rationale as hasRejectedReopenRequest above.
        val hasRejectedLmpChangeRequest = if (profile.type == BeneficiaryType.MOTHER) {
          val pending = runCatching { lmpChangeRepository.hasPendingLmpChangeRequest(serverBeneficiaryId) }
            .getOrDefault(false)
          if (pending) {
            false
          } else {
            runCatching { lmpChangeRepository.hasRejectedLmpChangeRequest(serverBeneficiaryId) }.getOrDefault(false)
          }
        } else {
          false
        }
        _uiState.update {
          it.copy(
            isLoading = false,
            profile = profile,
            canStartVisit = canStartVisit,
            hasPendingReopenRequest = hasPendingReopenRequest,
            hasRejectedReopenRequest = hasRejectedReopenRequest,
            hasRejectedLmpChangeRequest = hasRejectedLmpChangeRequest,
            isReopenEligible = isReopenEligible,
            hasDeliveryRecorded = hasDeliveryRecorded,
            deliveryButtonState = deliveryButtonState,
          )
        }
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true, profile = null) }
      }
    }
  }

  /**
   * [DeliveryButtonState] for this mother. [hasDeliveryRecorded] resolves the ambiguity in a null
   * active session (never started vs. fully done) — see that field's own doc.
   */
  private suspend fun computeDeliveryButtonState(hasDeliveryRecorded: Boolean): DeliveryButtonState {
    val activeSession = runCatching { deliverySessionRepository.getActiveForBeneficiary(beneficiaryId) }
      .getOrNull()
    return when (activeSession?.step) {
      DeliverySessionStep.DELIVERY_FORM -> DeliveryButtonState.ResumeDeliveryForm(activeSession.localSessionUuid)
      DeliverySessionStep.CHILD_REGISTRATION ->
        DeliveryButtonState.ChildRegistrationPending(activeSession.localSessionUuid)
      DeliverySessionStep.PP1 ->
        resolveResumeVisit(VisitCodeType.PP, sequenceNo = 1) ?: DeliveryButtonState.Completed
      DeliverySessionStep.NN -> resolveNnResumeVisit(activeSession) ?: DeliveryButtonState.Completed
      // DONE is excluded by DeliverySessionRepository.getActiveForBeneficiary's own contract (see
      // its doc), so this branch is unreachable in practice — folded into the same fallback as a
      // null session rather than given its own dead code path.
      DeliverySessionStep.DONE, null ->
        if (hasDeliveryRecorded) DeliveryButtonState.Completed else DeliveryButtonState.NotStarted
    }
  }

  /** The active row for ([visitType], [sequenceNo]) if the Sakhi hasn't already completed it.
   * Best-effort: a lookup failure or a genuinely missing row (shouldn't happen once a session has
   * reached this step) just falls back to the caller's "Completed" default rather than crashing
   * the whole profile load. */
  private suspend fun resolveResumeVisit(visitType: VisitCodeType, sequenceNo: Int): DeliveryButtonState.ResumeVisit? =
    runCatching {
      visitScheduleRepository.getActiveForBeneficiary(beneficiaryId)
        .firstOrNull { it.visitType == visitType && it.sequenceNo == sequenceNo && it.status != VisitScheduleStatus.COMPLETED }
        ?.let { DeliveryButtonState.ResumeVisit(it.localScheduleUuid, it.visitCode) }
    }.getOrNull()

  /**
   * Best-effort NN resume target for [DeliverySessionStep.NN].
   *
   * Stale-comment fix (2026-08-21): this used to say the [DeliverySessionStep.NN] step was
   * "currently unreachable" and that [org.armman.sakhi.data.delivery.DeliverySessionEntity] didn't
   * store `deliveryFormFilledOn` — both were wrong even before the CR-042 defect fix:
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.advanceDeliverySessionIfDue]
   * already transitions PP1 -> NN, and `deliveryFormFilledOn` has been a real column on that
   * entity the whole time.
   *
   * CR-042 defect fix (2026-08-21): NN now generates anchored to the CHILD's own local beneficiary
   * id, never the mother's (see [org.armman.sakhi.data.schedule.VisitScheduleCoordinator]
   * .onDeliveryRecorded's doc) — so looking this up against [beneficiaryId] (this mother's own
   * profile id) would never find it post-fix. [session]'s child1/2/3BeneficiaryId columns are the
   * correct anchor: [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmissionCoordinator]
   * reuses each child's serverBeneficiaryId as its localBeneficiaryId too (see that class's own
   * doc), so those columns are valid local ids to query against directly, no re-resolution needed.
   * Still an "earliest open NN across all registered children" heuristic rather than an exact
   * [org.armman.sakhi.data.schedule.sameSessionNnVisit] match — fine in practice since at most one
   * child's NN can be open while the session sits at this step for a single-birth delivery; a
   * twin/triplet delivery with more than one child mid-NN simultaneously is not handled precisely,
   * same limitation the original heuristic already had.
   */
  private suspend fun resolveNnResumeVisit(session: DeliverySessionEntity): DeliveryButtonState.ResumeVisit? =
    runCatching {
      val childBeneficiaryIds = listOfNotNull(
        session.child1BeneficiaryId,
        session.child2BeneficiaryId,
        session.child3BeneficiaryId,
      )
      childBeneficiaryIds
        .flatMap { visitScheduleRepository.getActiveForBeneficiary(it) }
        .filter { it.visitType == VisitCodeType.NN && it.status != VisitScheduleStatus.COMPLETED }
        .minByOrNull(VisitScheduleEntity::scheduledDate)
        ?.let { DeliveryButtonState.ResumeVisit(it.localScheduleUuid, it.visitCode) }
    }.getOrNull()

  /** Submits a reopen request for [reason] — see [ReopenRepository.submitReopenRequest]'s doc for
   * why this bypasses the ad-hoc-form offline-sync queue entirely (online-only action). No-op if a
   * submission is already in flight. */
  fun submitReopenRequest(reason: ReopenRequestReason) {
    if (_uiState.value.isSubmittingReopen) return
    _uiState.update { it.copy(isSubmittingReopen = true) }
    viewModelScope.launch {
      try {
        // `beneficiaryId` (the nav arg) is already the server id for a row sourced from
        // RemoteBeneficiaryRepository (My Beneficiaries' server-backed list), but for a row
        // sourced from LocalEnrolmentBeneficiarySource it's a LOCAL uuid the backend has never
        // seen — same distinction AdHocFormSubmissionCoordinator.submit resolves via its own
        // VisitScheduleRepository lookup. Falls back to the raw beneficiaryId when no schedule row
        // carries a server id yet (e.g. a locally-sourced row the backend has genuinely never
        // heard of); in practice a beneficiary must already be server-known for a CLOSED status to
        // exist at all, so this fallback path should be unreachable in the button's own gating,
        // but is kept rather than crashing if that assumption ever breaks.
        val serverBeneficiaryId = visitScheduleRepository.getForBeneficiary(beneficiaryId)
          .firstNotNullOfOrNull { it.serverBeneficiaryId }
          ?: beneficiaryId
        reopenRepository.submitReopenRequest(serverBeneficiaryId, reason, UUID.randomUUID().toString())
        _uiState.update { it.copy(isSubmittingReopen = false, hasPendingReopenRequest = true) }
        _events.trySend(BeneficiaryProfileEvent.ReopenRequested)
      } catch (e: ReopenSubmissionException.Failed) {
        _uiState.update { it.copy(isSubmittingReopen = false) }
        _events.trySend(
          BeneficiaryProfileEvent.ReopenFailed(
            SubmitErrorCopy.forApiError(e.apiMessage, emptyMap(), e.violations),
          ),
        )
      } catch (e: Exception) {
        // Offline/timeout/anything else unclassified — same generic copy every other submit flow
        // in this app falls back to.
        _uiState.update { it.copy(isSubmittingReopen = false) }
        _events.trySend(BeneficiaryProfileEvent.ReopenFailed(SubmitErrorCopy.GENERIC))
      }
    }
  }

  companion object {
    const val NAV_ARG_ID = "id"

    /**
     * CR-Closure-02: `CLOSURE_REASON` backend codes (see
     * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s own mapping tables) the
     * SRS Beneficiary Reopen form does not cover -- MATERNAL_DEATH/INFANT_OR_CHILD_DEATH,
     * MISCARRIAGE, ABORTION. MIGRATION, WITHDRAWAL, PROGRAM_CYCLE_COMPLETED and an unknown/null
     * reason all remain reopen-eligible -- see [isReopenEligible]'s field doc above for why
     * unknown defaults to eligible rather than blocked.
     */
    private val NON_REOPENABLE_CLOSURE_REASONS = setOf(
      "MATERNAL_DEATH",
      "INFANT_OR_CHILD_DEATH",
      "MISCARRIAGE",
      "ABORTION",
    )
  }
}
