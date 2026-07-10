package org.armman.sakhi.ui.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.auth.LoginRequest
import org.armman.sakhi.data.auth.LoginResult
import org.armman.sakhi.data.auth.LoginFailureReason
import org.armman.sakhi.data.profile.ProfileRepository
import org.armman.sakhi.data.profile.SakhiProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeProfileRepository(
    var profile: SakhiProfile = DEFAULT_PROFILE,
    var error: Exception? = null,
  ) : ProfileRepository {
    override suspend fun getProfile(): SakhiProfile {
      error?.let { throw it }
      return profile
    }
  }

  private class FakeAuthRepository(
    var logoutError: Exception? = null,
  ) : AuthRepository {
    var logoutCalls = 0

    override suspend fun login(request: LoginRequest): LoginResult =
      LoginResult.Failure(LoginFailureReason.UNKNOWN) // Unused in these tests.

    override suspend fun logout() {
      logoutCalls++
      logoutError?.let { throw it }
    }
  }

  private lateinit var profileRepository: FakeProfileRepository
  private lateinit var authRepository: FakeAuthRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    profileRepository = FakeProfileRepository()
    authRepository = FakeAuthRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(): ProfileViewModel {
    val viewModel = ProfileViewModel(profileRepository, authRepository)
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `successful load exposes the profile`() {
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals("Tarini Swaraj", state.profile?.name)
  }

  @Test
  fun `repository failure sets error and retry recovers`() {
    profileRepository.error = IOException("offline")
    val viewModel = createViewModel()
    assertTrue(viewModel.uiState.value.hasError)

    profileRepository.error = null
    viewModel.loadProfile()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertFalse(state.hasError)
    assertEquals("Tarini Swaraj", state.profile?.name)
  }

  @Test
  fun `language selection emits the right tag and resets after applying`() {
    val viewModel = createViewModel()

    viewModel.onChooseLanguage()
    assertTrue(viewModel.uiState.value.showLanguageDialog)

    viewModel.onLanguageSelected(AppLanguage.MARATHI)
    var state = viewModel.uiState.value
    assertFalse(state.showLanguageDialog)
    assertEquals("mr", state.applyLanguageTag)

    viewModel.onLanguageApplied()
    assertNull(viewModel.uiState.value.applyLanguageTag)

    viewModel.onLanguageSelected(AppLanguage.ENGLISH)
    assertEquals("en", viewModel.uiState.value.applyLanguageTag)
  }

  @Test
  fun `dialog can be dismissed without selection`() {
    val viewModel = createViewModel()

    viewModel.onChooseLanguage()
    viewModel.onDismissLanguageDialog()

    val state = viewModel.uiState.value
    assertFalse(state.showLanguageDialog)
    assertNull(state.applyLanguageTag)
  }

  @Test
  fun `logout calls auth repository once and emits one-shot navigation`() {
    val viewModel = createViewModel()

    viewModel.onLogout()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, authRepository.logoutCalls)
    assertTrue(viewModel.uiState.value.loggedOut)

    viewModel.onLogoutHandled()
    assertFalse(viewModel.uiState.value.loggedOut)
  }

  @Test
  fun `logout still navigates when session cleanup throws`() {
    authRepository.logoutError = IOException("cleanup failed")
    val viewModel = createViewModel()

    viewModel.onLogout()
    dispatcher.scheduler.advanceUntilIdle()

    // Never trap the user: navigation happens despite the failure.
    assertTrue(viewModel.uiState.value.loggedOut)
  }

  private companion object {
    val DEFAULT_PROFILE = SakhiProfile(
      name = "Tarini Swaraj",
      sakhiId = "12345678",
      projectName = "Project_Name",
      cardNumber = "AFCPC7070A",
      mobileNumber = "0987654321",
      maskedBankAccount = "*******431 HDFC Bank Ltd.",
    )
  }
}
