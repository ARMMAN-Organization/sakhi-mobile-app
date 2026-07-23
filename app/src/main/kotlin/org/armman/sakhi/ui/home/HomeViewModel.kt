package org.armman.sakhi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
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
  val isLoading: Boolean = false,
  val records: List<FormUploadRecord> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val dashboardRepository: DashboardRepository,
  private val dynamicFormDraftRepository: DynamicFormDraftRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  private val _uploadModalState = MutableStateFlow(UploadModalState())
  val uploadModalState: StateFlow<UploadModalState> = _uploadModalState.asStateFlow()

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

  /** Opens the "Forms Uploaded" modal and loads the current CR-018 draft list. */
  fun onDataUploadClicked() {
    _uploadModalState.value = UploadModalState(isVisible = true, isLoading = true)
    viewModelScope.launch {
      val records = try {
        dynamicFormDraftRepository.getUploadRecords()
      } catch (e: Exception) {
        // Fail closed to an empty list rather than crashing the modal — the Sakhi can dismiss
        // and reopen; the underlying data is untouched (this is a read-only view).
        emptyList()
      }
      _uploadModalState.value = UploadModalState(isVisible = true, isLoading = false, records = records)
    }
  }

  /** Dismisses the modal; clears records so a stale list doesn't flash on the next open. */
  fun onDismissUploadModal() {
    _uploadModalState.value = UploadModalState(isVisible = false)
  }
}
