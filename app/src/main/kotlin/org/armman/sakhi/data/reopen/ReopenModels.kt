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
  OTHER(wireValue = "OTHER", displayLabel = "Other"),
}

private const val SUPERVISOR_STATUS_PENDING = "PENDING"

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
  suspend fun submitReopenRequest(beneficiaryId: String, reason: ReopenRequestReason)

  /** True if [beneficiaryId] has at least one reopen request whose `supervisorStatus` is still
   * `"PENDING"` — best-effort: any fetch failure (offline, error response) is treated as "no
   * pending request known", same as every other best-effort read in this app (e.g.
   * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel]'s own `canStartVisit`
   * fallback) rather than failing the whole profile load. */
  suspend fun hasPendingReopenRequest(beneficiaryId: String): Boolean
}

internal fun ReopenRequestRowDto.isPending(): Boolean = supervisorStatus == SUPERVISOR_STATUS_PENDING
