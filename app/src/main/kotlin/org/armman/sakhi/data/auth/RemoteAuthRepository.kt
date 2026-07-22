package org.armman.sakhi.data.auth

import org.armman.sakhi.data.auth.session.OfflineCredentialCache
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val REQUIRED_ROLE = "SAKHI"

/**
 * Real [AuthRepository] backed by the auth-service API, with an offline fallback.
 *
 * Online: calls [AuthApi], decodes the access token's claims, enforces the SAKHI-only role
 * check (this app is single-role; a Supervisor/Manager/Admin account must be rejected here per
 * the SRS's strict per-app role separation), then persists both the session (for "stay logged
 * in") and a salted credential hash (for offline re-login).
 *
 * Offline (per [ConnectivityChecker]): never attempts the network call — instead verifies
 * against [OfflineCredentialCache] and, on a match, restores the last [SessionStore] session so
 * the Sakhi can keep working until connectivity returns.
 */
@Singleton
class RemoteAuthRepository @Inject constructor(
  private val authApi: AuthApi,
  private val jwtClaimsDecoder: JwtClaimsDecoder,
  private val sessionStore: SessionStore,
  private val offlineCredentialCache: OfflineCredentialCache,
  private val connectivityChecker: ConnectivityChecker,
  private val currentUserRepository: CurrentUserRepository,
) : AuthRepository {

  override suspend fun login(request: LoginRequest): LoginResult {
    if (!connectivityChecker.isOnline()) {
      return loginOffline(request)
    }
    return try {
      loginOnline(request)
    } catch (e: IOException) {
      // The connectivity check passed but the call itself didn't reach/complete with the
      // server (timeout, connection reset, DNS failure) — distinct from the deliberate
      // offline path above, and distinct from a server-returned error.
      LoginResult.Failure(LoginFailureReason.NETWORK_ERROR)
    } catch (e: JwtDecodeException) {
      LoginResult.Failure(LoginFailureReason.UNKNOWN)
    }
  }

  override suspend fun logout() {
    try {
      sessionStore.clearSession()
      // Deliberately does NOT clear currentUserRepository: this device belongs to one Sakhi, who
      // routinely logs out/back in while offline (no way to re-fetch /me then) — the cached
      // profile must survive that. The (rare) different-Sakhi case is handled at login instead,
      // via clearIfDifferentUser.
    } catch (e: Exception) {
      // Logout must never throw — the user must still land on the login screen.
    }
  }

  private suspend fun loginOnline(request: LoginRequest): LoginResult {
    val response = authApi.login(LoginRequestDto(username = request.username, password = request.password))
    val body = response.body()
    return when {
      response.isSuccessful && body?.success == true && body.data != null ->
        onLoginSucceeded(request, body.data)
      response.code() == 400 -> LoginResult.Failure(LoginFailureReason.VALIDATION_ERROR)
      response.code() == 401 -> LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
      else -> LoginResult.Failure(LoginFailureReason.UNKNOWN)
    }
  }

  private fun onLoginSucceeded(request: LoginRequest, data: LoginResponseData): LoginResult {
    val claims = jwtClaimsDecoder.decode(data.accessToken)
    if (REQUIRED_ROLE !in claims.roles) {
      return LoginResult.Failure(LoginFailureReason.WRONG_ROLE)
    }
    // Guards against a device previously used by a different Sakhi still showing her cached
    // /me profile — a no-op for the common case of the same Sakhi logging back in.
    currentUserRepository.clearIfDifferentUser(request.username)
    val session = UserSession(
      username = request.username,
      subjectId = claims.subjectId,
      roles = claims.roles,
      projectId = claims.projectId,
      geographyUnitId = claims.geographyUnitId,
      accessToken = data.accessToken,
      refreshToken = data.refreshToken,
      accessTokenExpiresAtEpochSeconds = claims.expiresAtEpochSeconds,
    )
    sessionStore.saveSession(session)
    offlineCredentialCache.store(request.username, request.password.toCharArray(), session)
    return LoginResult.Success(session)
  }

  private fun loginOffline(request: LoginRequest): LoginResult {
    // The session snapshot lives in OfflineCredentialCache, not SessionStore — a logout
    // performed while offline clears SessionStore (so the login form reappears) but must not
    // erase the one thing that lets this same Sakhi get back in without connectivity.
    val restoredSession = offlineCredentialCache.verifyAndRestoreSession(
      request.username,
      request.password.toCharArray(),
    )
    if (restoredSession == null) {
      return if (offlineCredentialCache.hasAnyEntry()) {
        LoginResult.Failure(LoginFailureReason.INVALID_CREDENTIALS)
      } else {
        LoginResult.Failure(LoginFailureReason.OFFLINE_NO_CACHE)
      }
    }
    // Same guard as the online path — a no-op here in practice, since the offline cache only
    // ever verifies against the one username it was seeded with, but kept for symmetry.
    currentUserRepository.clearIfDifferentUser(request.username)
    // Restore "stay logged in" too, so a second offline relaunch skips the form again.
    sessionStore.saveSession(restoredSession)
    return LoginResult.Success(restoredSession)
  }
}
