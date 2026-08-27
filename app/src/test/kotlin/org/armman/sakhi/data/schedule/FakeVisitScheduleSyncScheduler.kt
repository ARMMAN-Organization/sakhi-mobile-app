package org.armman.sakhi.data.schedule

/** Counting fake, mirroring `FakeChildFormSyncScheduler`'s conventions. */
class FakeVisitScheduleSyncScheduler : VisitScheduleSyncScheduler {
  var syncNowCallCount = 0
    private set

  override fun syncNow() {
    syncNowCallCount++
  }
}
