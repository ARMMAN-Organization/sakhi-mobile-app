package org.armman.sakhi.data.referral

import retrofit2.Response
import retrofit2.http.GET
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

/** Retrofit contract for pending referral follow-ups, behind the same API gateway/base URL and
 * Bearer token as every other service ([org.armman.sakhi.data.auth.AuthInterceptor]). */
interface ReferralApi {
  @GET("sakhi/{sakhiId}/referrals/pending-followup")
  suspend fun getPendingFollowUps(@Path("sakhiId") sakhiId: String): Response<ReferralFollowUpListResponseDto>
}
