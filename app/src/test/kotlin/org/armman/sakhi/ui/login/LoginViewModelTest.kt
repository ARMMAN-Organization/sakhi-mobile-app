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
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupWarmer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  // Fixed clock so "skip the form" expiry checks are deterministic regardless of wall time.
  private val nowEpochSeconds = 1_784_189_546L
  private val fixedClock: Clock = Clock.fixed(Instant.ofEpochSecond(nowEpochSeconds), ZoneOffset.UTC)

  private fun sampleSession(expiresAtEpochSeconds: Long = nowEpochSeconds + 3600) = UserSession(
    username = "test.sakhi",
    subjectId = "sub-1",
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "t",
    refreshToken = "r",
    accessTokenExpiresAtEpochSeconds = expiresAtEpochSeconds,
  )

  /** Controllable fake so tests never depend on any real network/session logic. */
  private class FakeAuthRepository(
    var result: LoginResult = LoginResult.Success(
      UserSession(
        username = "test.sakhi",
        subjectId = "sub-1",
        roles = listOf("SAKHI"),
        projectId = null,
        geographyUnitId = null,
        accessToken = "t",
        refreshToken = "r",
        accessTokenExpiresAtEpochSeconds = 2000L,
      ),
    ),
  ) : AuthRepository {
    var loginCallCount = 0
    var lastRequest: LoginRequest? = null

    override suspend fun login(request: LoginRequest): LoginResult {
      loginCallCount++
      lastRequest = request
      return result
    }

    override suspend fun logout() { /* no session state in the fake */ }
  }

  private lateinit var repository: FakeAuthRepository
  private lateinit var keyValueStore: FakeSecureKeyValueStore
  private lateinit var sessionStore: SessionStore
  private lateinit var viewModel: LoginViewModel

  private fun createViewModel() {
    viewModel = LoginViewModel(repository, sessionStore, LookupWarmer(FakeLookupRepository()))
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeAuthRepository()
    keyValueStore = FakeSecureKeyValueStore()
    sessionStore = SessionStore(keyValueStore, fixedClock)
    createViewModel()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial state has no prefill and no errors when no session is stored`() {
    val state = viewModel.uiState.value
    assertEquals("", state.username)
    assertEquals("", state.password)
    assertFalse(state.isSubmitting)
    assertNull(state.usernameError)
    assertNull(state.passwordError)
    assertNull(state.loginError)
    assertFalse(state.loginSucceeded)
  }

  @Test
  fun `existing valid session skips the form entirely`() {
    sessionStore.saveSession(sampleSession())
    createViewModel() // session must be read at construction time

    assertTrue(viewModel.uiState.value.loginSucceeded)
  }

  @Test
  fun `existing expired session does not skip the form`() {
    // An expired stored session must show the login form, not silently open the dashboard on a
    // dead token (which would only be rejected later, on the first authenticated call).
    sessionStore.saveSession(sampleSession(expiresAtEpochSeconds = nowEpochSeconds - 1))
    createViewModel()

    assertFalse(viewModel.uiState.value.loginSucceeded)
  }

  @Test
  fun `blank username blocks submit with field error`() = runTest(dispatcher) {
    viewModel.onUsernameChanged("")
    viewModel.onPasswordChanged("secret")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_username_required, viewModel.uiState.value.usernameError)
    assertEquals(0, repository.loginCallCount)
  }

  @Test
  fun `blank password blocks submit with field error`() = runTest(dispatcher) {
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_password_required, viewModel.uiState.value.passwordError)
    assertEquals(0, repository.loginCallCount)
  }

  @Test
  fun `both fields blank shows both errors`() = runTest(dispatcher) {
    viewModel.onUsernameChanged("")
    viewModel.onPasswordChanged("")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(R.string.login_error_username_required, state.usernameError)
    assertEquals(R.string.login_error_password_required, state.passwordError)
    assertEquals(0, repository.loginCallCount)
  }

  @Test
  fun `online login success updates state and calls repository once`() = runTest(dispatcher) { // VM-2
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(state.loginSucceeded)
    assertFalse(state.isSubmitting)
    assertNull(state.loginError)
    assertEquals(1, repository.loginCallCount)
  }

  @Test
  fun `username is trimmed before submission`() = runTest(dispatcher) {
    viewModel.onUsernameChanged("  test.sakhi  ")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("test.sakhi", repository.lastRequest?.username)
  }

  @Test
  fun `password is trimmed before submission`() = runTest(dispatcher) { // TC-19
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("  Test@1234  ")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("Test@1234", repository.lastRequest?.password)
  }

  @Test
  fun `invalid credentials shows localized error, does not succeed`() = runTest(dispatcher) { // VM-3
    repository.result = LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("wrong")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(R.string.login_error_invalid_credentials, state.loginError)
    assertFalse(state.loginSucceeded)
    assertFalse(state.isSubmitting)
  }

  @Test
  fun `network error shows distinct message from invalid credentials`() = runTest(dispatcher) { // VM-4
    repository.result = LoginResult.Failure(LoginFailureReason.NETWORK_ERROR)
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val errorRes = viewModel.uiState.value.loginError
    assertEquals(R.string.login_error_network, errorRes)
    assertTrue(errorRes != R.string.login_error_invalid_credentials)
  }

  @Test
  fun `offline with no cache shows connect-once message`() = runTest(dispatcher) { // VM-8
    repository.result = LoginResult.Failure(LoginFailureReason.OFFLINE_NO_CACHE)
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_offline_no_cache, viewModel.uiState.value.loginError)
  }

  @Test
  fun `wrong role shows app-mismatch error, not invalid-credentials`() = runTest(dispatcher) { // VM-10
    repository.result = LoginResult.Failure(LoginFailureReason.WRONG_ROLE)
    viewModel.onUsernameChanged("supervisor.user")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val errorRes = viewModel.uiState.value.loginError
    assertEquals(R.string.login_error_wrong_role, errorRes)
    assertTrue(errorRes != R.string.login_error_invalid_credentials)
  }

  @Test
  fun `unknown failure shows generic error`() = runTest(dispatcher) {
    repository.result = LoginResult.Failure(LoginFailureReason.UNKNOWN)
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("pw")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(R.string.login_error_generic, viewModel.uiState.value.loginError)
  }

  @Test
  fun `typing clears field and login errors`() = runTest(dispatcher) {
    repository.result = LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
    viewModel.onUsernameChanged("test.sakhi")
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
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onLoginHandled()

    assertFalse(viewModel.uiState.value.loginSucceeded)
  }

  @Test
  fun `submit is ignored while already submitting`() = runTest(dispatcher) { // VM-9
    viewModel.onUsernameChanged("test.sakhi")
    viewModel.onPasswordChanged("Test@1234")
    viewModel.onLoginClicked() // starts submitting; coroutine not yet run
    viewModel.onLoginClicked() // second click before the scheduler advances

    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, repository.loginCallCount)
  }
}
