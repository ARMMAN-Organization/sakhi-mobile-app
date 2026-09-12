package org.armman.sakhi.data.delivery

/** Counting fake, mirroring `FakeAdHocFormSyncScheduler`'s conventions. */
class FakeDeliveryChildRegistrationSyncScheduler : DeliveryChildRegistrationSyncScheduler {
  var syncNowCallCount = 0
    private set

  override fun syncNow() {
    syncNowCallCount++
  }
}
