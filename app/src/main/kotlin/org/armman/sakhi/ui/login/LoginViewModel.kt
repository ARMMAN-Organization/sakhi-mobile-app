package org.armman.sakhi.ui.login

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.R
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.auth.LoginFailureReason
import org.armman.sakhi.data.auth.LoginRequest
import org.armman.sakhi.data.auth.LoginResult
import org.armman.sakhi.data.auth.session.SessionStore
import javax.inject.Inject

/**
 * UI state for the login screen. Error messages are string resource ids so
 * they localize with the rest of the app (EN/Marathi).
 */
data class LoginUiState(
  val username: String = "",
  val password: String = "",
  val isSubmitting: Boolean = false,
  @StringRes val usernameError: Int? = null,
  @StringRes val passwordError: Int? = null,
  @StringRes val loginError: Int? = null,
  val loginSucceeded: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
  private val authRepository: AuthRepository,
  private val sessionStore: SessionStore,
) : ViewModel() {

  // A valid, unexpired "stay logged in" session skips the form entirely — LoginScreen treats
  // loginSucceeded the same whether it came from this check or a fresh submit. An expired
  // session does NOT bypass login (see SessionStore.hasValidSession).
  private val _uiState = MutableStateFlow(LoginUiState(loginSucceeded = sessionStore.hasValidSession()))
  val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

  fun onUsernameChanged(value: String) {
    _uiState.update { it.copy(username = value, usernameError = null, loginError = null) }
  }

  fun onPasswordChanged(value: String) {
    _uiState.update { it.copy(password = value, passwordError = null, loginError = null) }
  }

  fun onLoginClicked() {
    val state = _uiState.value
    if (state.isSubmitting) return

    val usernameError = if (state.username.isBlank()) R.string.login_error_username_required else null
    val passwordError = if (state.password.isBlank()) R.string.login_error_password_required else null
    if (usernameError != null || passwordError != null) {
      _uiState.update { it.copy(usernameError = usernameError, passwordError = passwordError) }
      return
    }

    // Set synchronously so a second tap before the coroutine runs is ignored.
    _uiState.update { it.copy(isSubmitting = true, loginError = null) }
    viewModelScope.launch {
      // Leading/trailing spaces are almost always accidental (autocorrect, copy-paste) on both
      // fields — trimmed before ever leaving the device, never sent or hashed with the spaces in.
      val result = authRepository.login(
        LoginRequest(username = state.username.trim(), password = state.password.trim()),
      )
      when (result) {
        is LoginResult.Success ->
          _uiState.update { it.copy(isSubmitting = false, loginSucceeded = true) }
        is LoginResult.Failure ->
          _uiState.update { it.copy(isSubmitting = false, loginError = result.reason.toMessageRes()) }
      }
    }
  }

  /** Reset the one-shot success flag after navigation has been performed. */
  fun onLoginHandled() {
    _uiState.update { it.copy(loginSucceeded = false) }
  }

  @StringRes
  private fun LoginFailureReason.toMessageRes(): Int = when (this) {
    LoginFailureReason.INVALID_CREDENTIALS -> R.string.login_error_invalid_credentials
    LoginFailureReason.VALIDATION_ERROR -> R.string.login_error_generic
    LoginFailureReason.NETWORK_ERROR -> R.string.login_error_network
    LoginFailureReason.WRONG_ROLE -> R.string.login_error_wrong_role
    LoginFailureReason.OFFLINE_NO_CACHE -> R.string.login_error_offline_no_cache
    LoginFailureReason.UNKNOWN -> R.string.login_error_generic
  }
}
