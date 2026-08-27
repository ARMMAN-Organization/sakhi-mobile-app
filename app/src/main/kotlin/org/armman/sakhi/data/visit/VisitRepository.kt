package org.armman.sakhi.data.visit

import java.time.LocalDate

/** The two `status` values `GET /padas/{padaId}/visits` accepts. */
enum class VisitStatus { OPEN, REFERRAL_FOLLOW_UP }

/**
 * One pada's visits for a given [VisitStatus], as returned by `GET /padas/{padaId}/visits`.
 * [openCount]/[referralFollowUpCount] come back identically regardless of which status was
 * requested — a single call refreshes both tab labels.
 */
data class PadaVisitsResult(
  val openCount: Int,
  val referralFollowUpCount: Int,
  val visits: List<Visit>,
)

/**
 * Pada-scoped visits data boundary. UI depends only on this interface; the backing implementation
 * (real API today) is bound in DI ([org.armman.sakhi.di.VisitModule]).
 */
interface VisitRepository {
  /**
   * @param date Defaults to today server-side when null. The API ignores this for
   * [VisitStatus.REFERRAL_FOLLOW_UP] — that bucket is never date-filtered.
   * @param search Exact beneficiary-name match only — names are encrypted, no partial/fuzzy match.
   */
  suspend fun getVisits(
    padaId: String,
    status: VisitStatus,
    date: LocalDate? = null,
    search: String? = null,
  ): PadaVisitsResult
}
