package org.armman.sakhi.data.childregistration

/** Records invocations instead of touching real WorkManager — the CR-020 twin of
 * `FakeDynamicFormSyncScheduler`. */
class FakeChildFormSyncScheduler : ChildFormSyncScheduler {
  var syncNowCallCount = 0

  override fun syncNow() {
    syncNowCallCount++
  }
}
