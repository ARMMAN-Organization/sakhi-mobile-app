package org.armman.sakhi.data.dashboard

import java.time.LocalDate
import java.time.YearMonth

/**
 * Home dashboard summary as the future `GET /dashboard/summary` API is
 * expected to return it. Static implementation supplies fixed values today.
 */
data class DashboardSummary(
  val sakhiName: String,
  // The Data Upload badge count is NOT part of the dashboard summary — it's derived live from the
  // local draft store (see HomeViewModel.pendingUploadCount), so a real sync always reflects it.
  val lastUploadedOn: LocalDate,
  val activeVisits: ActiveVisits,
  val activeBeneficiaries: ActiveBeneficiaries,
)

/** Visit counters for the current month. */
data class ActiveVisits(
  val month: YearMonth,
  val openCount: Int,
  val endingCount: Int,
  val pendingReferralCount: Int,
)

/** Beneficiary counters; high-risk counts are subsets of the totals. */
data class ActiveBeneficiaries(
  val mothersTotal: Int,
  val mothersHighRisk: Int,
  val infantsTotal: Int,
  val infantsHighRisk: Int,
)
