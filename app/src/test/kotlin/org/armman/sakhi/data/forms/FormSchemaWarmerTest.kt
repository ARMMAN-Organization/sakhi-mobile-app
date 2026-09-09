package org.armman.sakhi.data.forms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.connectivity.FakeConnectivityObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-Forms-01 ("Forms not loading when offline"). Same shape as [ReconnectLookupWarmerTest] --
 * [FormSchemaWarmer] mirrors [ReconnectLookupWarmer] exactly, just warming
 * [KnownFormCodes.ALL] form schemas instead of lookup categories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSchemaWarmerTest {

  @Test
  // UnconfinedTestDispatcher: start() launches an infinite StateFlow collector on the given scope;
  // the unconfined dispatcher runs that collector (and its initial-value warm) eagerly, so the
  // assertions see the warm without racing the default StandardTestDispatcher's scheduling.
  fun `warms every known form code the moment connectivity returns`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = false)
    val forms = FakeFormsRepository()
    FormSchemaWarmer(observer, forms).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    // Offline at start — nothing to fetch yet.
    assertTrue(forms.requestedFormCodes.isEmpty())

    observer.set(true)
    testScheduler.advanceUntilIdle()
    assertTrue(forms.requestedFormCodes.containsAll(KnownFormCodes.ALL))
    assertEquals(KnownFormCodes.ALL.size, forms.requestedFormCodes.size)
  }

  @Test
  fun `already-online at start warms once and a repeat online signal is not double-fired`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = true)
    val forms = FakeFormsRepository()
    FormSchemaWarmer(observer, forms).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    val afterFirstWarm = forms.requestedFormCodes.size
    assertTrue("expected an initial warm when already online", afterFirstWarm > 0)
    assertEquals(KnownFormCodes.ALL.size, afterFirstWarm)

    // A redundant "still online" signal must not re-request anything.
    observer.set(true)
    testScheduler.advanceUntilIdle()
    assertEquals(afterFirstWarm, forms.requestedFormCodes.size)
  }

  @Test
  fun `each offline to online transition warms again`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = true)
    val forms = FakeFormsRepository()
    FormSchemaWarmer(observer, forms).start(backgroundScope)
    testScheduler.advanceUntilIdle()
    val afterFirstWarm = forms.requestedFormCodes.size

    // Advance between transitions so the collector observes the offline state (a StateFlow
    // conflates set(false)+set(true) applied back-to-back into just the latest value).
    observer.set(false)
    testScheduler.advanceUntilIdle()
    observer.set(true)
    testScheduler.advanceUntilIdle()

    assertEquals(afterFirstWarm * 2, forms.requestedFormCodes.size)
  }

  @Test
  fun `one form code throwing does not stop the rest from being warmed`() = runTest(UnconfinedTestDispatcher()) {
    val observer = FakeConnectivityObserver(initial = false)
    val forms = object : FormsRepository {
      val requested = mutableListOf<String>()
      override suspend fun getActiveVersion(formCode: String): FormVersion? {
        requested += formCode
        if (formCode == KnownFormCodes.REFERRAL_VISIT) error("simulated failure")
        return null
      }
    }
    FormSchemaWarmer(observer, forms).start(backgroundScope)
    testScheduler.advanceUntilIdle()

    observer.set(true)
    testScheduler.advanceUntilIdle()

    // Every known form code was still attempted, including the ones after the one that threw.
    assertEquals(KnownFormCodes.ALL, forms.requested)
  }

  @Test
  fun `warmAll fetches every known form code exactly once when called directly`() = runTest {
    val forms = FakeFormsRepository()
    FormSchemaWarmer(FakeConnectivityObserver(initial = false), forms).warmAll()

    assertEquals(KnownFormCodes.ALL, forms.requestedFormCodes)
  }
}
