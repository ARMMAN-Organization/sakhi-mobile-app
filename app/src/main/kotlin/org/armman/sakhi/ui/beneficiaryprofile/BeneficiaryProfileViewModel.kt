package org.armman.sakhi.ui.beneficiaryprofile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import javax.inject.Inject

/** UI state for the Beneficiary Profile screen. */
data class BeneficiaryProfileUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val profile: BeneficiaryProfile? = null,
)

@HiltViewModel
class BeneficiaryProfileViewModel @Inject constructor(
  private val repository: BeneficiaryProfileRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val beneficiaryId: String = savedStateHandle[NAV_ARG_ID] ?: ""

  private val _uiState = MutableStateFlow(BeneficiaryProfileUiState())
  val uiState: StateFlow<BeneficiaryProfileUiState> = _uiState.asStateFlow()

  init {
    loadProfile()
  }

  /** Loads (or reloads after an error) the beneficiary detail for this id. */
  fun loadProfile() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        // A blank id means the screen was opened without its nav argument.
        require(beneficiaryId.isNotBlank()) { "Missing beneficiary id" }
        val profile = repository.getBeneficiary(beneficiaryId)
        _uiState.update { it.copy(isLoading = false, profile = profile) }
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true, profile = null) }
      }
    }
  }

  companion object {
    const val NAV_ARG_ID = "id"
  }
}
