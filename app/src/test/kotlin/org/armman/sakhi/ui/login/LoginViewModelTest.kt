package org.armman.sakhi.ui.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.R
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.auth.LoginFailureReason
import org.armman.sakhi.data.auth.LoginRequest
import org.armman.sakhi.data.auth.LoginResult
import org.armman.sakhi.data.auth.UserSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  /** Controllable fake so tests never depend on the static credential values. */
  private class FakeAuthRepository(
    var result: LoginResult = LoginResult.Success(
      UserSession(userId = "u1", displayName = "Test", role = "SAKHI", accessToken = "t"),
    ),
  ) : AuthRepository {
    var loginCallCount = 0
    var lastRequest: LoginRequest? = null

    override suspend fun login(request: LoginRequest): LoginResult {
      loginCallCount++
      lastRequest = request
      return result
    }
  }

  private lateinit var repository: FakeAuthRepository
  private lateinit var viewModel: LoginViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeAuthRepository()
    viewModel = LoginViewModel(repository)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial state prefills dev credentials in debug and has no errors`() {
    // Unit tests run against the debug variant, so the dev prefill is expected.
    val state = viewModel.uiState.value
    assertEquals("sakhi01", state.userId)
    assertEquals("Sakhi@123", state.password)
    assertFalse(state.isSubmitting)
    assertNull(state.userIdError)
    assertNull(state.passwordError)
    assertNull(state.loginError)
    assertFalse(state.loginSucceeded)
  }

  @Test
  fun `blank user id blocks submit with field error`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("")
    viewModel.onPasswordChanged("secret")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_user_id_required, viewModel.uiState.value.userIdError)
    assertEquals(0, repository.loginCallCount)
  }

  @Test
  fun `blank password blocks submit with field error`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_password_required, viewModel.uiState.value.passwordError)
    assertEquals(0, repository.loginCallCount)
  }

  @Test
  fun `both fields blank shows both errors`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("")
    viewModel.onPasswordChanged("")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(R.string.login_error_user_id_required, state.userIdError)
    assertEquals(R.string.login_error_password_required, state.passwordError)
    assertEquals(0, repository.loginCallCount)
  }

  @Test
  fun `successful login sets loginSucceeded and stops submitting`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("Sakhi@123")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(state.loginSucceeded)
    assertFalse(state.isSubmitting)
    assertNull(state.loginError)
  }

  @Test
  fun `user id is trimmed before submission`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("  sakhi01  ")
    viewModel.onPasswordChanged("Sakhi@123")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("sakhi01", repository.lastRequest?.userId)
  }

  @Test
  fun `invalid credentials shows login error`() = runTest(dispatcher) {
    repository.result = LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("wrong")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(R.string.login_error_invalid_credentials, state.loginError)
    assertFalse(state.loginSucceeded)
    assertFalse(state.isSubmitting)
  }

  @Test
  fun `unknown failure shows generic error`() = runTest(dispatcher) {
    repository.result = LoginResult.Failure(LoginFailureReason.UNKNOWN)
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("pw")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_generic, viewModel.uiState.value.loginError)
  }

  @Test
  fun `typing clears field and login errors`() = runTest(dispatcher) {
    repository.result = LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("wrong")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onPasswordChanged("wrong2")

    val state = viewModel.uiState.value
    assertNull(state.loginError)
    assertNull(state.passwordError)
  }

  @Test
  fun `onLoginHandled resets the one-shot success flag`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("Sakhi@123")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onLoginHandled()

    assertFalse(viewModel.uiState.value.loginSucceeded)
  }

  @Test
  fun `submit is ignored while already submitting`() = runTest(dispatcher) {
    viewModel.onUserIdChanged("sakhi01")
    viewModel.onPasswordChanged("Sakhi@123")
    viewModel.onLoginClicked() // starts submitting; coroutine not yet run
    viewModel.onLoginClicked() // second click before the scheduler advances

    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, repository.loginCallCount)
  }
}
