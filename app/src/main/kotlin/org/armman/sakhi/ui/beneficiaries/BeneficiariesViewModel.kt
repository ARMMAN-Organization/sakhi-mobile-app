package org.armman.sakhi.ui.beneficiaries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryRepository
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiary.VisitState
import java.time.YearMonth
import javax.inject.Inject

/** Sub-tabs of the Active tab; ALL disables visit-state filtering. */
enum class VisitSubTab { ALL, OPEN, PENDING_REFERRAL, MISSED }

/** Which filter popup is currently open, if any. */
enum class OpenFilter { NONE, PADA, RISK }

/**
 * UI state for My Beneficiaries. [beneficiaries] already has every active
 * filter applied; option lists feed the filter popups.
 */
data class BeneficiariesUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val beneficiaries: List<Beneficiary> = emptyList(),
  val selectedTab: BeneficiaryStatus = BeneficiaryStatus.ACTIVE,
  val selectedSubTab: VisitSubTab = VisitSubTab.ALL,
  val searchQuery: String = "",
  val padaOptions: List<String> = emptyList(),
  val selectedPadas: Set<String> = emptySet(),
  val selectedRisks: Set<RiskLevel> = emptySet(),
  val monthOptions: List<YearMonth> = emptyList(),
  val selectedMonth: YearMonth? = null,
  val openFilter: OpenFilter = OpenFilter.NONE,
)

@HiltViewModel
class BeneficiariesViewModel @Inject constructor(
  private val beneficiaryRepository: BeneficiaryRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(BeneficiariesUiState())
  val uiState: StateFlow<BeneficiariesUiState> = _uiState.asStateFlow()

  private var allBeneficiaries: List<Beneficiary> = emptyList()

  init {
    loadBeneficiaries()
  }

  /** Loads (or reloads after an error) the beneficiary list. */
  fun loadBeneficiaries() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        allBeneficiaries = beneficiaryRepository.getBeneficiaries()
        _uiState.update { state ->
          state.copy(
            isLoading = false,
            padaOptions = allBeneficiaries.map { it.pada }.distinct().sorted(),
            monthOptions = allBeneficiaries
              .mapNotNull { it.journeyCompletedIn }
              .distinct()
              .sortedDescending(),
          )
        }
        refreshList()
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      }
    }
  }

  fun onTabSelected(tab: BeneficiaryStatus) {
    // Sub-tab resets on tab switch; Pada/Risk selections deliberately persist.
    _uiState.update { it.copy(selectedTab = tab, selectedSubTab = VisitSubTab.ALL) }
    refreshList()
  }

  fun onSubTabSelected(subTab: VisitSubTab) {
    _uiState.update { it.copy(selectedSubTab = subTab) }
    refreshList()
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    refreshList()
  }

  fun onMonthSelected(month: YearMonth?) {
    _uiState.update { it.copy(selectedMonth = month) }
    refreshList()
  }

  fun onOpenFilter(filter: OpenFilter) {
    _uiState.update { it.copy(openFilter = filter) }
  }

  fun onTogglePada(pada: String) {
    _uiState.update {
      it.copy(selectedPadas = it.selectedPadas.toggle(pada))
    }
  }

  fun onToggleRisk(risk: RiskLevel) {
    _uiState.update {
      it.copy(selectedRisks = it.selectedRisks.toggle(risk))
    }
  }

  fun onApplyFilters() {
    _uiState.update { it.copy(openFilter = OpenFilter.NONE) }
    refreshList()
  }

  fun onClearFilter(filter: OpenFilter) {
    _uiState.update {
      when (filter) {
        OpenFilter.PADA -> it.copy(selectedPadas = emptySet())
        OpenFilter.RISK -> it.copy(selectedRisks = emptySet())
        OpenFilter.NONE -> it
      }
    }
    refreshList()
  }

  /** Applies tab, sub-tab, search, pada, risk and month filters (AND semantics). */
  private fun refreshList() {
    val s = _uiState.value
    val filtered = allBeneficiaries.filter { b ->
      b.status == s.selectedTab &&
        (s.selectedTab != BeneficiaryStatus.ACTIVE || s.selectedSubTab.matches(b.visitState)) &&
        (s.searchQuery.isBlank() || b.name.contains(s.searchQuery.trim(), ignoreCase = true)) &&
        (s.selectedPadas.isEmpty() || b.pada in s.selectedPadas) &&
        (s.selectedRisks.isEmpty() || b.riskLevel in s.selectedRisks) &&
        (
          s.selectedTab != BeneficiaryStatus.JOURNEY_COMPLETE ||
            s.selectedMonth == null ||
            b.journeyCompletedIn == s.selectedMonth
          )
    }
    _uiState.update { it.copy(beneficiaries = filtered) }
  }

  private fun VisitSubTab.matches(visitState: VisitState?): Boolean = when (this) {
    VisitSubTab.ALL -> true
    VisitSubTab.OPEN -> visitState == VisitState.OPEN
    VisitSubTab.PENDING_REFERRAL -> visitState == VisitState.PENDING_REFERRAL
    VisitSubTab.MISSED -> visitState == VisitState.MISSED
  }

  private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
}
