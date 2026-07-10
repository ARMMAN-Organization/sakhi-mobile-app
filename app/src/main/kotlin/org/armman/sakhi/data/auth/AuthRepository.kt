package org.armman.sakhi.data.auth

/**
 * Authentication boundary for the app. Screens and ViewModels depend only on
 * this interface; the backing implementation (static today, auth-service API
 * later) is chosen in DI so the swap never touches the UI layer.
 */
interface AuthRepository {
  suspend fun login(request: LoginRequest): LoginResult

  /** Clears the local session. Idempotent; must never throw for a clean UX. */
  suspend fun logout()
}
