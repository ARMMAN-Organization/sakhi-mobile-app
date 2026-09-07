package org.armman.sakhi.data.reopen

/**
 * `requestReason` values `POST /reopen-requests` accepts, per the confirmed contract — a fixed
 * three-value enum server-side, unlike closure reasons (which come from the `CLOSURE_REASON`
 * lookup category and can be extended without a client change). Kept as a real Kotlin enum rather
 * than routed through [org.armman.sakhi.data.lookup.LookupRepository] for that reason.
 */
enum class ReopenRequestReason(val wireValue: String, val displayLabel: String) {
  MIGRATION_RETURNED(wireValue = "MIGRATION_RETURNED", displayLabel = "Migrated back"),
  CLOSED_BY_MISTAKE(wireValue = "CLOSED_BY_MISTAKE", displayLabel = "Closed by mistake"),
  // OTHER dropped (CR-Closure-02): SRS v3.0's Beneficiary Reopen form only ever offered these two
  // reasons ("Migrated back" / "Closed by mistake") -- OTHER had no basis in the spec and is
  // removed as part of re-enabling this screen, not merely hidden.
}

private const val SUPERVISOR_STATUS_PENDING = "PENDING"
private const val SUPERVISOR_STATUS_APPROVED = "APPROVED"
private const val SUPERVISOR_STATUS_REJECTED = "REJECTED"

/**
 * Reopen-request data boundary. UI depends only on this interface; the backing implementation is
 * bound in DI ([org.armman.sakhi.di.ReopenModule]).
 */
interface ReopenRepository {
  /** Submits a new reopen request for [beneficiaryId] with [reason]. Throws on failure — same
   * throwing contract as [org.armman.sakhi.data.closure.ClosureRepository.submitClosure] — the
   * caller (the profile screen's ViewModel) is expected to catch and show a message; unlike a
   * closure this is not queued through the ad-hoc-form offline-sync machinery, since Reopen no
   * longer opens a schema-driven ad-hoc form at all (see [org.armman.sakhi.ui.beneficiaryprofile
   * .BeneficiaryProfileScreen]'s Footer doc for why). */
  suspend fun submitReopenRequest(beneficiaryId: String, reason: ReopenRequestReason, localReopenRequestUuid: String)

  /** True if [beneficiaryId] has at least one reopen request whose `supervisorStatus` is still
   * `"PENDING"` — best-effort: any fetch failure (offline, error response) is treated as "no
   * pending request known", same as every other best-effort read in this app (e.g.
   * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel]'s own `canStartVisit`
   * fallback) rather than failing the whole profile load. */
  suspend fun hasPendingReopenRequest(beneficiaryId: String): Boolean

  /**
   * CR-Closure-04: true if [beneficiaryId] has at least one reopen request whose
   * `supervisorStatus` is `"APPROVED"` — the confirmed CR-Closure-01 backend contract (2026-08-31)
   * says an APPROVED decision already reactivates the beneficiary and resumes her visit schedules
   * server-side, but this app has no push/webhook for that (confirmed: none exists) and, for a
   * locally enrolled beneficiary, reads her status from [org.armman.sakhi.data.beneficiary
   * .LocalBeneficiaryStatusOverrideStore] rather than a server round trip (see
   * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource.buildBeneficiary]). This is
   * the poll [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel.loadProfile] uses
   * to notice that flip and clear the stale local override. Best-effort like
   * [hasPendingReopenRequest] — a fetch failure just means "not known approved yet", which is the
   * safe default (the beneficiary simply stays CLOSED locally until the next successful check).
   */
  suspend fun hasApprovedReopenRequest(beneficiaryId: String): Boolean

  /**
   * Task-6 (LMP/Reopen/Referral/Audit task list, #6 rejection half): true if [beneficiaryId] has
   * at least one reopen request whose `supervisorStatus` is `"REJECTED"` — mirrors
   * [hasApprovedReopenRequest] exactly, just for the other terminal outcome. A rejected request
   * does NOT change the beneficiary's status (she stays CLOSED — only an APPROVED decision
   * reactivates her server-side), so unlike the approved case this poll doesn't clear any local
   * override; it only drives [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel]
   * showing a "request rejected" banner so the Sakhi isn't left wondering why nothing happened.
   * Best-effort like [hasPendingReopenRequest]/[hasApprovedReopenRequest] — a fetch failure just
   * means "not known rejected yet", the same safe default used everywhere else in this file.
   */
  suspend fun hasRejectedReopenRequest(beneficiaryId: String): Boolean
}

internal fun ReopenRequestRowDto.isPending(): Boolean = supervisorStatus == SUPERVISOR_STATUS_PENDING

/** CR-Closure-04: mirrors [isPending] for the decided-and-approved case. */
internal fun ReopenRequestRowDto.isApproved(): Boolean = supervisorStatus == SUPERVISOR_STATUS_APPROVED

/** Mirrors [isApproved] for the decided-and-rejected case (task-6 rejection half). */
internal fun ReopenRequestRowDto.isRejected(): Boolean = supervisorStatus == SUPERVISOR_STATUS_REJECTED
