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

/** `motherCaseDetails` block of `GET /beneficiaries/:id` (CR-037) — MOTHER cases only; null on a
 * CHILD case's response. */
data class MotherCaseDetailsDto(
  val lmpDate: String?,
  val eddDate: String?,
  val gravida: Int?,
  val parity: Int?,
  val heightCm: Double?,
  val bmiAtRegistration: Double?,
)

/** `childCaseDetails` block of `GET /beneficiaries/:id` (CR-037) — CHILD cases only; null on a
 * MOTHER case's response. [currentPhase] and [ccvOpeningRiskState] are BE's own documented known
 * gaps as of this cycle: `currentPhase` is set once at enrolment and can go stale rather than
 * advancing with the case (NN -> INC -> CCV), and `ccvOpeningRiskState` may read null even for a
 * CCV-phase child until its write-path is exercised — not something this app can detect or correct
 * client-side, so both are surfaced as-is rather than validated. */
data class ChildCaseDetailsDto(
  val currentPhase: String?,
  val ccvOpeningRiskState: String?,
)

/** One `riskConditionSummaries` entry of `GET /beneficiaries/:id` (CR-037) — a single risk
 * condition's latest grading. [conditionCode]/[conditionName]/[gradeScale] are all independently
 * nullable per BE: they read null (not an error) when risk-referral-service is briefly unreachable
 * at request time, so a caller must tolerate a summary with no name rather than treat it as
 * malformed. [gradeScale] does not imply a different grade vocabulary — every [latestGrade] comes
 * from the same shared 6-value set (`NORMAL, MILD, MODERATE, SEVERE, HIGH, CRITICAL`); it only
 * describes which subset of those a given condition realistically produces. */
data class RiskConditionSummaryDto(
  val riskConditionId: String?,
  val phase: String?,
  val latestGrade: String?,
  val latestAssessedAt: String?,
  val everHighestGrade: String?,
  val everAtRiskFlag: Boolean?,
  val currentReferralTriggerFlag: Boolean?,
  val currentHrVisitTriggerFlag: Boolean?,
  val conditionCode: String?,
  val conditionName: String?,
  val gradeScale: String?,
)

data class BeneficiaryDetailDto(
  val id: String?,
  /** `MOTHER` / `CHILD` — needed by the profile mapper (CR-037) to pick MOTHER vs CHILD case
   * fields; earlier readers of this DTO ([RemoteMotherLinkRepository]) never needed it since their
   * caller already knows the case type going in. */
  val caseType: String? = null,
  val currentStatus: String? = null,
  val registrationDate: String? = null,
  val consentRecords: List<ConsentRecordDto>?,
  /** Only [BeneficiaryPiiDto.address]/[BeneficiaryPiiDto.mobileNumber] are read from here — every
   * other `pii` property CR-031 needs already comes from the list row. CR-037's profile mapper
   * additionally reads [BeneficiaryPiiDto.fullName]/[dateOfBirth]/[villageId]/[padaId] from the
   * same block, since a remote-only beneficiary has no list row already cached to read them from. */
  val pii: BeneficiaryPiiDto? = null,
  val socioDemographics: SocioDemographicsDto? = null,
  /** MOTHER-only; null on a CHILD case (CR-037). */
  val motherCaseDetails: MotherCaseDetailsDto? = null,
  /** CHILD-only; null on a MOTHER case (CR-037). */
  val childCaseDetails: ChildCaseDetailsDto? = null,
  /** Per-condition risk grading (CR-037) — empty (not null) for a beneficiary with no assessment
   * yet, e.g. a "Not yet assessed" row. */
  val riskConditionSummaries: List<RiskConditionSummaryDto>? = null,
  /** BE-aggregated overall risk across every entry in [riskConditionSummaries]
   * (`none`/`mild`/`moderate`/`high`) — worst grade wins, same rule as the pada visit-list badge.
   * `none` when there are no summaries or none are graded (CR-037). A same-cycle `riskColor` field
   * also exists on this response but is deliberately not modelled here: the app already derives its
   * own risk colours from [org.armman.sakhi.data.beneficiary.RiskLevel] via
   * [org.armman.sakhi.ui.components.RiskBadge], so a second, BE-asserted colour would be redundant
   * at best and a second source of truth to keep in sync at worst. */
  val riskLevel: String? = null,
  /** Deliberately NOT modelled yet. `lastVisitVitals` is new on this endpoint this cycle
   * (weight/BP/temperature/hemoglobin/MUAC/respiratory-rate extracted from the most recent visit's
   * form data), but its exact JSON shape has not been confirmed against a live sample — mapping a
   * guessed shape risks silently misreading a real clinical reading. Add the field and its mapping
   * together, once a real response is available; until then [BeneficiaryProfile.lastVisitStats]
   * stays empty for a beneficiary served by [RemoteBeneficiaryProfileRepository], same as it always
   * has been for one served by [StaticBeneficiaryProfileRepository]. */
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
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository] for My Beneficiaries, and by
 * [org.armman.sakhi.data.beneficiaryprofile.RemoteBeneficiaryProfileRepository] for the beneficiary
 * profile screen (CR-037). Backend caveats that shape how these callers use it, verified against the
 * live service:
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
