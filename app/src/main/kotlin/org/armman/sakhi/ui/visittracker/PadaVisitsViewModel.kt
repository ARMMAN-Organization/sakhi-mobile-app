package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visit.VisitType
import javax.inject.Inject

/**
 * UI state for a single pada's visit list. [visitsByType] holds both tabs'
 * filtered lists so the swipe pager can show the adjacent page's real content.
 */
data class PadaVisitsUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val pada: String = "",
  val selectedTab: VisitType = VisitType.OPEN,
  val searchQuery: String = "",
  val visitsByType: Map<VisitType, List<Visit>> = emptyMap(),
  val openCount: Int = 0,
  val referralCount: Int = 0,
) {
  /** Convenience: the currently selected tab's filtered list. */
  val visits: List<Visit>
    get() = visitsByType[selectedTab].orEmpty()
}

@HiltViewModel
class PadaVisitsViewModel @Inject constructor(
  private val visitRepository: VisitRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val pada: String = savedStateHandle[NAV_ARG_PADA] ?: ""

  private val _uiState = MutableStateFlow(PadaVisitsUiState(pada = pada))
  val uiState: StateFlow<PadaVisitsUiState> = _uiState.asStateFlow()

  private var padaVisits: List<Visit> = emptyList()

  init {
    loadVisits()
  }

  /** Loads (or reloads after an error) this pada's visits for today. */
  fun loadVisits() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        padaVisits = visitRepository.getTodaysVisits().filter { it.pada == pada }
        _uiState.update { state ->
          state.copy(
            isLoading = false,
            openCount = padaVisits.count { it.visitType == VisitType.OPEN },
            referralCount = padaVisits.count { it.visitType == VisitType.REFERRAL_FOLLOWUP },
          )
        }
        refreshList()
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      }
    }
  }

  fun onTabSelected(tab: VisitType) {
    _uiState.update { it.copy(selectedTab = tab) }
    refreshList()
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    refreshList()
  }

  private fun refreshList() {
    val s = _uiState.value
    val matchesQuery = { visit: Visit ->
      s.searchQuery.isBlank() ||
        visit.beneficiaryName.contains(s.searchQuery.trim(), ignoreCase = true)
    }
    val lists = VisitType.entries.associateWith { type ->
      padaVisits.filter { it.visitType == type && matchesQuery(it) }
    }
    _uiState.update { it.copy(visitsByType = lists) }
  }

  companion object {
    const val NAV_ARG_PADA = "pada"
  }
}
