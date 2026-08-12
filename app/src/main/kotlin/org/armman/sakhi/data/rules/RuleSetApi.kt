package org.armman.sakhi.data.rules

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * One published rule version, as returned by `GET /admin/rules/:setId` (rules-service API
 * reference §4). Open to the `SAKHI` role — a Sakhi's device fetching a `SCHEDULE` or `RISK`
 * rule set's `rulesJson` to cache and evaluate offline is exactly what this endpoint is for.
 *
 * [rulesJson] is the full GoRules decision-graph JSON (JDM format — nodes/edges); its shape is
 * opaque to the app, which only loads it into the local rule evaluator
 * ([org.armman.sakhi.data.rules.RuleEvaluator]) and never inspects it directly.
 */
data class PublishedRuleVersionDto(
  val id: String,
  val ruleSetId: String,
  val versionNo: String,
  val rulesJson: JsonObject,
  val effectiveFrom: String,
  val effectiveTo: String? = null,
  val publishedByUserId: String? = null,
  val status: String,
)

data class RuleSetEnvelopeDto(
  val success: Boolean,
  val message: String? = null,
  val data: PublishedRuleVersionDto? = null,
)

/**
 * Retrofit contract for rules-service's published-rule-version endpoint. Requires a Bearer token,
 * attached by [org.armman.sakhi.data.auth.AuthInterceptor] — same as every other authenticated API
 * in the app.
 *
 * Deliberately narrow: this app only ever needs "the currently published version of one rule
 * set," never the admin listing/create/publish endpoints (`GET/POST /rules`,
 * `POST /admin/rules/:setId/publish`) — those are `ADMIN`-only and belong to whatever tool manages
 * rules server-side, not the Sakhi app.
 */
interface RuleSetApi {
  @GET("admin/rules/{setId}")
  suspend fun getPublishedVersion(@Path("setId") setId: String): Response<RuleSetEnvelopeDto>
}
