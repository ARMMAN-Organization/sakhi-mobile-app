package org.armman.sakhi.data.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Request body sent to the auth-service login endpoint. Field names are the wire contract. */
data class LoginRequestDto(
  val username: String,
  val password: String,
)

/** `data` payload of a successful login response. No profile fields — tokens only. */
data class LoginResponseData(
  val accessToken: String,
  val refreshToken: String,
)

/** Envelope every auth-service response uses, success or failure. */
data class LoginResponseDto(
  val success: Boolean,
  val message: String?,
  val data: LoginResponseData?,
)

/** Error envelope shape for 400/401/etc — `details` is a free-form map, not asserted on. */
data class ErrorResponseDto(
  val success: Boolean,
  val message: String?,
  val errorCode: String?,
)

/** One role assignment on the caller, as returned by `/me`. */
data class CallerRoleDto(
  val roleCode: String,
  val projectId: String?,
  val geographyUnitId: String?,
)

/** `data` payload of a successful `/me` response — the authenticated caller's profile.
 * `id`/`username`/`roles` aren't consumed by the app yet (the JWT claims already cover role/
 * project/geography for auth purposes) but are kept on the DTO since they're part of the wire
 * contract. No `sakhiId`-equivalent field exists yet — [org.armman.sakhi.data.profile.SakhiProfile.sakhiId]
 * stays static pending a backend field for it. */
data class CallerProfileData(
  val id: String,
  val username: String,
  val displayName: String,
  val mobileNumber: String?,
  val roles: List<CallerRoleDto> = emptyList(),
  val projectName: String?,
  val cardNumber: String?,
  val maskedBankAccount: String?,
)

/** Envelope for the `/me` endpoint — same success/message/data shape as login. */
data class CallerProfileResponseDto(
  val success: Boolean,
  val message: String?,
  val data: CallerProfileData?,
)

/** Retrofit contract for the auth-service endpoints. Paths are relative to `API_BASE_URL`
 * (`.../api/v1/`), so requests resolve to `.../api/v1/auth/login` and `.../api/v1/me`. */
interface AuthApi {
  @POST("auth/login")
  suspend fun login(@Body request: LoginRequestDto): Response<LoginResponseDto>

  /** Requires a Bearer token — attached by [AuthInterceptor], not passed here. */
  @GET("me")
  suspend fun getMe(): Response<CallerProfileResponseDto>
}
