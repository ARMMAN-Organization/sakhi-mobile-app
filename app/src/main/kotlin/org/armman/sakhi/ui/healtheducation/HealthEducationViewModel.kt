package org.armman.sakhi.ui.healtheducation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.healtheducation.HealthEducationRepository
import org.armman.sakhi.data.healtheducation.HealthEducationTopic
import javax.inject.Inject

/** One triggered condition + its resolved "Learn More" topic — [HealthEducationRepository]
 * always resolves every requested code to *something* (the real content, or the seeded
 * `COMING_SOON` placeholder), so there is no separate "unresolved" card state to model here. */
data class HealthEducationCard(
  val conditionCode: String,
  val topic: HealthEducationTopic,
)

data class HealthEducationUiState(
  val isLoading: Boolean = true,
  val cards: List<HealthEducationCard> = emptyList(),
)

/**
 * Post-submission Health Education screen (CR-M3-06 requirement #5). REWRITTEN 2026-08-28 against
 * backend's confirmed contract (`GET /beneficiaries/{beneficiaryId}/risk`) — see
 * [org.armman.sakhi.data.healtheducation.HealthEducationApi]'s class doc for what changed. PRD
 * ordering is Health Education → Referral; referral capture in this app happens as an in-form
 * step *before* the network submit (see [org.armman.sakhi.ui.visitform.DynamicVisitFormUiState
 * .showReferralCaptureStep]'s doc), so by the time this screen is reached that step has already
 * run — this screen only ever needs to show education content.
 */
@HiltViewModel
class HealthEducationViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val repository: HealthEducationRepository,
) : ViewModel() {

  private val beneficiaryId: String = savedStateHandle.get<String>(NAV_ARG_BENEFICIARY_ID).orEmpty()

  /** [org.armman.sakhi.data.rules.RiskConditionIds.ANC]/`.INFANT` map KEYS (e.g. `"JAUNDICE"`),
   * NOT the UUID `riskConditionId` — see [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen]'s
   * `routeAfterSubmit` for where these are resolved from the on-device grading result. */
  // Nav Compose already URL-decodes path segments before they land in SavedStateHandle (same as
  // every other nav arg in this app — beneficiaryId/visitId/label are never manually decoded
  // either), so no Uri.decode() here. That call was removed after it turned out to crash this
  // ViewModel's own unit tests: this project's JVM unit tests run with
  // `isReturnDefaultValues = true` (no Robolectric), under which android.net.Uri's stub methods
  // return null instead of throwing, and a null from Uri.decode() then NPEs the very next
  // .isNotBlank() call — see HealthEducationViewModelTest for the tests this fix was needed for.
  private val conditionCodes: List<String> =
    (savedStateHandle.get<String>(NAV_ARG_CONDITION_CODES) ?: "")
      .split(",")
      .filter { it.isNotBlank() }
      .distinct()

  private val _uiState = MutableStateFlow(HealthEducationUiState())
  val uiState: StateFlow<HealthEducationUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      if (conditionCodes.isEmpty() || beneficiaryId.isBlank()) {
        _uiState.update { it.copy(isLoading = false, cards = emptyList()) }
        return@launch
      }
      val topicsByCode = repository.getEducationContentForBeneficiary(beneficiaryId, conditionCodes.toSet())
      // Preserve the order conditions were triggered in (the order the visit form found them).
      val cards = conditionCodes.mapNotNull { code -> topicsByCode[code]?.let { HealthEducationCard(code, it) } }
      _uiState.update { it.copy(isLoading = false, cards = cards) }
    }
  }

  companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_CONDITION_CODES = "conditionCodes"
  }
}
