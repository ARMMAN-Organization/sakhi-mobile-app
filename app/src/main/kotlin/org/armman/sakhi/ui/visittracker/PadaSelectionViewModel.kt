package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visit.VisitType
import javax.inject.Inject

/** Per-pada aggregate for the selection screen's cards. */
data class PadaVisitSummary(
  val pada: String,
  val openWomen: Int,
  val openWomenEnding: Int,
  val openChildren: Int,
  val referralWomen: Int,
  val referralChildren: Int,
) {
  val remainingVisits: Int
    get() = openWomen + openChildren + referralWomen + referralChildren
}

/** UI state for the pada selection screen. */
data class PadaSelectionUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val searchQuery: String = "",
  val padaCards: List<PadaVisitSummary> = emptyList(),
  val totalPadas: Int = 0,
)

@HiltViewModel
class PadaSelectionViewModel @Inject constructor(
  private val visitRepository: VisitRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(PadaSelectionUiState())
  val uiState: StateFlow<PadaSelectionUiState> = _uiState.asStateFlow()

  private var allSummaries: List<PadaVisitSummary> = emptyList()

  init {
    loadVisits()
  }

  /** Loads (or reloads after an error) today's visits and aggregates per pada. */
  fun loadVisits() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        val visits = visitRepository.getTodaysVisits()
        allSummaries = aggregateByPada(visits)
        _uiState.update {
          it.copy(isLoading = false, totalPadas = allSummaries.size)
        }
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
      allSummaries.filter { it.pada.contains(query, ignoreCase = true) }
    }
    _uiState.update { it.copy(padaCards = filtered) }
  }

  private fun aggregateByPada(visits: List<Visit>): List<PadaVisitSummary> =
    visits
      .groupBy { it.pada }
      .map { (pada, padaVisits) ->
        val open = padaVisits.filter { it.visitType == VisitType.OPEN }
        val referral = padaVisits.filter { it.visitType == VisitType.REFERRAL_FOLLOWUP }
        PadaVisitSummary(
          pada = pada,
          openWomen = open.count { it.beneficiaryType == BeneficiaryType.MOTHER },
          openWomenEnding = open.count {
            it.beneficiaryType == BeneficiaryType.MOTHER && it.isEnding
          },
          openChildren = open.count { it.beneficiaryType == BeneficiaryType.INFANT },
          referralWomen = referral.count { it.beneficiaryType == BeneficiaryType.MOTHER },
          referralChildren = referral.count { it.beneficiaryType == BeneficiaryType.INFANT },
        )
      }
      .sortedBy { it.pada }
}
