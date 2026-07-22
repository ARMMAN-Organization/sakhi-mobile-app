package org.armman.sakhi.data.auth

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.OfflineCredentialCache
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class RemoteAuthRepositoryTest {

  /** Configurable fake — success/error/throw, and records the last request sent. */
  private class FakeAuthApi : AuthApi {
    var response: Response<LoginResponseDto>? = null
    var exceptionToThrow: Throwable? = null
    var lastRequest: LoginRequestDto? = null

    override suspend fun login(request: LoginRequestDto): Response<LoginResponseDto> {
      lastRequest = request
      exceptionToThrow?.let { throw it }
      return response!!
    }

    // Unused by this test class — RemoteCurrentUserRepositoryTest covers `/me` on its own.
    override suspend fun getMe() = throw NotImplementedError("unused")
  }

  // A real, valid-shape access token: {sub:"sub-1", roles:["SAKHI"], projectId:null,
  // geographyUnitId:null, iat:1000, exp:2000} — built the same way as JwtClaimsDecoderTest's.
  private val sakhiToken =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAic3ViLTEiLCAicm9sZXMiOiBbIlNBS0hJIl0sICJwcm9qZWN0SWQiOiBudWxsLCAiZ2VvZ3JhcGh5VW5pdElkIjogbnVsbCwgImlhdCI6IDEwMDAsICJleHAiOiAyMDAwfQ." +
      "sig"
  private val supervisorToken =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAic3ViLTEiLCAicm9sZXMiOiBbIlNVUEVSVklTT1IiXSwgInByb2plY3RJZCI6IG51bGwsICJnZW9ncmFwaHlVbml0SWQiOiBudWxsLCAiaWF0IjogMTAwMCwgImV4cCI6IDIwMDB9." +
      "sig"

  private lateinit var api: FakeAuthApi
  private lateinit var connectivity: FakeConnectivityChecker
  private lateinit var keyValueStore: FakeSecureKeyValueStore
  private lateinit var sessionStore: SessionStore
  private lateinit var offlineCache: OfflineCredentialCache
  private lateinit var currentUserRepository: FakeCurrentUserRepository
  private lateinit var repository: RemoteAuthRepository

  @Before
  fun setUp() {
    api = FakeAuthApi()
    connectivity = FakeConnectivityChecker(online = true)
    keyValueStore = FakeSecureKeyValueStore()
    sessionStore = SessionStore(keyValueStore)
    offlineCache = OfflineCredentialCache(keyValueStore)
    currentUserRepository = FakeCurrentUserRepository()
    repository = RemoteAuthRepository(
      api,
      JwtClaimsDecoder(),
      sessionStore,
      offlineCache,
      connectivity,
      currentUserRepository,
    )
  }

  private fun successBody(accessToken: String) =
    LoginResponseDto(success = true, message = "OK", data = LoginResponseData(accessToken, "refresh-1"))

  private fun errorResponse(code: Int, errorCode: String): Response<LoginResponseDto> {
    val json = """{"success":false,"message":"failed","errorCode":"$errorCode"}"""
    return Response.error(code, json.toResponseBody("application/json".toMediaType()))
  }

  @Test
  fun `login success maps token payload`() = runTest { // RA-1
    api.response = Response.success(successBody(sakhiToken))

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertTrue(result is LoginResult.Success)
    val session = (result as LoginResult.Success).session
    assertEquals(sakhiToken, session.accessToken)
    assertEquals("refresh-1", session.refreshToken)
    assertEquals(2000L, session.accessTokenExpiresAtEpochSeconds)
  }

  @Test
  fun `login sends username and password fields`() = runTest { // RA-2
    api.response = Response.success(successBody(sakhiToken))

    repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals("test.sakhi", api.lastRequest?.username)
    assertEquals("Test@1234", api.lastRequest?.password)
  }

  @Test
  fun `400 validation error maps to VALIDATION_ERROR`() = runTest { // RA-3
    api.response = errorResponse(400, "VALIDATION_ERROR")

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals(LoginResult.Failure(LoginFailureReason.VALIDATION_ERROR), result)
  }

  @Test
  fun `401 invalid credentials maps to INVALID_CREDENTIALS`() = runTest { // RA-4
    api.response = errorResponse(401, "VALIDATION_ERROR") // real API's errorCode is generic today

    val result = repository.login(LoginRequest("test.sakhi", "wrong"))

    assertEquals(LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS), result)
  }

  @Test
  fun `network or timeout failure maps to NETWORK_ERROR`() = runTest { // RA-5
    api.exceptionToThrow = IOException("timeout")

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals(LoginResult.Failure(LoginFailureReason.NETWORK_ERROR), result)
  }

  @Test
  fun `unexpected 5xx maps to UNKNOWN`() = runTest { // RA-6
    api.response = errorResponse(500, "INTERNAL")

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals(LoginResult.Failure(LoginFailureReason.UNKNOWN), result)
  }

  @Test
  fun `successful login writes offline credential hash`() = runTest { // RA-7
    api.response = Response.success(successBody(sakhiToken))

    repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertTrue(offlineCache.verify("test.sakhi", "Test@1234".toCharArray()))
  }

  @Test
  fun `successful login persists session via SessionStore`() = runTest { // RA-8
    api.response = Response.success(successBody(sakhiToken))

    repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals("test.sakhi", sessionStore.readSession()?.username)
  }

  @Test
  fun `persistence failure rolls back both session and offline cache`() = runTest { // RA-8b
    // Offline cache is written first, then the session; if the session write fails, neither must
    // survive — a persisted session with no offline cache would break offline re-login silently.
    api.response = Response.success(successBody(sakhiToken))
    keyValueStore.failOnPutKey = "session_json"

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals(LoginResult.Failure(LoginFailureReason.UNKNOWN), result)
    assertNull(sessionStore.readSession())
    assertFalse(offlineCache.verify("test.sakhi", "Test@1234".toCharArray()))
    assertFalse(offlineCache.hasAnyEntry())
  }

  @Test
  fun `login rejected when roles does not contain SAKHI`() = runTest { // RA-9
    api.response = Response.success(successBody(supervisorToken))

    val result = repository.login(LoginRequest("supervisor.user", "Test@1234"))

    assertEquals(LoginResult.Failure(LoginFailureReason.WRONG_ROLE), result)
    // A rejected-role login must not persist a session or an offline credential.
    assertNull(sessionStore.readSession())
    assertFalse(offlineCache.hasAnyEntry())
  }

  @Test
  fun `offline with matching cache restores last session`() = runTest {
    api.response = Response.success(successBody(sakhiToken))
    repository.login(LoginRequest("test.sakhi", "Test@1234")) // online login caches + persists
    connectivity.online = false

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertTrue(result is LoginResult.Success)
  }

  @Test
  fun `offline with wrong password fails as invalid credentials`() = runTest {
    api.response = Response.success(successBody(sakhiToken))
    repository.login(LoginRequest("test.sakhi", "Test@1234"))
    connectivity.online = false

    val result = repository.login(LoginRequest("test.sakhi", "wrongpass"))

    assertEquals(LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS), result)
  }

  @Test
  fun `offline with no cache at all fails as OFFLINE_NO_CACHE`() = runTest {
    connectivity.online = false

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals(LoginResult.Failure(LoginFailureReason.OFFLINE_NO_CACHE), result)
  }

  @Test
  fun `offline path never calls the network API`() = runTest {
    connectivity.online = false

    repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertNull(api.lastRequest)
  }

  @Test
  fun `logout clears session and never throws`() = runTest {
    api.response = Response.success(successBody(sakhiToken))
    repository.login(LoginRequest("test.sakhi", "Test@1234"))

    repository.logout()

    assertNull(sessionStore.readSession())
  }

  @Test
  fun `logout does not clear the cached me profile - same Sakhi needs it back if she re-logs offline`() =
    runTest {
      // Seed the cached profile for the SAME Sakhi who logs in, so clearIfDifferentUser is a
      // genuine no-op — the default fake profile is a *different* username, which would trip the
      // different-user guard at login and is not the scenario under test here.
      currentUserRepository.profile = CurrentUserProfile(
        username = "test.sakhi",
        displayName = "Test Sakhi",
        mobileNumber = null,
        projectName = null,
        cardNumber = null,
        maskedBankAccount = null,
      )
      api.response = Response.success(successBody(sakhiToken))
      repository.login(LoginRequest("test.sakhi", "Test@1234"))

      repository.logout()

      assertEquals(0, currentUserRepository.clearCallCount)
    }

  @Test
  fun `login guards the cached me profile with the logging-in username`() = runTest {
    api.response = Response.success(successBody(sakhiToken))

    repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertEquals(listOf("test.sakhi"), currentUserRepository.clearIfDifferentUserCalls)
  }

  @Test
  fun `same Sakhi logging back in offline keeps her cached me profile`() = runTest {
    api.response = Response.success(successBody(sakhiToken))
    repository.login(LoginRequest("test.sakhi", "Test@1234")) // online, seeds offline cache
    currentUserRepository.profile = CurrentUserProfile(
      username = "test.sakhi",
      displayName = "Test Sakhi",
      mobileNumber = null,
      projectName = null,
      cardNumber = null,
      maskedBankAccount = null,
    )
    repository.logout()
    connectivity.online = false

    val result = repository.login(LoginRequest("test.sakhi", "Test@1234"))

    assertTrue(result is LoginResult.Success)
    assertEquals("Test Sakhi", currentUserRepository.getProfile()?.displayName)
  }
}
