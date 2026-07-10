package org.armman.sakhi.data.auth

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the auth-service API. Accepts one fixed credential so
 * both the success and error flows are demonstrable. Delete this class once
 * the real API exists — nothing outside DI references it.
 */
@Singleton
class StaticAuthRepository @Inject constructor() : AuthRepository {

  override suspend fun login(request: LoginRequest): LoginResult {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return if (
      request.userId.equals(VALID_USER_ID, ignoreCase = true) &&
      request.password == VALID_PASSWORD
    ) {
      LoginResult.Success(
        UserSession(
          userId = VALID_USER_ID,
          displayName = "Sunita Pawar",
          role = "SAKHI",
          accessToken = "static-token",
        ),
      )
    } else {
      LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
    }
  }

  override suspend fun logout() {
    // Static impl holds no session state; the real impl will revoke the
    // refresh token and clear the encrypted credential cache. Idempotent.
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 800L
    const val VALID_USER_ID = "sakhi01"
    const val VALID_PASSWORD = "Sakhi@123"
  }
}
