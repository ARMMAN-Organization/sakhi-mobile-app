package org.armman.sakhi.ui.previsithealthhistory

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import org.armman.sakhi.data.previsithealth.BeneficiaryNotSyncedException
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistory
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistoryRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

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
      } catch (e: CancellationException) {
        throw e
      } catch (e: BeneficiaryNotSyncedException) {
        // Not a failure — this beneficiary (or her schedule) hasn't synced to the server yet, so
        // there is nothing to show. Per product decision, don't block her on connectivity: skip
        // straight to the visit form rather than showing an error with a Retry button that would
        // only ever fail the same way again until she's back online.
        _events.trySend(PreVisitHealthHistoryEvent.NavigateToVisitForm)
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users. The real cause
        // is still logged (not swallowed) so a failure like this is diagnosable from logcat rather
        // than only ever showing the same "couldn't load" message no matter what actually broke —
        // e.g. an SSL/cert error (often a device clock set wrong) looks identical to the user as a
        // plain network outage, but they need very different fixes.
        Log.e(TAG, "load(beneficiaryId=$beneficiaryId, visitId=$visitId) failed: ${e.describeForLog()}", e)
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

private const val TAG = "PreVisitHealthHistory"

/** Short, stable classification for [Log.e] so a logcat grep (or crash-report breadcrumb) can
 * tell a device-clock/certificate problem apart from "no internet" or a genuine server error,
 * instead of every failure reading identically as "Exception". */
private fun Throwable.describeForLog(): String = when (this) {
  is SSLException -> "SSL/TLS handshake failed (often a device clock set far off, or a real MITM/cert issue) — ${message}"
  is UnknownHostException -> "DNS lookup failed — device likely has no real internet path — ${message}"
  is SocketTimeoutException -> "Request timed out — ${message}"
  is IOException -> "Network I/O error — ${message}"
  is BeneficiaryNotSyncedException -> "Beneficiary not synced (unexpected here — should have been caught above) — ${message}"
  else -> "${this::class.simpleName}: ${message}"
}
