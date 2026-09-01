package org.armman.sakhi.data.beneficiary

import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** CR-Closure-02: covers the closure-reason override added alongside the pre-existing status
 * override — see [LocalBeneficiaryStatusOverrideStore.setClosureReason]'s doc for why it's a
 * separate, independently-nullable key rather than folded into the status enum itself. */
class LocalBeneficiaryStatusOverrideStoreTest {
  private lateinit var store: LocalBeneficiaryStatusOverrideStore

  @Before
  fun setUp() {
    store = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore())
  }

  @Test
  fun `getStatus is null before any override is set`() {
    assertNull(store.getStatus("ben-1"))
  }

  @Test
  fun `getClosureReason is null before any closure is recorded`() {
    assertNull(store.getClosureReason("ben-1"))
  }

  @Test
  fun `setStatus and setClosureReason round-trip independently per beneficiary`() {
    store.setStatus("ben-1", BeneficiaryStatus.CLOSED)
    store.setClosureReason("ben-1", "MIGRATION")

    assertEquals(BeneficiaryStatus.CLOSED, store.getStatus("ben-1"))
    assertEquals("MIGRATION", store.getClosureReason("ben-1"))
    // A different beneficiary's key must not see this one's values.
    assertNull(store.getStatus("ben-2"))
    assertNull(store.getClosureReason("ben-2"))
  }

  @Test
  fun `a beneficiary can be CLOSED with no closure reason recorded`() {
    // Models the real "closed on another device" / "closed before this field existed" case —
    // see LocalBeneficiaryStatusOverrideStore.getClosureReason's own doc.
    store.setStatus("ben-1", BeneficiaryStatus.CLOSED)

    assertEquals(BeneficiaryStatus.CLOSED, store.getStatus("ben-1"))
    assertNull(store.getClosureReason("ben-1"))
  }
}
