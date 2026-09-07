package org.armman.sakhi.data.lmpchange

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "local_lmp_change_applied_request_id_"

/**
 * Idempotency guard for [org.armman.sakhi.data.schedule.VisitScheduleCoordinator
 * .onLmpOrEddApproved] (task 3, LMP/Reopen/Referral/Audit task list) — that function's own doc
 * says regeneration "legitimately supersedes" the prior schedule and callers must gate on the
 * approval themselves, it does not guard its own idempotency. Without a guard here,
 * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel.loadProfile] — which re-runs
 * on every profile screen entry, not just once — would re-supersede-and-regenerate the
 * beneficiary's whole ANC schedule on every single visit to her profile after one approval, which
 * is destructive, not just wasteful.
 *
 * Same "plain [SecureKeyValueStore] entry keyed by beneficiary id" judgment call as
 * [org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore] makes for the same
 * reason (a Room migration for one nullable string felt disproportionate to this pass) — flagged
 * for the same future review that store's own doc already flags.
 */
@Singleton
class LocalLmpChangeAppliedStore @Inject constructor(
  private val store: SecureKeyValueStore,
) {
  /** The id of the last LMP change request this device has already regenerated the schedule for,
   * or null if none yet — the caller compares this against the currently-approved request's own
   * id to decide whether regeneration is still due. */
  fun getAppliedRequestId(localBeneficiaryId: String): String? =
    store.getString(KEY_PREFIX + localBeneficiaryId)

  fun setAppliedRequestId(localBeneficiaryId: String, requestId: String) {
    store.putString(KEY_PREFIX + localBeneficiaryId, requestId)
  }
}
