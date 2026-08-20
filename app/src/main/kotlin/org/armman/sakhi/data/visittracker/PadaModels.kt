package org.armman.sakhi.data.visittracker

/**
 * Women/Child counts for one visit-type bucket (Open or Referral Follow-up) within a pada.
 * [womenOverdueCount] is parsed from the API and carried through, but is the *only* highlighted
 * sub-count the design shows (rendered as `Women N(womenOverdueCount)`) — there is deliberately no
 * equivalent rendering for [childOverdueCount], per design confirmation 2026-08-17.
 */
data class PadaVisitBucket(
  val womenCount: Int,
  val womenOverdueCount: Int,
  val childCount: Int,
  val childOverdueCount: Int,
)

/**
 * Per-pada aggregate as returned by `GET /sakhi/{sakhiId}/padas` (confirmed against a live mock
 * response, 2026-08-14; nested Open/Referral Follow-up bucket shape confirmed live 2026-08-17,
 * replacing the earlier flat overdue/due/pendingReferralFollowUp counts). Restores the
 * Women/Child breakdown per Figma p63/p65 that the flat-count interim shape couldn't support —
 * see [org.armman.sakhi.ui.visittracker.PadaCard].
 */
data class PadaSummary(
  val padaId: String,
  val padaName: String,
  val villageName: String,
  val open: PadaVisitBucket,
  val referralFollowUp: PadaVisitBucket,
  val visitsRemainingCount: Int,
)

/**
 * Pada-summary data boundary. UI depends only on this interface; the backing implementation is
 * bound in DI ([org.armman.sakhi.di.PadaModule]).
 */
interface PadaRepository {
  suspend fun getPadaSummaries(): List<PadaSummary>
}
