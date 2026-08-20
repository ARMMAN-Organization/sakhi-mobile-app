package org.armman.sakhi.data.referral

import java.time.LocalDate

/** `items[].status` of `GET /sakhi/{sakhiId}/referrals/pending-followup`. Only
 * `"PENDING_FOLLOWUP"` has been observed; anything else (a future status value) maps to
 * [UNKNOWN] rather than failing to deserialize. */
enum class ReferralFollowUpStatus { PENDING_FOLLOWUP, UNKNOWN }

/**
 * One pending referral follow-up as returned by `GET /sakhi/{sakhiId}/referrals/pending-followup`
 * (confirmed against a live mock response, 2026-08-14).
 *
 * [daysRemaining] is preserved exactly as the backend sends it — `0` means due today, and a
 * hypothetical negative value means overdue; callers decide urgency styling, nothing is clamped
 * here.
 */
data class ReferralFollowUp(
  val referralId: String,
  val beneficiaryId: String,
  val beneficiaryName: String,
  val referralDate: LocalDate?,
  val followUpDueDate: LocalDate?,
  val daysRemaining: Int,
  val status: ReferralFollowUpStatus,
)

/**
 * Referral-follow-up data boundary. UI depends only on this interface; the backing implementation
 * is bound in DI ([org.armman.sakhi.di.ReferralModule]).
 *
 * NOTE: this data layer has no screen consuming it yet — the PRD places the referral follow-up
 * list inside Visit Tracker, whose tab structure (Open/Referral vs. per-visit-type) is still an
 * open product decision (tracked separately). The Dashboard's `pendingFollowUpsCount` stat is
 * sourced independently from [org.armman.sakhi.data.dashboard.DashboardSummary], not from this.
 */
interface ReferralRepository {
  suspend fun getPendingFollowUps(): List<ReferralFollowUp>
}
