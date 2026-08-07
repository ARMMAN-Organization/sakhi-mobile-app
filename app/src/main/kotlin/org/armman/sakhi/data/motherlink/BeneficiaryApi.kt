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
  /** Rows 21/22 (CR-032) — only present on the detail response, never on the list row. */
  val address: String? = null,
  val mobileNumber: String? = null,
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

/**
 * One already-resolved lookup inside `socioDemographics` (CR-032) — `beneficiary-service` looks the
 * `*LookupId` up server-side and reports back its own `categoryCode`/`valueCode`/`label`. The app
 * never reads [valueCode] directly: it is upper-snake-case in `beneficiary-service`'s convention and
 * never matches the CHILD_REGISTRATION form schema's own lower-snake-case `value_code` byte-for-byte
 * (confirmed against the schema for all 8 fields this DTO covers). [label] is what
 * [org.armman.sakhi.data.lookup.LookupLabelMatcher] matches against the form schema's own option
 * labels instead.
 */
data class ResolvedLookupDto(
  val categoryCode: String?,
  val valueCode: String?,
  val label: String?,
)

/**
 * `socioDemographics` block of `GET /beneficiaries/:id` (CR-032) — rows 23–34 of the mother's
 * socio-demographic details. Every property is independently nullable: a Sakhi may not have
 * answered every question at the mother's own registration.
 */
data class SocioDemographicsDto(
  val phoneOwner: ResolvedLookupDto?,
  val mobileNetworkAvailability: ResolvedLookupDto?,
  val educationLevel: ResolvedLookupDto?,
  val partnerEducationLevel: ResolvedLookupDto?,
  val partnerOccupation: ResolvedLookupDto?,
  val migrationPattern: ResolvedLookupDto?,
  val monthlyIncome: ResolvedLookupDto?,
  val religion: ResolvedLookupDto?,
  val socialCategory: ResolvedLookupDto?,
  val yearsInVillage: Int?,
  val familyMembersCount: Int?,
  val childrenUnder5Count: Int?,
)

data class BeneficiaryDetailDto(
  val id: String?,
  val consentRecords: List<ConsentRecordDto>?,
  /** Only [BeneficiaryPiiDto.address]/[BeneficiaryPiiDto.mobileNumber] are read from here — every
   * other `pii` property CR-031 needs already comes from the list row. */
  val pii: BeneficiaryPiiDto? = null,
  val socioDemographics: SocioDemographicsDto? = null,
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
