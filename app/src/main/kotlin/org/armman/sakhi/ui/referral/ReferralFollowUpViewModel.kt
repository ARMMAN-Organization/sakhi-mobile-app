package org.armman.sakhi.ui.referral

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
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.referral.ReferralLinkEntity
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.referral.ReferralStatus
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

private const val CATEGORY_REFERRAL_TYPE = "REFERRAL_TYPE"
private const val VALUE_CODE_STANDARD = "STANDARD"

/**
 * CR-Referral-01: state for the referral follow-up form opened from a Beneficiary Profile visit
 * card whose action is "Fill Form" because [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit
 * .referralIncomplete] is true (see [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileScreen]'s
 * routing).
 *
 * [canConvertToAccompanied] gates the Standard→Accompanied action per the requirement's own
 * rules: only while the referral is still [ReferralStatus.PENDING_FOLLOWUP], only within the
 * original 7-day [ReferralLinkEntity.validTill] window (no extension — backend-confirmed), and
 * only when the referral is currently Standard (converting an already-Accompanied referral
 * returns `409`, so the app hides the action rather than let the Sakhi hit that error). Resolved
 * once on load, not re-checked on every recomposition — `now` moving past `validTill` mid-session
 * is an edge case the Sakhi can just retry after a refresh, same tolerance every other
 * window-gated action in this app already has.
 */
data class ReferralFollowUpUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val referralId: String = "",
  val status: ReferralStatus = ReferralStatus.UNKNOWN,
  val canConvertToAccompanied: Boolean = false,
  val visitedFacility: Boolean? = null,
  val followupDate: LocalDate? = null,
  val notVisitedReason: String = "",
  val diagnosis: String = "",
  val treatmentGiven: String = "",
  val outcome: String = "",
  val isSubmitting: Boolean = false,
  val isConverting: Boolean = false,
)

sealed interface ReferralFollowUpEvent {
  data object SubmittedSuccessfully : ReferralFollowUpEvent
  data class SubmitFailed(val message: String) : ReferralFollowUpEvent
  data object ConvertedSuccessfully : ReferralFollowUpEvent
  data class ConvertFailed(val message: String) : ReferralFollowUpEvent
}

@HiltViewModel
class ReferralFollowUpViewModel @Inject constructor(
  private val referralRepository: ReferralRepository,
  private val referralLinkDao: ReferralLinkDao,
  private val lookupRepository: LookupRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val localScheduleUuid: String = savedStateHandle[NAV_ARG_LOCAL_SCHEDULE_UUID] ?: ""
  private val referralId: String = savedStateHandle[NAV_ARG_REFERRAL_ID] ?: ""

  private val _uiState = MutableStateFlow(ReferralFollowUpUiState(referralId = referralId))
  val uiState: StateFlow<ReferralFollowUpUiState> = _uiState.asStateFlow()

  private val _events = Channel<ReferralFollowUpEvent>(Channel.BUFFERED)
  val events: Flow<ReferralFollowUpEvent> = _events.receiveAsFlow()

  init {
    load()
  }

  private fun load() {
    viewModelScope.launch {
      val link = referralLinkDao.getByLocalScheduleUuid(localScheduleUuid)
      if (link == null) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val status = runCatching { ReferralStatus.valueOf(link.status) }.getOrDefault(ReferralStatus.UNKNOWN)
      val standardLookupId = lookupRepository.findValue(CATEGORY_REFERRAL_TYPE, VALUE_CODE_STANDARD)?.id
      val withinWindow = link.validTill
        ?.let { runCatching { !Instant.now().isAfter(Instant.parse(it)) }.getOrDefault(false) }
        ?: false
      _uiState.update {
        it.copy(
          isLoading = false,
          hasError = false,
          status = status,
          canConvertToAccompanied = status == ReferralStatus.PENDING_FOLLOWUP &&
            withinWindow &&
            standardLookupId != null &&
            link.referralTypeLookupValueId == standardLookupId,
        )
      }
    }
  }

  fun setVisitedFacility(visited: Boolean) = _uiState.update { it.copy(visitedFacility = visited) }
  fun setFollowupDate(date: LocalDate?) = _uiState.update { it.copy(followupDate = date) }
  fun setNotVisitedReason(value: String) = _uiState.update { it.copy(notVisitedReason = value) }
  fun setDiagnosis(value: String) = _uiState.update { it.copy(diagnosis = value) }
  fun setTreatmentGiven(value: String) = _uiState.update { it.copy(treatmentGiven = value) }
  fun setOutcome(value: String) = _uiState.update { it.copy(outcome = value) }

  fun submit() {
    val state = _uiState.value
    val visited = state.visitedFacility ?: return
    val date = state.followupDate ?: return
    if (state.isSubmitting) return

    _uiState.update { it.copy(isSubmitting = true) }
    viewModelScope.launch {
      referralRepository.submitFollowUp(
        referralId = referralId,
        visitedFacilityFlag = visited,
        followupDate = date,
        notVisitedReason = state.notVisitedReason.trim().ifBlank { null },
        diagnosis = state.diagnosis.trim().ifBlank { null },
        treatmentGiven = state.treatmentGiven.trim().ifBlank { null },
        outcome = state.outcome.trim().ifBlank { null },
      ).onSuccess { result ->
        // Mirror the referral's new status into the local cache immediately, same as
        // VisitFormSubmissionCoordinator does on create — so a pop-back to the profile shows the
        // updated chip without a fresh network round trip.
        referralLinkDao.getByLocalScheduleUuid(localScheduleUuid)?.let { existing ->
          referralLinkDao.upsert(existing.copy(status = result.referral.status.name))
        }
        _uiState.update { it.copy(isSubmitting = false) }
        _events.send(ReferralFollowUpEvent.SubmittedSuccessfully)
      }.onFailure { error ->
        _uiState.update { it.copy(isSubmitting = false) }
        _events.send(ReferralFollowUpEvent.SubmitFailed(error.message ?: "Something went wrong. Please try again."))
      }
    }
  }

  fun convertToAccompanied() {
    if (_uiState.value.isConverting) return
    _uiState.update { it.copy(isConverting = true) }
    viewModelScope.launch {
      referralRepository.convertToAccompanied(referralId)
        .onSuccess { referral ->
          referralLinkDao.getByLocalScheduleUuid(localScheduleUuid)?.let { existing ->
            referralLinkDao.upsert(
              existing.copy(referralTypeLookupValueId = referral.referralTypeLookupValueId),
            )
          }
          _uiState.update { it.copy(isConverting = false, canConvertToAccompanied = false) }
          _events.send(ReferralFollowUpEvent.ConvertedSuccessfully)
        }
        .onFailure { error ->
          _uiState.update { it.copy(isConverting = false) }
          _events.send(
            ReferralFollowUpEvent.ConvertFailed(error.message ?: "Could not convert this referral. Please try again."),
          )
        }
    }
  }

  companion object {
    const val NAV_ARG_LOCAL_SCHEDULE_UUID = "localScheduleUuid"
    const val NAV_ARG_REFERRAL_ID = "referralId"
  }
}
