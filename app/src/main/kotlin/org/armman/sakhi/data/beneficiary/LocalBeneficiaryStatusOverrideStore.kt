package org.armman.sakhi.data.beneficiary

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_PREFIX = "local_beneficiary_status_override_"

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
}
