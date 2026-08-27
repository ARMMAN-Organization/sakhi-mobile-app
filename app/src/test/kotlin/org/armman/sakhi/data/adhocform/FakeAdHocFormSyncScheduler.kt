package org.armman.sakhi.data.adhocform

/** Counting fake, mirroring `FakeVisitFormSyncScheduler`'s conventions. */
class FakeAdHocFormSyncScheduler : AdHocFormSyncScheduler {
  var syncNowCallCount = 0
    private set

  override fun syncNow() {
    syncNowCallCount++
  }
}
