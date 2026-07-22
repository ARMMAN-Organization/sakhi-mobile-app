package org.armman.sakhi.data.forms

/** Records invocations instead of touching real WorkManager — mirrors
 * `FakeEnrollmentSyncScheduler`. */
class FakeDynamicFormSyncScheduler : DynamicFormSyncScheduler {
  var syncNowCallCount = 0
  var ensurePeriodicSyncScheduledCallCount = 0

  override fun syncNow() {
    syncNowCallCount++
  }

  override fun ensurePeriodicSyncScheduled() {
    ensurePeriodicSyncScheduledCallCount++
  }
}
