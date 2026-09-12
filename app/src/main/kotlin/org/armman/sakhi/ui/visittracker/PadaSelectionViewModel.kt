package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.visittracker.LocalPadaSummaryOverlay
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
  private val localPadaSummaryOverlay: LocalPadaSummaryOverlay,
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
        val serverSummaries = padaRepository.getPadaSummaries()
        // Bharath, 2026-09-10: a successful-but-stale response (nothing thrown, counts just not
        // caught up with a schedule this device generated/synced since) still needs the same
        // local top-up the full-failure path below already gets — see
        // [LocalPadaSummaryOverlay.mergeWithLocal]'s doc for why max-per-bucket is safe here.
        allSummaries = try {
          localPadaSummaryOverlay.mergeWithLocal(serverSummaries)
        } catch (mergeError: Exception) {
          serverSummaries
        }
        _uiState.update { it.copy(isLoading = false, totalPadas = allSummaries.size) }
        refreshList()
      } catch (e: Exception) {
        // CR-VisitTracker offline overlay (bharath, 2026-09-10): the server has never returned
        // anything for this Sakhi's pada list AND nothing was ever cached (RemotePadaRepository
        // only throws in that exact case) — e.g. a fresh install/device taken offline before its
        // first successful load. Build the pada list from this device's own open visits/pending
        // referrals instead of leaving the screen blank; see [LocalPadaSummaryOverlay]'s doc for
        // why this is a full-replacement fallback rather than an additive overlay like the
        // dashboard's.
        val localSummaries = try {
          localPadaSummaryOverlay.buildPadaSummaries()
        } catch (inner: Exception) {
          emptyList()
        }
        if (localSummaries.isEmpty()) {
          // Generic error state for the UI; technical detail must not leak to users.
          _uiState.update { it.copy(isLoading = false, hasError = true) }
        } else {
          allSummaries = localSummaries
          _uiState.update { it.copy(isLoading = false, hasError = false, totalPadas = allSummaries.size) }
          refreshList()
        }
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
