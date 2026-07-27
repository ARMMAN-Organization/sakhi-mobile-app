package org.armman.sakhi.data.forms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.connectivity.FakeConnectivityObserver
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupWarmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSyncTriggerTest {

  @Test
  fun `does not sync while offline, then syncs and warms lookups the moment connectivity returns`() = runTest {
    val observer = FakeConnectivityObserver(initial = false)
    val scheduler = FakeDynamicFormSyncScheduler()
    val lookups = FakeLookupRepository()
    ReconnectSyncTrigger(observer, scheduler, LookupWarmer(lookups)).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    // Offline at start — nothing to do yet.
    assertEquals(0, scheduler.syncNowCallCount)
    assertTrue(lookups.requestedCategories.isEmpty())

    // Network returns — an upload attempt is triggered and the submit-critical lookups are warmed,
    // both without any user action.
    observer.set(true)
    testScheduler.advanceUntilIdle()
    assertEquals(1, scheduler.syncNowCallCount)
    assertTrue(lookups.requestedCategories.containsAll(listOf("CASE_TYPE", "BENEFICIARY_TYPE")))
  }

  @Test
  fun `already-online at start triggers one sync and a repeat online is not double-fired`() = runTest {
    val observer = FakeConnectivityObserver(initial = true)
    val scheduler = FakeDynamicFormSyncScheduler()
    ReconnectSyncTrigger(observer, scheduler, LookupWarmer(FakeLookupRepository())).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    // Online at start fires once (harmless safety-net sync, like the app-start periodic schedule).
    assertEquals(1, scheduler.syncNowCallCount)

    // A redundant "still online" signal must not re-trigger.
    observer.set(true)
    testScheduler.advanceUntilIdle()
    assertEquals(1, scheduler.syncNowCallCount)
  }

  @Test
  fun `each offline to online transition triggers a fresh sync`() = runTest {
    val observer = FakeConnectivityObserver(initial = true)
    val scheduler = FakeDynamicFormSyncScheduler()
    ReconnectSyncTrigger(observer, scheduler, LookupWarmer(FakeLookupRepository())).start(backgroundScope)
    testScheduler.advanceUntilIdle()
    assertEquals(1, scheduler.syncNowCallCount)

    // Advance between transitions so the collector observes the offline state (a StateFlow conflates
    // set(false)+set(true) applied back-to-back into just the latest value).
    observer.set(false)
    testScheduler.advanceUntilIdle()
    observer.set(true)
    testScheduler.advanceUntilIdle()

    assertEquals(2, scheduler.syncNowCallCount)
  }
}
