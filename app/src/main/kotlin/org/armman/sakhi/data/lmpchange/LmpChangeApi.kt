package org.armman.sakhi.data.lmpchange

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * `POST /lmp-change-requests` body — role SAKHI only. Confirmed live 2026-09-02: no `oldLmpDate`
 * in the request (the server already knows the beneficiary's current value); `sonographyImageAssetId`
 * is optional. [localRequestUuid] is the client-minted idempotency key (string, 1-80 chars,
 * confirmed) — same shape as [org.armman.sakhi.data.reopen.ReopenRequestDto.localReopenRequestUuid].
 * A repeat POST with the same [localRequestUuid] replays the original row (`200`) instead of
 * creating a duplicate (`201` on first submit) — see
 * [RemoteLmpChangeRepository.submitLmpChangeRequest].
 */
data class LmpChangeRequestDto(
  val beneficiaryId: String,
  val newLmpDate: String,
  val sonographyImageAssetId: String?,
  val localRequestUuid: String,
)

/**
 * One row as `GET /lmp-change-requests?beneficiaryId=` / `POST /lmp-change-requests` returns it.
 * Modelled loosely (every property nullable) — same convention as [org.armman.sakhi.data.reopen
 * .ReopenRequestRowDto]'s own doc. Backend confirmed 2026-09-02 the response shape "matches the
 * existing `GET /lmp-change-requests/:id` detail shape you already know" — [id], [beneficiaryId],
 * [oldLmpDate], [newLmpDate], [sonographyImageAssetId], [requestedByUserId], [requestedAt] were
 * independently cited from that detail route in an earlier investigation pass. The decision fields
 * ([supervisorStatus], [decidedByUserId], [decidedAt]) are carried over from the Reopen contract's
 * naming BY INFERENCE, not yet verified field-by-field against a live LMP response — verify these
 * three specifically on first real integration test, same caution [ReopenRequestRowDto]'s own doc
 * already gives for its own fields.
 */
data class LmpChangeRequestRowDto(
  val id: String?,
  val beneficiaryId: String?,
  val oldLmpDate: String?,
  val newLmpDate: String?,
  val sonographyImageAssetId: String?,
  val requestedByUserId: String?,
  val requestedAt: String?,
  val supervisorStatus: String?,
  val decidedByUserId: String?,
  val decidedAt: String?,
)

data class LmpChangeRequestResponseDto(
  val success: Boolean,
  val message: String?,
  val data: LmpChangeRequestRowDto?,
)

/** `GET /lmp-change-requests?beneficiaryId=` response — `data` is most-recent first, empty array
 * if none ever existed for this beneficiary, mirroring [org.armman.sakhi.data.reopen
 * .ReopenRequestListResponseDto]'s own documented contract for the equivalent Reopen endpoint
 * (not independently reconfirmed field-by-field for this endpoint). */
data class LmpChangeRequestListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: List<LmpChangeRequestRowDto>?,
)

/** Retrofit contract for the LMP-change-request endpoints confirmed live 2026-09-02. Requires a
 * Bearer token, attached by [org.armman.sakhi.data.auth.AuthInterceptor]. */
interface LmpChangeApi {
  /** Returns `201` for a genuinely new request, `200` with the existing row for an idempotent
   * replay on the same `localRequestUuid` — both success codes carry the same body shape, mirrors
   * [org.armman.sakhi.data.referral.ReferralApi.createReferral]'s own documented `201`/`200`
   * distinction for the same reason (a retried offline submit must not create a duplicate row). */
  @POST("lmp-change-requests")
  suspend fun createLmpChangeRequest(@Body request: LmpChangeRequestDto): Response<LmpChangeRequestResponseDto>

  /** `beneficiaryId` is required — omitting it is a `400`, not an unfiltered list (confirmed). */
  @GET("lmp-change-requests")
  suspend fun getLmpChangeRequests(
    @Query("beneficiaryId") beneficiaryId: String,
  ): Response<LmpChangeRequestListResponseDto>
}
