package org.armman.sakhi.data.auth

/**
 * Credentials submitted from the login screen. Field names mirror the
 * planned auth-service contract so the future Retrofit DTO maps 1:1.
 */
data class LoginRequest(
  val userId: String,
  val password: String,
)

/** Authenticated Sakhi session as the auth API is expected to return it. */
data class UserSession(
  val userId: String,
  val displayName: String,
  val role: String,
  val accessToken: String,
)

/** Domain result of a login attempt — success with a session, or a typed failure. */
sealed interface LoginResult {
  data class Success(val session: UserSession) : LoginResult
  data class Failure(val reason: LoginFailureReason) : LoginResult
}

/** Typed failure reasons so the UI can show localized, user-friendly messages. */
enum class LoginFailureReason {
  INVALID_CREDENTIALS,
  UNKNOWN,
}
