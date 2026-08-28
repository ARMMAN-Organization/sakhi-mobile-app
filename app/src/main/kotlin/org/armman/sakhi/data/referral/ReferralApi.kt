package org.armman.sakhi.data.referral

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** One entry of `GET /sakhi/{sakhiId}/referrals/pending-followup`'s `data.items` array. */
data class ReferralFollowUpDto(
  val referralId: String?,
  val beneficiaryId: String?,
  val beneficiaryName: String?,
  val referralDate: String?,
  val followUpDueDate: String?,
  val daysRemaining: Int?,
  val status: String?,
)

data class ReferralFollowUpListDataDto(
  val pendingFollowUpCount: Int?,
  val items: List<ReferralFollowUpDto>?,
)

data class ReferralFollowUpListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ReferralFollowUpListDataDto?,
)

/** `POST /referrals` request body — field names/casing match the live-confirmed contract exactly
 * (2026-08-27; corrected from this app's original, wrong assumptions — see [ReferralModels.kt]'s
 * doc comments for what changed). [status] is required, no server default, and must always be
 * `"PENDING_FOLLOWUP"` (confirmed: any other value would create a referral no existing endpoint
 * can act on). [triggerConditionListJson] is a real JSON array, not a JSON-encoded string, despite
 * its name (the `Json` suffix names the Postgres/Prisma column type, confirmed live). `validTill`
 * is deliberately never sent — confirmed a caller-supplied value is rejected outright with `400`,
 * since the server always computes it as `referralDate + 7 days`. */
data class CreateReferralRequestDto(
  val beneficiaryId: String,
  val visitId: String?,
  val sourceSubmissionId: String?,
  val referralTypeLookupValueId: String,
  val referralDate: String,
  val status: String,
  val facilityType: String,
  val facilityName: String,
  val triggerConditionListJson: List<String>,
)

/** `POST /referrals`'s `data` object, and also `PATCH /referrals/{id}/convert`'s `data` object —
 * same shape, confirmed live for both (the convert response is a strict subset of these fields;
 * extra fields simply come back null when a particular response omits them, handled the same way
 * every other DTO in this app treats an absent field). */
data class ReferralDataDto(
  val id: String?,
  val beneficiaryId: String?,
  val visitId: String?,
  val sourceSubmissionId: String?,
  val referralTypeLookupValueId: String?,
  val referralDate: String?,
  val triggerConditionListJson: List<String>?,
  val facilityType: String?,
  val facilityName: String?,
  val status: String?,
  val validTill: String?,
  val createdAt: String?,
)

data class CreateReferralResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ReferralDataDto?,
)

/** `POST /referrals/{referralId}/follow-up` request body — field names match the live-confirmed
 * contract exactly (2026-08-27). [notVisitedReason] is free text (confirmed by an exact-echo
 * test against the live endpoint), not a lookup code. */
data class SubmitReferralFollowUpRequestDto(
  val visitedFacilityFlag: Boolean,
  val followupDate: String,
  val notVisitedReason: String?,
  val diagnosis: String?,
  val treatmentGiven: String?,
  val outcome: String?,
)

data class ReferralFollowUpDataDto(
  val id: String?,
  val referralId: String?,
  val visitedFacilityFlag: Boolean?,
  val notVisitedReason: String?,
  val diagnosis: String?,
  val treatmentGiven: String?,
  val outcome: String?,
  val followupStatus: String?,
)

data class SubmitReferralFollowUpDataDto(
  val followup: ReferralFollowUpDataDto?,
  val referral: ReferralDataDto?,
)

data class SubmitReferralFollowUpResponseDto(
  val success: Boolean,
  val message: String?,
  val data: SubmitReferralFollowUpDataDto?,
)

/** Retrofit contract for referrals, behind the same API gateway/base URL and Bearer token as
 * every other service ([org.armman.sakhi.data.auth.AuthInterceptor]). */
interface ReferralApi {
  @GET("sakhi/{sakhiId}/referrals/pending-followup")
  suspend fun getPendingFollowUps(@Path("sakhiId") sakhiId: String): Response<ReferralFollowUpListResponseDto>

  /** Returns `201` for a genuinely new referral, `200` with the existing referral for a duplicate
   * `visitId` (idempotent as of backend's #197 fix, confirmed live 2026-08-27) — callers
   * distinguish the two by HTTP status code; the body shape is identical either way. See
   * [RemoteReferralRepository.createReferral]. */
  @POST("referrals")
  suspend fun createReferral(@Body request: CreateReferralRequestDto): Response<CreateReferralResponseDto>

  @POST("referrals/{referralId}/follow-up")
  suspend fun submitFollowUp(
    @Path("referralId") referralId: String,
    @Body request: SubmitReferralFollowUpRequestDto,
  ): Response<SubmitReferralFollowUpResponseDto>

  /** No request body — confirmed live 2026-08-27. Returns `409` if the referral is already
   * Accompanied. */
  @PATCH("referrals/{referralId}/convert")
  suspend fun convertToAccompanied(@Path("referralId") referralId: String): Response<CreateReferralResponseDto>
}
