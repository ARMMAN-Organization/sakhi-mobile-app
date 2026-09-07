package org.armman.sakhi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.notification.AppNotification
import org.armman.sakhi.data.notification.NOTIFICATION_CTA_FILL_REFERRAL_FORM
import org.armman.sakhi.data.notification.NOTIFICATION_TYPE_REFERRAL_INCOMPLETE_UPDATE
import org.armman.sakhi.data.notification.NotificationRepository
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.sync.ManualSyncTrigger
import org.armman.sakhi.data.sync.UploadRecordsSource
import javax.inject.Inject

/** UI state for the Home dashboard — loading, error and success. */
sealed interface HomeUiState {
  data object Loading : HomeUiState
  data class Success(val summary: DashboardSummary) : HomeUiState
  data object Error : HomeUiState
}

/**
 * State for the "Forms Uploaded" sync-status modal (Data Upload pill on Home). Kept separate from
 * [HomeUiState] so opening/reloading the modal never disturbs the dashboard cards underneath, and
 * a modal load failure doesn't force the whole Home screen into an error state.
 */
data class UploadModalState(
  val isVisible: Boolean = false,
  val records: List<FormUploadRecord> = emptyList(),
)

/**
 * A draft the backend rejected as a possible duplicate, where the earlier pregnancy is already
 * complete (SRS FR-S-2.5) and nobody has answered the resulting question yet.
 *
 * Surfaced on Home because the rejection can happen during a manual Data Upload, when the Sakhi is
 * nowhere near the enrollment form — without this the draft would stay in DUPLICATE_CONFLICT
 * permanently, visible in the upload modal and impossible to upload.
 *
 * Identified by submission date rather than by name: [FormUploadRecord] carries no PII by design, and
 * decrypting a beneficiary's name just to title a dialog isn't worth widening that boundary.
 */
data class DuplicateReview(
  val localBeneficiaryId: String,
  val existingBeneficiaryId: String,
  val submittedAtEpochMillis: Long,
)

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** [FormUploadRecord.formCode] for the mother/child registration queues — same literals
 * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource] and each queue's own
 * repository already duplicate privately rather than sharing one constant across files. */
private const val MOTHER_REGISTRATION_FORM_CODE = "MOTHER_REGISTRATION"
private const val CHILD_REGISTRATION_FORM_CODE = "CHILD_REGISTRATION"

/** One-shot Home events the screen reacts to (currently just the offline Data Upload toast) —
 * same "Channel + receiveAsFlow" shape as [org.armman.sakhi.ui.visitform.DynamicVisitFormEvent]. */
sealed interface HomeEvent {
  /** The Sakhi tapped Data Upload while offline (bharath, 2026-08-08) — nothing was queued or
   * shown; see [HomeViewModel.onDataUploadClicked]'s doc for why the sync call and modal are
   * skipped entirely rather than started and left to fail. */
  data object OfflineUploadBlocked : HomeEvent

  /** The "Fill Referral Form" CTA (SRS FR-S-7.2 row 2) resolved successfully — navigate to the
   * Referral Follow-up ad-hoc form. See [HomeViewModel.onFillReferralFormClicked]'s doc for why
   * [beneficiaryId] has to be resolved separately from the notification itself. */
  data class NavigateToReferralFollowUp(val beneficiaryId: String, val referralId: String) : HomeEvent

  /** The CTA's referral id wasn't found in the Sakhi's current pending-follow-up list — see
   * [HomeViewModel.onFillReferralFormClicked]'s doc for when this can happen. */
  data object ReferralFollowUpNotFound : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val dashboardRepository: DashboardRepository,
  private val uploadRecordsSource: UploadRecordsSource,
  private val manualSyncTrigger: ManualSyncTrigger,
  private val dynamicFormDraftRepository: DynamicFormDraftRepository,
  private val connectivityChecker: ConnectivityChecker,
  private val notificationRepository: NotificationRepository,
  private val referralRepository: ReferralRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)

  private val _events = Channel<HomeEvent>(Channel.BUFFERED)
  val events: Flow<HomeEvent> = _events.receiveAsFlow()

  /** Live draft list across every surfaced offline queue — re-emits as the sync worker advances
   * statuses, which is what makes both the badge and the open modal update during an upload
   * without any further user action. */
  private val uploadRecords: StateFlow<List<FormUploadRecord>> =
    uploadRecordsSource.observeAll()
      // Fail closed to an empty list rather than crashing the Home screen if the local read errors —
      // this is a read-only status view; the underlying drafts are untouched.
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

  /** Data Upload badge count = drafts not yet uploaded (anything but SYNCED, so FAILED/
   * DUPLICATE_CONFLICT still surface as needing attention). Replaces the former hardcoded value. */
  val pendingUploadCount: StateFlow<Int> =
    uploadRecords
      .map { records -> records.count { it.syncStatus != EnrollmentSyncStatus.SYNCED } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), 0)

  /**
   * Local mother-registration drafts not yet represented server-side — same [uploadRecords] source
   * as [pendingUploadCount], narrowed to `formCode == "MOTHER_REGISTRATION"` and the identical
   * "not yet synced" condition ([EnrollmentSyncStatus.SYNCED] exclusive). [EnrollmentSyncStatus]'s
   * own doc defines SYNCED as exactly the point
   * [org.armman.sakhi.data.enrollment.EnrollmentDraftEntity.remoteBeneficiaryId] /
   * [org.armman.sakhi.data.forms.DynamicFormDraftEntity.remoteBeneficiaryId] gets populated, so this
   * is the same "not yet synced" rule [org.armman.sakhi.data.beneficiary.OfflineFirstBeneficiaryRepository]
   * applies via `remoteBeneficiaryId == null` — just read off the projection that's already reactive
   * here instead of a second Room query.
   *
   * Feeds [uiState]'s overlay: a beneficiary registered while offline shows up in
   * [DashboardSummary.activeMothersCount] the moment her draft is saved, not after the next sync +
   * server refetch.
   */
  private val pendingMotherCount: Flow<Int> =
    uploadRecords.map { records ->
      records.count { it.formCode == MOTHER_REGISTRATION_FORM_CODE && it.syncStatus != EnrollmentSyncStatus.SYNCED }
    }

  /** Same as [pendingMotherCount], for the Children Register queue (`formCode ==
   * "CHILD_REGISTRATION"`) — feeds [DashboardSummary.activeChildrenCount] in [uiState]. */
  private val pendingChildCount: Flow<Int> =
    uploadRecords.map { records ->
      records.count { it.formCode == CHILD_REGISTRATION_FORM_CODE && it.syncStatus != EnrollmentSyncStatus.SYNCED }
    }

  /**
   * Public dashboard state consumed by [HomeScreen]: [_uiState]'s raw server snapshot with
   * [pendingMotherCount]/[pendingChildCount] added onto the three beneficiary-count fields
   * ([DashboardSummary.activeMothersCount], [DashboardSummary.activeChildrenCount],
   * [DashboardSummary.totalActiveBeneficiaries]) via [overlayPendingCounts]. Every other field on
   * [DashboardSummary] — risk/referral/visit-due counts, sakhiName, lastSyncedAt — is left exactly
   * as the server returned it; those genuinely need server-side computation and are out of scope
   * for this overlay.
   *
   * Recomputed fresh from [_uiState] and the current pending counts on every emission rather than
   * writing the overlaid numbers back into [_uiState] itself, so the raw server value [_uiState]
   * holds — and later re-emits verbatim, e.g. from [refreshSummaryQuietly] — is never corrupted by
   * an earlier overlay.
   *
   * [SharingStarted.Eagerly], not the [SUBSCRIPTION_TIMEOUT_MS]-gated `WhileSubscribed` used for
   * [pendingUploadCount]/[uploadModalState] above: those are only read once [HomeScreen] actively
   * collects them, but [uiState] is the primary state the screen (and this class's own callers, and
   * tests reading `.value` directly) expect to be live from construction on — the same always-on
   * contract the plain `_uiState.asStateFlow()` this replaces already had.
   */
  val uiState: StateFlow<HomeUiState> =
    combine(_uiState, pendingMotherCount, pendingChildCount) { state, motherPending, childPending ->
      overlayPendingCounts(state, motherPending, childPending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState.Loading)

  /**
   * Watches [pendingUploadCount] for a sync finishing (count drops back to zero after being above
   * zero) and re-fetches the dashboard summary when it does.
   *
   * [DashboardSummary.lastSyncedAt] -- the "Updated <date>"/"Not yet synced" caption under the Data
   * Upload pill (see [org.armman.sakhi.ui.home.HomeContent]'s `SakhiRow`) -- only ever comes from
   * [dashboardRepository], which this ViewModel otherwise calls just once, in [init]. Starting a
   * sync via [ManualSyncTrigger.syncAllQueues] is deliberately fire-and-forget WorkManager
   * scheduling (see that function's doc) -- nothing about it tells this ViewModel when the upload
   * actually finishes -- so without this, a real successful upload left the caption showing its
   * stale value (or "Not yet synced") for the rest of the session, only catching up the next time
   * the Sakhi left and re-entered Home.
   *
   * The very first emission is recorded as the starting point rather than treated as a completion --
   * otherwise an account with nothing ever queued (count starts and stays at zero) would trigger a
   * spurious reload on launch.
   */
  private fun observeSyncCompletion() {
    viewModelScope.launch {
      var previousPendingCount: Int? = null
      pendingUploadCount.collect { count ->
        val previous = previousPendingCount
        previousPendingCount = count
        if (previous != null && previous > 0 && count == 0) {
          refreshSummaryQuietly()
        }
      }
    }
  }

  /**
   * Re-fetches the dashboard summary after a sync completes, without going through [loadSummary]'s
   * [HomeUiState.Loading] step -- that would blank the whole dashboard behind [HomeScreen]'s
   * full-screen spinner for what should be an invisible background refresh. A failure here is
   * swallowed rather than surfaced: the Sakhi already has a working summary on screen, so there is
   * nothing wrong worth showing her over a background refresh that didn't happen to land.
   */
  private suspend fun refreshSummaryQuietly() {
    val refreshed = try {
      dashboardRepository.getSummary()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    _uiState.value = HomeUiState.Success(refreshed)
  }

  /** Adds [motherPending]/[childPending] onto a [HomeUiState.Success]'s beneficiary-count fields;
   * [HomeUiState.Loading]/[HomeUiState.Error] pass through unchanged since there's no summary to
   * overlay onto. See [uiState]'s doc for why this recomputes rather than mutating [_uiState] in
   * place. */
  private fun overlayPendingCounts(
    state: HomeUiState,
    motherPending: Int,
    childPending: Int,
  ): HomeUiState {
    if (state !is HomeUiState.Success) return state
    if (motherPending == 0 && childPending == 0) return state
    val summary = state.summary
    return state.copy(
      summary = summary.copy(
        activeMothersCount = summary.activeMothersCount + motherPending,
        activeChildrenCount = summary.activeChildrenCount + childPending,
        totalActiveBeneficiaries = summary.totalActiveBeneficiaries + motherPending + childPending,
      ),
    )
  }

  private val _modalVisible = MutableStateFlow(false)

  /**
   * The oldest unanswered new-pregnancy question across the drafts, or null when there is none.
   *
   * One at a time on purpose: each answer creates a real beneficiary record, so they are worth
   * showing deliberately rather than stacking dialogs. Answering one re-emits the list and the next
   * (if any) takes its place.
   */
  val duplicateReview: StateFlow<DuplicateReview?> =
    uploadRecords
      .map { records ->
        records
          .filter { it.syncStatus == EnrollmentSyncStatus.DUPLICATE_CONFLICT }
          .sortedBy { it.createdAtEpochMillis }
          .firstNotNullOfOrNull { record ->
            record.pendingNewPregnancyBeneficiaryId?.let { existingId ->
              DuplicateReview(
                localBeneficiaryId = record.localBeneficiaryId,
                existingBeneficiaryId = existingId,
                submittedAtEpochMillis = record.createdAtEpochMillis,
              )
            }
          }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

  val uploadModalState: StateFlow<UploadModalState> =
    combine(_modalVisible, uploadRecords) { visible, records ->
      UploadModalState(isVisible = visible, records = records)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), UploadModalState())

  /** Raw feed from the last successful fetch — see [NotificationRepository.getNotifications]'s
   * doc for why a failed fetch resolves to an empty list here instead of an error state. */
  private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())

  /** Ids the Sakhi has tapped the X on. In-memory only, cleared on process death / a fresh
   * [loadNotifications] finding the row no longer present — there is no confirmed backend
   * dismiss/mark-read mutation yet (CR-Notification-Escalation backend contract answers,
   * 2026-09-02, item 5's PATCH option isn't confirmed live), so nothing is persisted server-side.
   * This is a deliberate placeholder: once that endpoint exists, dismiss should call it instead of
   * (or in addition to) filtering locally. */
  private val _dismissedNotificationIds = MutableStateFlow<Set<String>>(emptySet())

  /** [_notifications] minus anything [_dismissedNotificationIds] hid, sorted by
   * [AppNotification.srsStackRank] — the real SRS FR-S-7.2 stacking order, not the raw backend
   * `priority` int (unenforced on the DB side per backend, 2026-09-02). */
  val notifications: StateFlow<List<AppNotification>> =
    combine(_notifications, _dismissedNotificationIds) { all, dismissed ->
      all.filterNot { it.id in dismissed }.sortedBy { it.srsStackRank }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

  /** Fetches the notification feed. Best-effort and independent of [loadSummary] — a notification
   * failure must never block or error out the dashboard summary (see
   * [NotificationRepository.getNotifications]'s doc), so this has no shared loading/error state
   * with [uiState]. */
  fun loadNotifications() {
    viewModelScope.launch {
      _notifications.value = notificationRepository.getNotifications()
    }
  }

  /** Local-only dismiss — see [_dismissedNotificationIds]'s doc for why nothing is persisted
   * server-side yet. */
  fun onDismissNotification(notification: AppNotification) {
    _dismissedNotificationIds.value = _dismissedNotificationIds.value + notification.id
  }

  /**
   * The "Fill Referral Form" CTA (backend-confirmed 2026-09-02: only ever set — `ctaType ==
   * "FILL_REFERRAL_FORM"` — on a `REFERRAL_INCOMPLETE_UPDATE` notification when the Supervisor
   * rejected the follow-up; null on that same type when it was approved/lapsed instead, and null
   * on every other notification type).
   *
   * The notification's [AppNotification.linkedEntityId] is the **referral id**, not a beneficiary
   * id — but the Referral Follow-up ad-hoc form route needs both
   * ([org.armman.sakhi.ui.navigation.Routes.adHocForm]). Backend confirmed there is no
   * `GET /referrals/{id}` lookup; the only way to resolve a referral id to its beneficiary id is
   * to search [ReferralRepository.getPendingFollowUps] (`GET
   * /sakhi/{sakhiId}/referrals/pending-followup`) for the matching row — built on the assumption
   * that a rejected-follow-up referral still appears in that pending list. If it doesn't (list is
   * stale, or the assumption turns out wrong for some referral state), this surfaces
   * [HomeEvent.ReferralFollowUpNotFound] rather than navigating with a guessed/blank beneficiary
   * id.
   */
  fun onFillReferralFormClicked(notification: AppNotification) {
    if (notification.type != NOTIFICATION_TYPE_REFERRAL_INCOMPLETE_UPDATE) return
    if (notification.ctaType != NOTIFICATION_CTA_FILL_REFERRAL_FORM) return
    val referralId = notification.linkedEntityId ?: return
    viewModelScope.launch {
      val match = try {
        referralRepository.getPendingFollowUps().firstOrNull { it.referralId == referralId }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      _events.trySend(
        if (match != null) {
          HomeEvent.NavigateToReferralFollowUp(beneficiaryId = match.beneficiaryId, referralId = referralId)
        } else {
          HomeEvent.ReferralFollowUpNotFound
        },
      )
    }
  }

  init {
    loadSummary()
    observeSyncCompletion()
    loadNotifications()
  }

  /** Loads (or reloads after an error) the dashboard summary. */
  fun loadSummary() {
    _uiState.value = HomeUiState.Loading
    viewModelScope.launch {
      _uiState.value = try {
        HomeUiState.Success(dashboardRepository.getSummary())
      } catch (e: CancellationException) {
        // Cancellation is not a failure: it means this coroutine's scope is going away (the Sakhi
        // navigated off the dashboard mid-load). Swallowing it into HomeUiState.Error would both
        // break structured concurrency and paint a spurious error on a screen that is leaving.
        throw e
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        HomeUiState.Error
      }
    }
  }

  /**
   * The Data Upload pill: **starts the upload and opens the progress modal**, in that order.
   *
   * This is the app's only manual sync trigger (SRS §3A.1 — *"Data Sync — Manual trigger"*), and
   * per the Figma "Data Sync Behaviour" board the modal itself carries no action button: the pill
   * tap is the action, and the modal is the live progress view of it. Every tap really does start an
   * attempt (the schedulers use `ExistingWorkPolicy.REPLACE`, never `KEEP`), and re-attempting an
   * in-flight draft is safe because both API calls are idempotent on their local UUIDs — so this
   * doubles as the retry affordance for FAILED drafts, which is why the modal needs no button.
   *
   * Sync starts before the modal is shown so the first frame the Sakhi sees already reflects work
   * in progress rather than a stale PENDING list.
   *
   * Offline guard (bharath, 2026-08-08): a one-shot [ConnectivityChecker.isOnline] check gates
   * both of those steps. Offline, [manualSyncTrigger] is never called and the modal never opens —
   * previously both happened unconditionally, so an offline tap showed the same "uploading"
   * progress modal as a real attempt, with nothing to actually show progress on. Online behaviour
   * (including the WorkManager retry-as-you-go semantics described above) is unchanged.
   */
  fun onDataUploadClicked() {
    if (!connectivityChecker.isOnline()) {
      _events.trySend(HomeEvent.OfflineUploadBlocked)
      return
    }
    manualSyncTrigger.syncAllQueues()
    _modalVisible.value = true
  }

  /** Dismisses the modal. */
  fun onDismissUploadModal() {
    _modalVisible.value = false
  }

  /**
   * The Sakhi confirmed that a rejected draft really is a new pregnancy. The acknowledgement is
   * persisted on the draft and the upload retried, so the confirmation isn't lost if the retry fails
   * or the device is offline.
   *
   * The prompt disappears on its own: [confirmNewPregnancy] clears the stored prompt, which
   * re-emits [uploadRecords] and empties [duplicateReview].
   */
  fun onConfirmNewPregnancy(review: DuplicateReview) {
    viewModelScope.launch {
      dynamicFormDraftRepository.confirmNewPregnancy(
        localBeneficiaryId = review.localBeneficiaryId,
        existingBeneficiaryId = review.existingBeneficiaryId,
      )
    }
  }

  /** The Sakhi declined. The draft and its answers are left as they are — only the question is
   * cleared, so it stops reappearing after every upload. */
  fun onDismissDuplicateReview(review: DuplicateReview) {
    viewModelScope.launch {
      dynamicFormDraftRepository.dismissNewPregnancyPrompt(review.localBeneficiaryId)
    }
  }
}
