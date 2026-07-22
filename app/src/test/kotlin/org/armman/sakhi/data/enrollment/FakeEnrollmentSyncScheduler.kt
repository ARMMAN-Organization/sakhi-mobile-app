package org.armman.sakhi.data.enrollment

/** Records invocations instead of touching real WorkManager, so [RoomEnrollmentRepository] tests
 * can assert a sync was nudged without needing a WorkManager test harness. */
class FakeEnrollmentSyncScheduler : EnrollmentSyncScheduler {
  var syncNowCallCount = 0
  var ensurePeriodicSyncScheduledCallCount = 0

  override fun syncNow() {
    syncNowCallCount++
  }

  override fun ensurePeriodicSyncScheduled() {
    ensurePeriodicSyncScheduledCallCount++
  }
}
