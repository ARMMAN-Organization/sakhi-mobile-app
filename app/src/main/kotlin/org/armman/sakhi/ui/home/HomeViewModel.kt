package org.armman.sakhi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.FormUploadRecord
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

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val dashboardRepository: DashboardRepository,
  private val uploadRecordsSource: UploadRecordsSource,
  private val manualSyncTrigger: ManualSyncTrigger,
  private val dynamicFormDraftRepository: DynamicFormDraftRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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

  init {
    loadSummary()
  }

  /** Loads (or reloads after an error) the dashboard summary. */
  fun loadSummary() {
    _uiState.value = HomeUiState.Loading
    viewModelScope.launch {
      _uiState.value = try {
        HomeUiState.Success(dashboardRepository.getSummary())
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
   */
  fun onDataUploadClicked() {
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
