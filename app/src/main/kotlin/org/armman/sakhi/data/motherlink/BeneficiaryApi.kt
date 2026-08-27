package org.armman.sakhi.data.motherlink

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
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
  /** Enrichment field (added to `beneficiary-service` alongside the ANC_VISIT visibleWhen fixes,
   * this same sprint) — the server-resolved name of the village behind `pii.villageId`. Null when
   * that id no longer resolves to a village row (stale/deleted data), same graceful-degradation
   * contract as `sakhiName`/`projectName` below. This is what [PADA_UNRESOLVED] in
   * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository] was waiting on — see that
   * file's own doc comment. */
  val villageName: String? = null,
  /** Enrichment field, same contract as [villageName] — the server-resolved display name of the
   * Sakhi behind this row's `sakhiId`. Not currently surfaced in the app UI (My Beneficiaries is a
   * single Sakhi's own list, so it would be redundant there); modelled here so it round-trips
   * cleanly through Gson and is available if a Manager/Admin-facing view needs it later. */
  val sakhiName: String? = null,
  /** Enrichment field, same contract as [villageName] — the server-resolved display name of the
   * project the beneficiary is enrolled under. Not currently surfaced in the app UI, for the same
   * reason as [sakhiName]. */
  val projectName: String? = null,
)

/**
 * `data` is typed as a raw [JsonElement], not `List<BeneficiaryListItemDto>`, because the backend
 * has shipped TWO different shapes for it, confirmed against live traffic:
 *  - a bare array — production (`api.armman.org`) today.
 *  - `{ "items": [...] }` — a newer backend build (seen 2026-08-07 behind an ngrok tunnel), likely
 *    in preparation for the pagination this endpoint doesn't support yet (risk R3, see
 *    [BeneficiaryApi]'s class doc). Gson can't deserialize a JSON object straight into a `List`,
 *    so a typed field here would throw and silently drop every row on whichever backend sends this
 *    shape — every caller must go through [items] instead of touching [data] directly.
 * Delete this and go back to a plain typed list once every environment agrees on one shape.
 */
data class BeneficiaryListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: JsonElement?,
) {

  /** [data] normalized to a plain list regardless of which of the two shapes above the backend
   * sent. Empty (never null) for anything unexpected, so callers never need their own null/shape
   * handling on top of this. */
  val items: List<BeneficiaryListItemDto>
    get() {
      val element = data ?: return emptyList()
      val arrayElement = when {
        element.isJsonArray -> element
        element.isJsonObject -> element.asJsonObject.get("items")?.takeIf { it.isJsonArray }
        else -> null
      } ?: return emptyList()
      return runCatching {
        beneficiaryListItemGson.fromJson<List<BeneficiaryListItemDto>>(
          arrayElement,
          object : TypeToken<List<BeneficiaryListItemDto>>() {}.type,
        )
      }.getOrDefault(emptyList())
    }
}

/** Plain, unconfigured [Gson] — matches [org.armman.sakhi.di.NetworkModule]'s
 * `GsonConverterFactory.create()`, which is also unconfigured. Only used to re-parse the `items`/
 * bare-array sub-tree above; every field on [BeneficiaryListItemDto] is a plain String, so no
 * custom adapter is needed here any more than the Retrofit converter needed one. */
private val beneficiaryListItemGson = Gson()

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
 * Originally the mother-link picker's alone (CR-031); now also consumed by
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository] for My Beneficiaries. Backend
 * caveats that shape how BOTH callers use it, verified against the live service:
 *
 * 1. **No pagination.** The repository hardcodes `take: 50, orderBy createdAt desc` and returns no
 *    total. Above 50 rows the caller silently truncates — tracked as risk R3.
 * 2. **`name` is an exact HMAC-hash match**, not a substring search, so it is useless for typeahead
 *    and deliberately not exposed here. Filtering happens client-side over the cached list.
 * 3. **`sakhiId` is intentionally absent** from this contract: the service derives no scope from the
 *    token today (risk R2) and offers no `sakhiId` filter, so there is nothing to send. Once scoping
 *    lands server-side this contract does not change — the gap is entirely server-side, which is why
 *    [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryListFeatureFlag] keeps the My Beneficiaries
 *    caller off until that fix ships.
 */
interface BeneficiaryApi {
  /** [caseType]/[status] required — the mother-link picker's own narrow, always-mother-always-active
   * contract (CR-031). Prefer [listAll] for anything that needs more than one caseType/status. */
  @GET("beneficiaries")
  suspend fun list(
    @Query("caseType") caseType: String,
    @Query("status") status: String,
  ): Response<BeneficiaryListResponseDto>

  /**
   * [caseType]/[status] optional (the backend DTO already treats both as optional) — omitting either
   * asks for every case type / every status the caller is allowed to see. My Beneficiaries uses this
   * to fetch both MOTHER and CHILD rows across all three statuses in one call, then buckets them into
   * tabs client-side exactly the way the local-only source already does.
   *
   * A distinct method rather than making [list]'s params nullable: Retrofit resolves a
   * `@Query` from the interface's declared type at the call site, not from a default value (default
   * parameter values on a Retrofit interface method are a known trap — the generated proxy bypasses
   * Kotlin's synthetic `$default` overload), so every caller must pass both explicitly either way.
   * Two clearly-named methods reads better than one with two nullable, easy-to-forget params.
   */
  @GET("beneficiaries")
  suspend fun listAll(
    @Query("caseType") caseType: String?,
    @Query("status") status: String?,
  ): Response<BeneficiaryListResponseDto>

  @GET("beneficiaries/{id}")
  suspend fun detail(@Path("id") id: String): Response<BeneficiaryDetailResponseDto>
}
