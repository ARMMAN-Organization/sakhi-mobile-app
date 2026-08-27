package org.armman.sakhi.data.previsithealth

/**
 * Pre-Visit Health History data boundary (FR-S-4.6). UI depends only on this
 * interface; the backing implementation is [RemotePreVisitHealthHistoryRepository],
 * backed by `GET /beneficiaries/:beneficiaryId/visit-history`, bound in DI.
 * Implementations throw [NoSuchElementException] if the request itself fails —
 * callers must only reach this screen when [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit.hasPreVisitHistory]
 * is true, so that case is a caller bug, not a normal "first visit" outcome. A
 * beneficiary with no completed visits *yet on the server* (e.g. offline sync
 * pending) is not an error — it returns an empty [PreVisitHealthHistory].
 *
 * A beneficiary with no server id at all (enrolled/visited entirely offline, never
 * synced) throws [BeneficiaryNotSyncedException] instead — see that class's own
 * doc for why callers should treat it differently from a genuine failure.
 */
interface PreVisitHealthHistoryRepository {
  suspend fun getHealthHistory(beneficiaryId: String, visitId: String): PreVisitHealthHistory
}

/**
 * Thrown by [RemotePreVisitHealthHistoryRepository] when a beneficiary has no
 * server-assigned id yet — i.e. she (and/or her visit schedule) has never
 * synced. This is a normal, expected state for an offline-first app, not a
 * failure: a Sakhi can enroll a beneficiary and complete her first visit fully
 * offline before ever reaching connectivity.
 *
 * Callers should treat this distinctly from other exceptions — there is
 * nothing to retry and nothing wrong; [org.armman.sakhi.ui.previsithealthhistory
 * .PreVisitHealthHistoryViewModel] catches this specifically to skip straight
 * to the visit form rather than showing an error screen with a Retry button
 * that would only ever fail the same way again.
 */
class BeneficiaryNotSyncedException(beneficiaryId: String) :
  Exception("Beneficiary $beneficiaryId has no server id yet (not synced)")
