package org.armman.sakhi.data.visitform

/** Counting fake, mirroring `FakeVisitScheduleSyncScheduler`'s conventions. */
class FakeVisitFormSyncScheduler : VisitFormSyncScheduler {
  var syncNowCallCount = 0
    private set

  override fun syncNow() {
    syncNowCallCount++
  }
}
