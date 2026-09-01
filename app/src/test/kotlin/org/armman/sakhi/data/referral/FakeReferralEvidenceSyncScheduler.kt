package org.armman.sakhi.data.referral

/** Counting fake, mirroring `FakeVisitFormSyncScheduler`'s conventions. */
class FakeReferralEvidenceSyncScheduler : ReferralEvidenceSyncScheduler {
  var syncNowCallCount = 0
    private set

  override fun syncNow() {
    syncNowCallCount++
  }
}
