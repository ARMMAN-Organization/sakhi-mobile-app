package org.armman.sakhi.data.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers every field-level mismatch confirmed against the live CHILD_REGISTRATION schema and the
 * beneficiary-service lookup seed data — not synthetic cases. */
class LookupLabelMatcherTest {

  // MATCH-01 — the common case: an exact label match, just differently cased.
  @Test
  fun `exact match ignores case`() {
    val options = listOf("self" to "Self", "husband" to "Husband")
    assertEquals("self", LookupLabelMatcher.match("Self", options))
  }

  // MATCH-02 — income brackets: symbols (`≤`, `>`) differ between the two sides but must still
  // resolve, and distinct brackets must never collide after normalization.
  @Test
  fun `normalizes symbols so income brackets match across sides`() {
    val options = listOf(
      "10000" to "≤10000",
      "10001_15000" to "10001-15000",
      "25000" to ">25000",
    )
    assertEquals("10000", LookupLabelMatcher.match("<=10000", options))
    assertEquals("25000", LookupLabelMatcher.match(">25000", options))
  }

  // MATCH-03 — education: the API's label is a truncated prefix of the schema's fuller label.
  @Test
  fun `longest prefix fallback resolves a truncated external label`() {
    val options = listOf(
      "no_formal_education_never_attended_school_cannot_read_or_write" to
        "No formal education (Never attended school / cannot read or write)",
      "primary_education_class_1_5" to "Primary education (Class 1–5)",
    )
    assertEquals(
      "no_formal_education_never_attended_school_cannot_read_or_write",
      LookupLabelMatcher.match("No formal education", options),
    )
  }

  // MATCH-04 — network availability: same shape as education, a different field.
  @Test
  fun `longest prefix fallback resolves network availability`() {
    val options = listOf(
      "very_poor_network_have_to_go_height_which_is_10_minutes_walk_away" to
        "Very Poor Network( Have to go height which is 10 minutes walk away)",
      "full_network_available" to "Full Network Available",
    )
    assertEquals(
      "very_poor_network_have_to_go_height_which_is_10_minutes_walk_away",
      LookupLabelMatcher.match("Very Poor Network", options),
    )
  }

  // MATCH-05 — no option's label is a prefix match either way: never guess.
  @Test
  fun `returns null when nothing matches confidently`() {
    val options = listOf("self" to "Self", "husband" to "Husband")
    assertNull(LookupLabelMatcher.match("Neighbour", options))
  }

  // MATCH-06
  @Test
  fun `returns null for a blank or null external label`() {
    val options = listOf("self" to "Self")
    assertNull(LookupLabelMatcher.match(null, options))
    assertNull(LookupLabelMatcher.match("   ", options))
  }

  // MATCH-07 — an empty option list (question missing from the schema) must not throw.
  @Test
  fun `returns null against an empty option list`() {
    assertNull(LookupLabelMatcher.match("Self", emptyList()))
  }
}
