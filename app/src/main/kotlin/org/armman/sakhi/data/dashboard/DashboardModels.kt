package org.armman.sakhi.data.dashboard

import java.time.Instant

/**
 * Home dashboard summary as returned by `GET /sakhi/{sakhiId}/dashboard`. Beneficiary counts now
 * carry a high-risk subset ([activeMothersHighRiskCount] / [activeChildrenHighRiskCount]) and
 * visits carry an ending-soon subset ([endingSoonVisitsCount]) — added 2026-08 to match the Figma
 * "count | high-risk" and "Open N (Ending M)" tiles (bharath). The percent fields are kept for
 * now (still parsed from the API) even though the current Home UI renders the raw counts instead.
 */
data class DashboardSummary(
  val sakhiName: String,
  /** Null when the backend has never recorded a sync for this Sakhi. */
  val lastSyncedAt: Instant?,
  val totalActiveBeneficiaries: Int,
  val activeMothersCount: Int,
  val activeChildrenCount: Int,
  /** High-risk subset of [activeMothersCount]. */
  val activeMothersHighRiskCount: Int,
  /** High-risk subset of [activeChildrenCount]. */
  val activeChildrenHighRiskCount: Int,
  /** Already a percentage (e.g. 43.75), not a 0..1 fraction. */
  val activeMothersPercent: Double,
  val activeChildrenPercent: Double,
  val accompaniedReferralsCount: Int,
  val pendingFollowUpsCount: Int,
  val dueVisitsCount: Int,
  val overdueVisitsCount: Int,
  /** Subset of (overdueVisitsCount + dueVisitsCount) whose visit window is about to close. */
  val endingSoonVisitsCount: Int,
)
