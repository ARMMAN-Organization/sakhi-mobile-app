package org.armman.sakhi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the Home screen — covers loading, error, empty and success. */
sealed interface HomeUiState {
  data object Idle : HomeUiState
  data object Syncing : HomeUiState
  data object Synced : HomeUiState
  data class Error(val message: String) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  fun onSyncClicked() {
    viewModelScope.launch {
      _uiState.value = HomeUiState.Syncing
      try {
        // TODO: invoke sync repository once the data layer exists.
        _uiState.value = HomeUiState.Synced
      } catch (e: Exception) {
        _uiState.value = HomeUiState.Error(e.message ?: "Sync failed")
      }
    }
  }
}
