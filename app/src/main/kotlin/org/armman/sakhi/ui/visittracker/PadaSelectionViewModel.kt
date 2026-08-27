package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.visittracker.PadaRepository
import org.armman.sakhi.data.visittracker.PadaSummary
import javax.inject.Inject

/** UI state for the pada selection screen. */
data class PadaSelectionUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val searchQuery: String = "",
  val padaCards: List<PadaSummary> = emptyList(),
  val totalPadas: Int = 0,
)

/**
 * M3: consumes [PadaRepository]'s pre-aggregated per-pada counts directly — the previous
 * client-side aggregation over [org.armman.sakhi.data.visit.VisitRepository]'s flat today's-visits
 * list is gone now that the backend returns these counts itself.
 */
@HiltViewModel
class PadaSelectionViewModel @Inject constructor(
  private val padaRepository: PadaRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(PadaSelectionUiState())
  val uiState: StateFlow<PadaSelectionUiState> = _uiState.asStateFlow()

  private var allSummaries: List<PadaSummary> = emptyList()

  init {
    loadVisits()
  }

  /** Loads (or reloads after an error) the pada summaries. */
  fun loadVisits() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        allSummaries = padaRepository.getPadaSummaries()
        _uiState.update { it.copy(isLoading = false, totalPadas = allSummaries.size) }
        refreshList()
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      }
    }
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    refreshList()
  }

  private fun refreshList() {
    val query = _uiState.value.searchQuery.trim()
    val filtered = if (query.isBlank()) {
      allSummaries
    } else {
      allSummaries.filter { it.padaName.contains(query, ignoreCase = true) }
    }
    _uiState.update { it.copy(padaCards = filtered) }
  }
}
