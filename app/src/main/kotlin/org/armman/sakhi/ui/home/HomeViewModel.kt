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
import javax.inject.Inject

/** UI state for the Home dashboard — loading, error and success. */
sealed interface HomeUiState {
  data object Loading : HomeUiState
  data class Success(val summary: DashboardSummary) : HomeUiState
  data object Error : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val dashboardRepository: DashboardRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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
}
