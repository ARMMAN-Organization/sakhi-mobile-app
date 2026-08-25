package org.armman.sakhi.data.rules

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * rules-service's non-admin, SAKHI-accessible rule-pack sync contract — confirmed against a live
 * backend instance 2026-08-24, replacing the two endpoints this interface used to expose:
 *
 *  - `GET /admin/rules/:setId` — ADMIN-only; every SAKHI-role call 403'd, so SCHEDULE rule-pack
 *    sync (ANC/PP/NN/INC/CCV/HR) never actually worked despite looking wired up.
 *  - `GET /rules/versions/:versionId` — open to SAKHI, but its `getById()` handler deliberately
 *    never returns `rulesJson` (by design, per the backend's own doc comment on that method) —
 *    RISK on-device grading was silently getting a `{id, ruleSetId, status}` stub back, not an
 *    error, so it never actually evaluated a real rule pack either.
 *
 * Both issues affected every id in [RuleSetIds] identically. The three endpoints below fix both
 * categories with one consistent, confirmed-correct contract instead of two separate fixes.
 */
interface RuleSetApi {

  /**
   * Resolves the currently PUBLISHED versionId for one rule set. 404 means "nothing published for
   * this set right now" — not fatal, callers fall back to whatever's already cached on-device.
   */
  @GET("rules/{setId}/published-version")
  suspend fun getPublishedVersionId(@Path("setId") setId: String): Response<PublishedVersionEnvelopeDto>

  /**
   * Fetches one specific rule version's full content by its version id (not the rule set id).
   * 404 covers both "unknown versionId" and "exists but not PUBLISHED" indistinguishably — an
   * unpublished pack's existence is never revealed to a non-admin caller. Not fatal; same
   * fall-back-to-cache contract as [getPublishedVersionId].
   */
  @GET("rules/versions/{versionId}/content")
  suspend fun getRuleVersionContent(@Path("versionId") versionId: String): Response<RuleContentEnvelopeDto>

  /**
   * Batch variant of the two calls above — one round trip for a full sync instead of up to 16.
   * [setIds] is comma-separated (matches this backend's existing `visitIds` convention, not
   * repeated query keys); max 50 per call per the confirmed contract.
   *
   * A requested id with no published version is **silently omitted** from the response array —
   * not a per-item error, not a 404 for the whole batch. Callers must not assume [data] on the
   * response's length or order matches the request; key off each item's own `ruleSetId`. Any id
   * absent from the result means "nothing published right now" — leave whatever's cached alone.
   */
  @GET("rules/published-content")
  suspend fun getPublishedContentBatch(@Query("setIds") setIds: String): Response<BatchRuleContentEnvelopeDto>
}

data class PublishedVersionDto(
  val versionId: String,
)

data class PublishedVersionEnvelopeDto(
  val success: Boolean,
  val message: String? = null,
  val data: PublishedVersionDto? = null,
)

/**
 * [rulesJson] is the full GoRules decision-graph JSON (JDM format — nodes/edges); its shape is
 * opaque to the app, which only loads it into the local rule evaluator
 * ([org.armman.sakhi.data.rules.RuleEvaluator]) and never inspects it directly. [id] here is the
 * rule *version* id (distinct from [ruleSetId]) — matches [CachedRuleSet.ruleVersionId].
 */
data class RuleVersionContentDto(
  val id: String,
  val ruleSetId: String,
  val versionNo: String,
  val rulesJson: JsonObject,
  val status: String,
)

data class RuleContentEnvelopeDto(
  val success: Boolean,
  val message: String? = null,
  val data: RuleVersionContentDto? = null,
)

/**
 * One entry in the batch response. Same content as [RuleVersionContentDto] but the confirmed
 * batch payload names the version-id field `versionId`, not `id` — kept as a distinct type rather
 * than reusing [RuleVersionContentDto] so a future divergence between the two contracts doesn't
 * silently misparse one of them.
 */
data class BatchRuleContentItemDto(
  val ruleSetId: String,
  val versionId: String,
  val versionNo: String,
  val rulesJson: JsonObject,
  val status: String,
)

data class BatchRuleContentEnvelopeDto(
  val success: Boolean,
  val message: String? = null,
  val data: List<BatchRuleContentItemDto> = emptyList(),
)
