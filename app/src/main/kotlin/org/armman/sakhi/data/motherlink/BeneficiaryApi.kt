package org.armman.sakhi.data.motherlink

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** `pii` block of a beneficiary row. Every geography property is a `geographyUnitId`. */
data class BeneficiaryPiiDto(
  val id: String?,
  val fullName: String?,
  val villageId: String?,
  val padaId: String?,
  val healthSubCentreId: String?,
  val phcId: String?,
  val healthBlockId: String?,
  val dateOfBirth: String?,
  val sex: String?,
  val stateId: String?,
  val districtId: String?,
  val talukaId: String?,
)

/** One row of `GET /beneficiaries`. Only the properties CR-031 reads are modelled — Gson leaves
 * anything else out, and the backend adding a property must not break the parse. */
data class BeneficiaryListItemDto(
  val id: String?,
  val caseType: String?,
  val currentStatus: String?,
  val currentPhase: String?,
  val registrationDate: String?,
  val motherBeneficiaryId: String?,
  val pii: BeneficiaryPiiDto?,
)

data class BeneficiaryListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: List<BeneficiaryListItemDto>?,
)

/** One `consentRecords` entry from the detail endpoint. The backend returns only the latest. */
data class ConsentRecordDto(
  val consentType: String?,
  val consentStatus: String?,
  val consentDate: String?,
)

data class BeneficiaryDetailDto(
  val id: String?,
  val consentRecords: List<ConsentRecordDto>?,
)

data class BeneficiaryDetailResponseDto(
  val success: Boolean,
  val message: String?,
  val data: BeneficiaryDetailDto?,
)

/**
 * Retrofit contract for `beneficiary-service`'s read endpoints, behind the same API gateway/base URL
 * and Bearer token as every other service ([org.armman.sakhi.data.auth.AuthInterceptor]).
 *
 * **This is the app's first consumer of `/beneficiaries`.** Two backend caveats that shape how it is
 * used, both verified against the live service:
 *
 * 1. **No pagination.** The repository hardcodes `take: 50, orderBy createdAt desc` and returns no
 *    total. Above 50 mothers per Sakhi the picker silently truncates — tracked as risk R3.
 * 2. **`name` is an exact HMAC-hash match**, not a substring search, so it is useless for typeahead
 *    and deliberately not exposed here. Filtering happens client-side over the cached list.
 *
 * `sakhiId` is intentionally absent: the service derives no scope from the token today (risk R2) and
 * offers no `sakhiId` filter, so there is nothing to send. Once scoping lands server-side this
 * contract does not change.
 */
interface BeneficiaryApi {
  @GET("beneficiaries")
  suspend fun list(
    @Query("caseType") caseType: String,
    @Query("status") status: String,
  ): Response<BeneficiaryListResponseDto>

  @GET("beneficiaries/{id}")
  suspend fun detail(@Path("id") id: String): Response<BeneficiaryDetailResponseDto>
}
