package org.armman.sakhi.data.dashboard

import org.armman.sakhi.data.adhocform.AdHocFormDraftDao
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.referral.ReferralLinkDao
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's own ground-truth count of pending referral follow-ups, for the Home dashboard's
 * "Pending Referral Follow-up" tile — bharath, 2026-09-10 ("referral count also need to work in
 * offline too, like the visit count — do the mapping based on the beneficiary, not only from the
 * api call").
 *
 * ### Why this is a max-with-server overlay, not an additive one like [LocalVisitCounts]
 * [LocalVisitCounts]/[LocalVisitOverlay] are safe to ADD on top of the server's dashboard number
 * because they're scoped to schedules with `serverScheduleId == null` — a hard guarantee the
 * server has never seen that row, so it can never double-count. A
 * [org.armman.sakhi.data.referral.ReferralLinkEntity] row has no equivalent "never reached the
 * server" state: it is only ever written after `POST /referrals` already succeeded (see
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.cacheReferralLink]'s doc), so
 * every row here is something the server itself already created. What can lag is the *dashboard
 * aggregate* specifically — `GET /sakhi/{sakhiId}/dashboard`'s own cached/just-fetched number —
 * not the referral's existence. So this is read the same way
 * [org.armman.sakhi.data.visittracker.LocalPadaSummaryOverlay.mergeWithLocal] already treats the
 * per-pada referral count: this device's own tally can never be an over-count for this Sakhi's own
 * caseload, so [org.armman.sakhi.ui.home.HomeViewModel] takes the max of the two rather than
 * summing them.
 *
 * An interface (rather than a bare `@Inject constructor` class, like [LocalVisitCounts]'s plain
 * data-class shape) purely so [org.armman.sakhi.ui.home.HomeViewModel] can be unit-tested against
 * a trivial fake instead of constructing a real [LocalEnrolmentBeneficiarySource] — same
 * interface-plus-`Room`-prefixed-impl convention every other repository in `di/` already follows
 * (e.g. `RoomAdHocFormDraftRepository` / `AdHocFormDraftRepository`).
 */
interface LocalReferralFollowUpOverlay {
  suspend fun pendingCount(today: LocalDate = LocalDate.now()): Int
}

/**
 * ### Scope and exclusions
 * Scoped to beneficiaries THIS DEVICE currently knows about locally (same
 * [LocalEnrolmentBeneficiarySource] limitation
 * [org.armman.sakhi.ui.visittracker.PadaVisitsViewModel] already has — no cross-device
 * visibility). Matches `ReferralLinkEntity.beneficiaryId` against
 * [org.armman.sakhi.data.beneficiary.Beneficiary.id] — both the LOCAL beneficiary id since the
 * 2026-09-10 fix to
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.cacheReferralLink] (it used to
 * stamp the SERVER id there, which never matched this local id at all — see that function's own
 * doc for the bug this overlay would otherwise have silently inherited).
 *
 * Excludes a referral whose follow-up form has already been submitted on this device, even if
 * that submission is still queued for sync — same "already handled, not still outstanding" reason
 * [org.armman.sakhi.ui.visittracker.PadaVisitsViewModel]'s own `submittedReferralIds` filter
 * exists.
 */
@Singleton
class RoomLocalReferralFollowUpOverlay @Inject constructor(
  private val localEnrolmentBeneficiarySource: LocalEnrolmentBeneficiarySource,
  private val referralLinkDao: ReferralLinkDao,
  private val adHocFormDraftDao: AdHocFormDraftDao,
) : LocalReferralFollowUpOverlay {

  override suspend fun pendingCount(today: LocalDate): Int {
    val localBeneficiaryIds = localEnrolmentBeneficiarySource.getLocalBeneficiaries(today)
      .map { it.id }
      .toSet()
    if (localBeneficiaryIds.isEmpty()) return 0

    val pendingLinks = referralLinkDao.getPendingFollowUp()
      .filter { it.beneficiaryId in localBeneficiaryIds }
    if (pendingLinks.isEmpty()) return 0

    val submittedReferralIds = adHocFormDraftDao
      .getReferralFollowUpDraftsByReferralIds(pendingLinks.map { it.referralId })
      .filter { it.syncStatus != EnrollmentSyncStatus.SYNCED }
      .mapNotNull { it.referralId }
      .toSet()

    return pendingLinks.count { it.referralId !in submittedReferralIds }
  }
}
