package org.armman.sakhi.data.previsithealth

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** One vital's value + the unit it's stored in — present with a null [value] when that visit's
 * form didn't capture this vital, per the backend contract ("the field and its unit are always
 * present, only value goes null — branch on null, not on a missing key"). */
data class VitalValueDto(
  val value: String?,
  val unit: String?,
)

/** Blood pressure is the one vital shaped as two numbers rather than a single value — modelled as
 * its own DTO rather than overloading [VitalValueDto]. [systolic]/[diastolic] are plain integers
 * per the backend contract (every other vital's `value` is a string, to avoid float-precision
 * surprises on decimals; BP has no such risk). */
data class BloodPressureDto(
  val systolic: Int?,
  val diastolic: Int?,
  val unit: String?,
)

/** Fixed vital set for one completed visit. Temperature's [VitalValueDto.unit] is `"°F"` — every
 * visit form actually captures it in Fahrenheit; the backend does not convert, so neither do we
 * here (display-side conversion, if wanted, belongs in the UI layer, not this DTO). */
data class VisitVitalsDto(
  val hemoglobin: VitalValueDto?,
  val bloodPressure: BloodPressureDto?,
  val weight: VitalValueDto?,
  val bloodSugar: VitalValueDto?,
  val temperature: VitalValueDto?,
)

/** One completed visit row from `GET /beneficiaries/:beneficiaryId/visit-history`. */
data class VisitHistoryEntryDto(
  val visitId: String?,
  val visitCode: String?,
  /** ISO-8601 instant, e.g. `"2026-08-20T11:07:00.012Z"`. */
  val completedAt: String?,
  val vitals: VisitVitalsDto?,
)

data class VisitHistoryDataDto(
  val visits: List<VisitHistoryEntryDto>?,
)

data class VisitHistoryResponseDto(
  val success: Boolean,
  val message: String?,
  val data: VisitHistoryDataDto?,
)

/**
 * Retrofit contract for `visit-form-service`'s Pre-Visit Health History endpoint (FR-S-4.6).
 * Bearer token attached by [org.armman.sakhi.data.auth.AuthInterceptor], same as every other
 * authenticated API in the app. [beneficiaryId] must be the *server*-assigned id — see
 * [RemotePreVisitHealthHistoryRepository]'s own doc for how the caller resolves that from the
 * screen's local nav argument.
 *
 * A caller outside their own roster gets `403`; a beneficiary with no completed visits yet gets
 * `200` with an empty `visits` array, not an error — both handled by the repository, not here.
 */
interface PreVisitHealthHistoryApi {
  @GET("beneficiaries/{beneficiaryId}/visit-history")
  suspend fun getVisitHistory(
    @Path("beneficiaryId") beneficiaryId: String,
    @Query("limit") limit: Int,
  ): Response<VisitHistoryResponseDto>
}
