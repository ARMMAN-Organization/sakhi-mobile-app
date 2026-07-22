package org.armman.sakhi.data.lookup

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/** One value row, as `/lookups/:categoryCode` returns it. */
data class LookupValueDto(
  val id: String,
  val valueCode: String,
  val valueLabel: String,
  val sortOrder: Int,
  val parentLookupValueId: String?,
  val isActive: Boolean,
)

/** `data` payload of a successful `/lookups/:categoryCode` response. */
data class LookupCategoryData(
  val id: String,
  val categoryCode: String,
  val categoryName: String,
  val description: String?,
  val isActive: Boolean,
  val values: List<LookupValueDto>,
)

/** Envelope — same success/message/data shape as every other auth-service endpoint. */
data class LookupCategoryResponseDto(
  val success: Boolean,
  val message: String?,
  val data: LookupCategoryData?,
)

/** Retrofit contract for the auth-service lookup endpoints (master data: beneficiary type,
 * case type, and any future category). Requires a Bearer token, attached by [org.armman.sakhi.data.auth.AuthInterceptor]. */
interface LookupApi {
  @GET("lookups/{categoryCode}")
  suspend fun getCategory(@Path("categoryCode") categoryCode: String): Response<LookupCategoryResponseDto>
}
