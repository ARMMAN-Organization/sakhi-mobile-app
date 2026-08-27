package org.armman.sakhi.data.reopen

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * `POST /api/v1/reopen-requests` body — role `SAKHI` only, `.strict()` (rejects unknown fields).
 * No date/user-id field: both are stamped server-side, unlike [org.armman.sakhi.data.closure
 * .ClosureRequestDto] which takes an explicit `submittedByUserId`.
 */
data class ReopenRequestDto(
  val beneficiaryId: String,
  val requestReason: String,
)

/** One row as `POST`/`GET /reopen-requests` returns it. Modelled loosely (every property nullable)
 * since only [supervisorStatus] is currently read (see
 * [org.armman.sakhi.data.reopen.ReopenRepository.hasPendingReopenRequest]) — the exact full shape
 * beyond that is inferred, not confirmed field-by-field against a live response. */
data class ReopenRequestRowDto(
  val id: String?,
  val beneficiaryId: String?,
  val requestReason: String?,
  val supervisorStatus: String?,
  val requestedAt: String?,
)

data class ReopenRequestResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ReopenRequestRowDto?,
)

/** `GET /reopen-requests?beneficiaryId=` response — `data` is most-recent first, empty array if
 * none ever existed for this beneficiary (never null per the confirmed contract). */
data class ReopenRequestListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: List<ReopenRequestRowDto>?,
)

/** Retrofit contract for the reopen-request endpoints confirmed live by the backend team. Requires
 * a Bearer token, attached by [org.armman.sakhi.data.auth.AuthInterceptor]. Never calls
 * `PATCH /beneficiaries/:id/reactivate` — that is a server-to-server call the reopen service
 * itself makes once a supervisor approves; the mobile app must not call it directly. */
interface ReopenApi {
  @POST("reopen-requests")
  suspend fun createReopenRequest(@Body request: ReopenRequestDto): Response<ReopenRequestResponseDto>

  @GET("reopen-requests")
  suspend fun getReopenRequests(
    @Query("beneficiaryId") beneficiaryId: String,
  ): Response<ReopenRequestListResponseDto>
}
