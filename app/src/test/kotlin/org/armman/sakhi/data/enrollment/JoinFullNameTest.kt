package org.armman.sakhi.data.enrollment

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [joinFullName] is the ONE place first/middle/last answers become the `pii.fullName` string
 * [BeneficiaryPiiDto] sends — see that DTO's doc for why (the backend's PII contract has flipped
 * between a single `fullName` and split fields three times as of 2026-08-06).
 */
class JoinFullNameTest {

  @Test
  fun `joins all three parts with single spaces`() {
    assertEquals("Reema Kumari Devi", joinFullName("Reema", "Kumari", "Devi"))
  }

  @Test
  fun `a null middle name is skipped, not left as a double space`() {
    assertEquals("Reema Devi", joinFullName("Reema", null, "Devi"))
  }

  @Test
  fun `a blank or whitespace-only middle name is skipped the same as null`() {
    assertEquals("Reema Devi", joinFullName("Reema", "", "Devi"))
    assertEquals("Reema Devi", joinFullName("Reema", "   ", "Devi"))
  }

  @Test
  fun `each part is trimmed before joining`() {
    // Guards the exact bug this replaced: padding must not survive into the PII field or the
    // name-based duplicate-detection hash built from it.
    assertEquals("Reema Kumari Devi", joinFullName("  Reema  ", "  Kumari  ", "  Devi  "))
  }
}
