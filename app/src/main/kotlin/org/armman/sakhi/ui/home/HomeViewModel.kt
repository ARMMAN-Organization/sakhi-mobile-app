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
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
import org.armman.sakhi.data.forms.FormUploadRecord
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

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val dashboardRepository: DashboardRepository,
  private val dynamicFormDraftRepository: DynamicFormDraftRepository,
  private val syncScheduler: DynamicFormSyncScheduler,
) : ViewModel() {

  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  /** Live draft list from Room — re-emits as the sync worker advances statuses, which is what
   * makes both the badge and the open modal update without any user action. */
  private val uploadRecords: StateFlow<List<FormUploadRecord>> =
    dynamicFormDraftRepository.observeUploadRecords()
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

  /** Opens the "Forms Uploaded" modal. Records come live from [uploadRecords]; no manual load. */
  fun onDataUploadClicked() {
    _modalVisible.value = true
  }

  /** Immediately re-attempts upload of every pending/failed draft. Enqueues the sync worker (which
   * runs as soon as there's connectivity); the modal reflects progress live via [uploadRecords]. */
  fun onRetryUpload() {
    syncScheduler.syncNow()
  }

  /** Dismisses the modal. */
  fun onDismissUploadModal() {
    _modalVisible.value = false
  }
}
