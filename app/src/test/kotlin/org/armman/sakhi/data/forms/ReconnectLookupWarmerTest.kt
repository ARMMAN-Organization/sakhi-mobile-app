package org.armman.sakhi.data.forms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.connectivity.FakeConnectivityObserver
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupWarmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaces the former `ReconnectSyncTriggerTest`. That class asserted the opposite of what the SRS
 * requires — that reconnecting **auto-synced** pending drafts. Under manual-only sync (SRS §3A.1)
 * reconnect must warm reference data and nothing else, which is what these tests pin down.
 *
 * The strongest guarantee here isn't a test at all: [ReconnectLookupWarmer] no longer takes a
 * scheduler as a constructor argument, so it is not *able* to start a sync. These tests cover the
 * behaviour that remains.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectLookupWarmerTest {

  @Test
  // UnconfinedTestDispatcher: start() launches an infinite StateFlow collector on the given scope;
  // the unconfined dispatcher runs that collector (and its initial-value warm) eagerly, so the
  // assertions see the warm without racing the default StandardTestDispatcher's scheduling.
  fun `warms submit-critical lookups the moment connectivity returns`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = false)
    val lookups = FakeLookupRepository()
    ReconnectLookupWarmer(observer, LookupWarmer(lookups)).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    // Offline at start — nothing to fetch yet.
    assertTrue(lookups.requestedCategories.isEmpty())

    observer.set(true)
    testScheduler.advanceUntilIdle()
    assertTrue(lookups.requestedCategories.containsAll(listOf("CASE_TYPE", "BENEFICIARY_TYPE")))
  }

  @Test
  fun `already-online at start warms once and a repeat online signal is not double-fired`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = true)
    val lookups = FakeLookupRepository()
    ReconnectLookupWarmer(observer, LookupWarmer(lookups)).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    val afterFirstWarm = lookups.requestedCategories.size
    assertTrue("expected an initial warm when already online", afterFirstWarm > 0)

    // A redundant "still online" signal must not re-request anything.
    observer.set(true)
    testScheduler.advanceUntilIdle()
    assertEquals(afterFirstWarm, lookups.requestedCategories.size)
  }

  @Test
  fun `each offline to online transition warms again`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = true)
    val lookups = FakeLookupRepository()
    ReconnectLookupWarmer(observer, LookupWarmer(lookups)).start(backgroundScope)
    testScheduler.advanceUntilIdle()
    val afterFirstWarm = lookups.requestedCategories.size

    // Advance between transitions so the collector observes the offline state (a StateFlow
    // conflates set(false)+set(true) applied back-to-back into just the latest value).
    observer.set(false)
    testScheduler.advanceUntilIdle()
    observer.set(true)
    testScheduler.advanceUntilIdle()

    assertEquals(afterFirstWarm * 2, lookups.requestedCategories.size)
  }
}
