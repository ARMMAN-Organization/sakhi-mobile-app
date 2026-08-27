package org.armman.sakhi.ui.beneficiaryprofile

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
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.reopen.ReopenRepository
import org.armman.sakhi.data.reopen.ReopenRequestReason
import org.armman.sakhi.data.reopen.ReopenSubmissionException
import org.armman.sakhi.data.schedule.VisitCodeType
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
        val profile = repository.getBeneficiary(beneficiaryId)
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
          runCatching { reopenRepository.hasPendingReopenRequest(beneficiaryId) }.getOrDefault(false)
        } else {
          false
        }
        _uiState.update {
          it.copy(
            isLoading = false,
            profile = profile,
            canStartVisit = canStartVisit,
            hasPendingReopenRequest = hasPendingReopenRequest,
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
      DeliverySessionStep.NN -> resolveNnResumeVisit() ?: DeliveryButtonState.Completed
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
   * Best-effort NN resume target for [DeliverySessionStep.NN] — currently unreachable (nothing yet
   * transitions a session to this step; see [DeliveryButtonState.ResumeVisit]'s doc), so this is a
   * documented heuristic rather than the exact [org.armman.sakhi.data.schedule.sameSessionNnVisit]
   * match: that selector needs the delivery form's own `deliveryFormFilledOn`, which
   * [org.armman.sakhi.data.delivery.DeliverySessionEntity] does not currently store. Once an NN
   * step-setter exists, prefer threading that date through instead of this "earliest open NN"
   * fallback.
   */
  private suspend fun resolveNnResumeVisit(): DeliveryButtonState.ResumeVisit? =
    runCatching {
      visitScheduleRepository.getActiveForBeneficiary(beneficiaryId)
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
        reopenRepository.submitReopenRequest(serverBeneficiaryId, reason)
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
  }
}
