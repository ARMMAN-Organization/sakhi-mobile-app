package org.armman.sakhi.data.beneficiary

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "local_beneficiary_status_override_"
private const val CLOSURE_REASON_KEY_PREFIX = "local_beneficiary_closure_reason_"

/**
 * On-device optimistic override of a locally-enrolled beneficiary's [BeneficiaryStatus] — set once
 * a closure submission succeeds (or is queued while offline) so the Closed tab reflects it
 * immediately, without waiting for the next `GET /beneficiaries` pull to confirm it server-side.
 * Same "write optimistically, let the next pull reconcile" shape
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository] already applies to a
 * server-sourced row's `currentStatus`, applied here to a row that started out purely local (see
 * [LocalEnrolmentBeneficiarySource]).
 *
 * Judgment call: a plain [SecureKeyValueStore] entry keyed by beneficiary id, rather than a new
 * Room column on `dynamic_form_drafts`/`child_registration_drafts` — a migration on two tables for
 * one nullable enum felt disproportionate to this pass; flagged for review. A future pass that
 * needs more optimistic local beneficiary state should probably fold this into a real column
 * instead of growing this store further.
 */
@Singleton
class LocalBeneficiaryStatusOverrideStore @Inject constructor(
  private val store: SecureKeyValueStore,
) {
  fun setStatus(localBeneficiaryId: String, status: BeneficiaryStatus) {
    store.putString(KEY_PREFIX + localBeneficiaryId, status.name)
  }

  /** Null when no override was ever set — the caller falls back to its own default (ACTIVE for a
   * freshly enrolled beneficiary that has never had any closure). */
  fun getStatus(localBeneficiaryId: String): BeneficiaryStatus? {
    val raw = store.getString(KEY_PREFIX + localBeneficiaryId) ?: return null
    return runCatching { BeneficiaryStatus.valueOf(raw) }.getOrNull()
  }

  /**
   * CR-Closure-02: the `CLOSURE_REASON` lookup category's backend code (e.g. `"MATERNAL_DEATH"`,
   * `"MIGRATION"`) this beneficiary was closed under, set alongside [setStatus] by
   * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator.submitClosure] — the only
   * place this app currently learns a closure reason at all, since there is no
   * `GET /closures` readback endpoint yet (see CR-Closure-01's backend ask #1). Only meaningful
   * for a closure THIS device submitted; a beneficiary closed elsewhere, or before this field
   * existed, has no entry here even though [getStatus] correctly reports CLOSED for her.
   */
  fun setClosureReason(localBeneficiaryId: String, closureReasonBackendCode: String) {
    store.putString(CLOSURE_REASON_KEY_PREFIX + localBeneficiaryId, closureReasonBackendCode)
  }

  /** Null when no closure reason was ever recorded for this device — see [setClosureReason]'s doc
   * for why that is a real, expected case and not just "never closed". */
  fun getClosureReason(localBeneficiaryId: String): String? =
    store.getString(CLOSURE_REASON_KEY_PREFIX + localBeneficiaryId)

  /**
   * CR-Closure-04: clears both the status and closure-reason overrides for [localBeneficiaryId] —
   * called once [org.armman.sakhi.data.reopen.ReopenRepository.hasApprovedReopenRequest] confirms
   * a reopen request was approved server-side, so the next [getStatus] read falls back to its own
   * ACTIVE default instead of continuing to report the now-stale local CLOSED override. Idempotent
   * — clearing an override that was never set (or already cleared) is a no-op either way.
   */
  fun clearOverride(localBeneficiaryId: String) {
    store.remove(KEY_PREFIX + localBeneficiaryId)
    store.remove(CLOSURE_REASON_KEY_PREFIX + localBeneficiaryId)
  }
}
