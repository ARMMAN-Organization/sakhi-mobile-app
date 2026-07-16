package org.armman.sakhi.ui.previsithealthhistory

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
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistory
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistoryRepository
import javax.inject.Inject

/** UI state for the Pre-Visit Health History screen (FR-S-4.6). */
data class PreVisitHealthHistoryUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val profile: BeneficiaryProfile? = null,
  val history: PreVisitHealthHistory? = null,
)

/** One-shot navigation events — the destinations themselves are wired by the NavHost. */
sealed interface PreVisitHealthHistoryEvent {
  data object NavigateToProfile : PreVisitHealthHistoryEvent
  data object NavigateToVisitForm : PreVisitHealthHistoryEvent
}

@HiltViewModel
class PreVisitHealthHistoryViewModel @Inject constructor(
  private val profileRepository: BeneficiaryProfileRepository,
  private val historyRepository: PreVisitHealthHistoryRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val beneficiaryId: String = savedStateHandle[NAV_ARG_BENEFICIARY_ID] ?: ""
  private val visitId: String = savedStateHandle[NAV_ARG_VISIT_ID] ?: ""

  private val _uiState = MutableStateFlow(PreVisitHealthHistoryUiState())
  val uiState: StateFlow<PreVisitHealthHistoryUiState> = _uiState.asStateFlow()

  private val _events = Channel<PreVisitHealthHistoryEvent>(Channel.BUFFERED)
  val events: Flow<PreVisitHealthHistoryEvent> = _events.receiveAsFlow()

  init {
    load()
  }

  /** Loads (or reloads after an error) the beneficiary header + trend data. */
  fun load() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        // Blank ids mean the screen was opened without its nav arguments.
        require(beneficiaryId.isNotBlank() && visitId.isNotBlank()) {
          "Missing beneficiary id or visit id"
        }
        val profile = profileRepository.getBeneficiary(beneficiaryId)
        val history = historyRepository.getHealthHistory(beneficiaryId, visitId)
        _uiState.update { it.copy(isLoading = false, profile = profile, history = history) }
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      }
    }
  }

  fun onSeeProfile() {
    _events.trySend(PreVisitHealthHistoryEvent.NavigateToProfile)
  }

  fun onStartVisit() {
    _events.trySend(PreVisitHealthHistoryEvent.NavigateToVisitForm)
  }

  companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_VISIT_ID = "visitId"
  }
}
