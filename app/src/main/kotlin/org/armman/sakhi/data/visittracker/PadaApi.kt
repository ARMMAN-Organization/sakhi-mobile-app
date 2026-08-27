package org.armman.sakhi.data.visittracker

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/** One visit-type bucket (Open, or Referral Follow-up) within a pada's summary. The API returns
 * [childOverdueCount] alongside [womenOverdueCount], but the `(N)` highlighted count shown next
 * to a bucket's count is women-only by design — [childOverdueCount] is parsed but intentionally
 * not rendered anywhere (see [PadaVisitBucket] / PadaCard). */
data class PadaVisitBucketDto(
  val womenCount: Int?,
  val womenOverdueCount: Int?,
  val childCount: Int?,
  val childOverdueCount: Int?,
)

/** One entry of `GET /sakhi/{sakhiId}/padas`'s `data.padas` array. */
data class PadaDto(
  val padaId: String?,
  val padaName: String?,
  val villageName: String?,
  val open: PadaVisitBucketDto?,
  val referralFollowUp: PadaVisitBucketDto?,
  val visitsRemainingCount: Int?,
)

data class PadaListDataDto(
  val padas: List<PadaDto>?,
)

data class PadaListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: PadaListDataDto?,
)

/** Retrofit contract for the pada/village visit summary, behind the same API gateway/base URL
 * and Bearer token as every other service ([org.armman.sakhi.data.auth.AuthInterceptor]). */
interface PadaApi {
  @GET("sakhi/{sakhiId}/padas")
  suspend fun getPadas(@Path("sakhiId") sakhiId: String): Response<PadaListResponseDto>
}
