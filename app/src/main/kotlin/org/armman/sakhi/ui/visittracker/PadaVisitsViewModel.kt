package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visit.VisitStatus
import org.armman.sakhi.data.visit.VisitType
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 500L

/**
 * UI state for a single pada's visit list. [visitsByType] holds both tabs' lists so the swipe
 * pager can show the adjacent page's real content.
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
  /** Convenience: the currently selected tab's list. */
  val visits: List<Visit>
    get() = visitsByType[selectedTab].orEmpty()
}

/**
 * Backed by `GET /padas/{padaId}/visits`: one call per tab (each returns both tabs' counts, but
 * only its own tab's rows), refetched together on load, retry, and search. Search is exact-match
 * server-side (encrypted names — no partial/fuzzy match), so typing is debounced rather than
 * filtered live like the old client-side mock.
 */
@HiltViewModel
class PadaVisitsViewModel @Inject constructor(
  private val visitRepository: VisitRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val padaId: String = savedStateHandle[NAV_ARG_PADA_ID] ?: ""
  private val padaName: String = savedStateHandle[NAV_ARG_PADA_NAME] ?: ""

  private val _uiState = MutableStateFlow(PadaVisitsUiState(pada = padaName))
  val uiState: StateFlow<PadaVisitsUiState> = _uiState.asStateFlow()

  /** Emits every keystroke; [SEARCH_DEBOUNCE_MS] later the latest value triggers a real reload. */
  private val searchQueryChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)

  init {
    loadVisits()
    viewModelScope.launch {
      searchQueryChanges
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .collect { fetchVisits(it) }
    }
  }

  /** Loads (or reloads after an error/retry) both tabs for the current search query. */
  fun loadVisits() {
    viewModelScope.launch { fetchVisits(_uiState.value.searchQuery) }
  }

  fun onTabSelected(tab: VisitType) {
    _uiState.update { it.copy(selectedTab = tab) }
  }

  /** Updates the input immediately; the network reload is debounced (see [searchQueryChanges]). */
  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    searchQueryChanges.tryEmit(query)
  }

  private suspend fun fetchVisits(search: String) {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    try {
      val trimmedSearch = search.trim().takeIf { it.isNotBlank() }
      val open = visitRepository.getVisits(padaId, VisitStatus.OPEN, search = trimmedSearch)
      val referral = visitRepository.getVisits(padaId, VisitStatus.REFERRAL_FOLLOW_UP, search = trimmedSearch)
      _uiState.update {
        it.copy(
          isLoading = false,
          openCount = open.openCount,
          referralCount = open.referralFollowUpCount,
          visitsByType = mapOf(
            VisitType.OPEN to open.visits,
            VisitType.REFERRAL_FOLLOWUP to referral.visits,
          ),
        )
      }
    } catch (e: Exception) {
      // Generic error state for the UI; technical detail must not leak to users.
      _uiState.update { it.copy(isLoading = false, hasError = true) }
    }
  }

  companion object {
    const val NAV_ARG_PADA_ID = "padaId"
    const val NAV_ARG_PADA_NAME = "padaName"
  }
}
