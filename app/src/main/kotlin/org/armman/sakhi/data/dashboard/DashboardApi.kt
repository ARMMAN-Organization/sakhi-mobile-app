package org.armman.sakhi.data.dashboard

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/** `sakhi` block of `GET /sakhi/{sakhiId}/dashboard`. */
data class DashboardSakhiDto(
  val id: String?,
  val name: String?,
)

/** `beneficiarySummary` block. */
data class BeneficiarySummaryDto(
  val totalActiveBeneficiaries: Int?,
  val activeMothersCount: Int?,
  val activeChildrenCount: Int?,
  /** High-risk subset of [activeMothersCount] — the red count in the Active Beneficiaries tile. */
  val activeMothersHighRiskCount: Int?,
  /** High-risk subset of [activeChildrenCount] — the red count in the Active Beneficiaries tile. */
  val activeChildrenHighRiskCount: Int?,
  val activeMothersPercent: Double?,
  val activeChildrenPercent: Double?,
)

/** `referralSummary` block. */
data class ReferralSummaryDto(
  val accompaniedReferralsCount: Int?,
  val pendingFollowUpsCount: Int?,
)

/** `visitSummary` block. */
data class VisitSummaryDto(
  val dueVisitsCount: Int?,
  val overdueVisitsCount: Int?,
  /** Subset of (due + overdue) whose visit window is about to close — the purple "(Ending)"
   * count on the Home Open tile. */
  val endingSoonVisitsCount: Int?,
)

/** `data` block of `GET /sakhi/{sakhiId}/dashboard`. Only the properties the app reads are
 * modelled — Gson leaves anything else out, and the backend adding a property must not break
 * the parse. */
data class DashboardDataDto(
  val sakhi: DashboardSakhiDto?,
  val lastSyncedAt: String?,
  val beneficiarySummary: BeneficiarySummaryDto?,
  val referralSummary: ReferralSummaryDto?,
  val visitSummary: VisitSummaryDto?,
  val version: Int?,
)

data class DashboardResponseDto(
  val success: Boolean,
  val message: String?,
  val data: DashboardDataDto?,
)

/** Retrofit contract for the Sakhi dashboard summary, behind the same API gateway/base URL and
 * Bearer token as every other service ([org.armman.sakhi.data.auth.AuthInterceptor]). */
interface DashboardApi {
  @GET("sakhi/{sakhiId}/dashboard")
  suspend fun getDashboard(@Path("sakhiId") sakhiId: String): Response<DashboardResponseDto>
}
