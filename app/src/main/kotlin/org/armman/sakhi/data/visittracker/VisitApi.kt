package org.armman.sakhi.data.visittracker

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * One row of `GET /padas/{padaId}/visits`'s `data.visits` array. Every field but [beneficiaryId]
 * can independently come back null — a degraded name/phone/risk enrichment lookup still returns
 * the base visit row (see [org.armman.sakhi.data.visit.RemoteVisitRepository]). [visitId] is
 * always null for a `referral_follow_up` row.
 */
data class PadaVisitDto(
  val visitId: String?,
  val beneficiaryId: String?,
  val beneficiaryName: String?,
  val riskLevel: String?,
  val padaName: String?,
  val villageName: String?,
  val scheduledDate: String?,
  val dueDate: String?,
  val visitType: String?,
  val phoneNumber: String?,
)

/** `openCount`/`referralFollowUpCount` are present on every response, regardless of which
 * `status` was requested — one call updates both tab labels. */
data class PadaVisitsDataDto(
  val openCount: Int?,
  val referralFollowUpCount: Int?,
  val visits: List<PadaVisitDto>?,
)

data class PadaVisitsResponseDto(
  val success: Boolean,
  val message: String?,
  val data: PadaVisitsDataDto?,
)

/** Retrofit contract for a pada's today's-visits list, behind the same API gateway/base URL and
 * Bearer token as every other service ([org.armman.sakhi.data.auth.AuthInterceptor]). */
interface VisitApi {
  @GET("padas/{padaId}/visits")
  suspend fun getVisits(
    @Path("padaId") padaId: String,
    @Query("status") status: String,
    @Query("date") date: String? = null,
    @Query("search") search: String? = null,
  ): Response<PadaVisitsResponseDto>
}
