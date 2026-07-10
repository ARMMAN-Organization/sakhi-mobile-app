package org.armman.sakhi.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.profile.ProfileRepository
import org.armman.sakhi.data.profile.SakhiProfile
import javax.inject.Inject

/** Supported app languages with their BCP-47 tags. */
enum class AppLanguage(val tag: String) { ENGLISH("en"), MARATHI("mr") }

/**
 * UI state for the Profile screen. [applyLanguageTag] and [loggedOut] are
 * one-shot signals consumed by the screen (locale switch / navigation).
 */
data class ProfileUiState(
  val isLoading: Boolean = true,
  val hasError: Boolean = false,
  val profile: SakhiProfile? = null,
  val showLanguageDialog: Boolean = false,
  val applyLanguageTag: String? = null,
  val loggedOut: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
  private val profileRepository: ProfileRepository,
  private val authRepository: AuthRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ProfileUiState())
  val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

  init {
    loadProfile()
  }

  /** Loads (or reloads after an error) the Sakhi profile. */
  fun loadProfile() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    viewModelScope.launch {
      try {
        val profile = profileRepository.getProfile()
        _uiState.update { it.copy(isLoading = false, profile = profile) }
      } catch (e: Exception) {
        // Generic error state for the UI; technical detail must not leak to users.
        _uiState.update { it.copy(isLoading = false, hasError = true) }
      }
    }
  }

  fun onChooseLanguage() {
    _uiState.update { it.copy(showLanguageDialog = true) }
  }

  fun onDismissLanguageDialog() {
    _uiState.update { it.copy(showLanguageDialog = false) }
  }

  /** Emits the one-shot locale tag; the screen applies it via AppCompat. */
  fun onLanguageSelected(language: AppLanguage) {
    _uiState.update {
      it.copy(showLanguageDialog = false, applyLanguageTag = language.tag)
    }
  }

  /** Reset after the screen has applied the locale. */
  fun onLanguageApplied() {
    _uiState.update { it.copy(applyLanguageTag = null) }
  }

  /**
   * Logout is best-effort client-side: even if session cleanup throws,
   * the user must still land on the login screen.
   */
  fun onLogout() {
    viewModelScope.launch {
      try {
        authRepository.logout()
      } catch (e: Exception) {
        // Intentionally swallowed — never trap the user in the app.
      }
      _uiState.update { it.copy(loggedOut = true) }
    }
  }

  /** Reset after navigation has been performed. */
  fun onLogoutHandled() {
    _uiState.update { it.copy(loggedOut = false) }
  }
}
