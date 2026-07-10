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
import org.armman.sakhi.BuildConfig
import org.armman.sakhi.R
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.auth.LoginFailureReason
import org.armman.sakhi.data.auth.LoginRequest
import org.armman.sakhi.data.auth.LoginResult
import javax.inject.Inject

/**
 * UI state for the login screen. Error messages are string resource ids so
 * they localize with the rest of the app (EN/Marathi).
 */
data class LoginUiState(
  val userId: String = "",
  val password: String = "",
  val isSubmitting: Boolean = false,
  @StringRes val userIdError: Int? = null,
  @StringRes val passwordError: Int? = null,
  @StringRes val loginError: Int? = null,
  val loginSucceeded: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
  private val authRepository: AuthRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(initialState())
  val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

  fun onUserIdChanged(value: String) {
    _uiState.update { it.copy(userId = value, userIdError = null, loginError = null) }
  }

  fun onPasswordChanged(value: String) {
    _uiState.update { it.copy(password = value, passwordError = null, loginError = null) }
  }

  fun onLoginClicked() {
    val state = _uiState.value
    if (state.isSubmitting) return

    val userIdError = if (state.userId.isBlank()) R.string.login_error_user_id_required else null
    val passwordError = if (state.password.isBlank()) R.string.login_error_password_required else null
    if (userIdError != null || passwordError != null) {
      _uiState.update { it.copy(userIdError = userIdError, passwordError = passwordError) }
      return
    }

    // Set synchronously so a second tap before the coroutine runs is ignored.
    _uiState.update { it.copy(isSubmitting = true, loginError = null) }
    viewModelScope.launch {
      val result = authRepository.login(
        LoginRequest(userId = state.userId.trim(), password = state.password),
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
    LoginFailureReason.UNKNOWN -> R.string.login_error_generic
  }

  private companion object {
    // Dev convenience only: matches StaticAuthRepository; never compiled into release flows.
    const val DEV_USER_ID = "sakhi01"
    const val DEV_PASSWORD = "Sakhi@123"

    /** Prefills the static dev credentials in debug builds; empty in release. */
    fun initialState(): LoginUiState = if (BuildConfig.DEBUG) {
      LoginUiState(userId = DEV_USER_ID, password = DEV_PASSWORD)
    } else {
      LoginUiState()
    }
  }
}
