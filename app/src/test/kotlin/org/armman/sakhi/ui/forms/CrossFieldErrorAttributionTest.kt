package org.armman.sakhi.ui.forms

import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossFieldErrorAttributionTest {

  private fun lte(higher: String, limit: String) =
    FormCrossFieldRule(rule = "LTE", fields = listOf(higher, limit))

  private fun sumEquals(parts: List<String>, total: String) =
    FormCrossFieldRule(rule = "SUM_EQUALS", fields = parts, equals = total)

  @Test
  fun `an LTE rule is attributed to the value that is too high`() {
    // "children under 5 <= family members": the Sakhi corrects the children count, so the message
    // belongs there rather than under the limit it exceeded.
    val rule = lte("children_under_five", "household_members")

    assertEquals("children_under_five", CrossFieldErrorAttribution.attributedFieldOf(rule))
    assertEquals(mapOf("children_under_five" to rule), CrossFieldErrorAttribution.byQuestionCode(listOf(rule)))
  }

  @Test
  fun `a SUM_EQUALS rule is attributed to the total that does not match`() {
    val rule = sumEquals(listOf("living_children", "still_births", "abortions"), "gravida")

    assertEquals("gravida", CrossFieldErrorAttribution.attributedFieldOf(rule))
  }

  @Test
  fun `the first rule wins when two land on the same field`() {
    val first = lte("children_under_five", "household_members")
    val second = lte("children_under_five", "living_children")

    val attributed = CrossFieldErrorAttribution.byQuestionCode(listOf(first, second))

    // One field never stacks messages; the second rule still blocks submit and still shows in the
    // Summary banner.
    assertEquals(1, attributed.size)
    assertEquals(first, attributed["children_under_five"])
  }

  @Test
  fun `each field keeps its own rule when they differ`() {
    val children = lte("children_under_five", "household_members")
    val para = lte("para", "gravida")

    val attributed = CrossFieldErrorAttribution.byQuestionCode(listOf(children, para))

    assertEquals(children, attributed["children_under_five"])
    assertEquals(para, attributed["para"])
  }

  @Test
  fun `malformed and unknown rules are skipped rather than crashing`() {
    // Same fail-open treatment FormCrossFieldValidator gives them: an unusable rule is ignored, not
    // rendered as an empty error.
    assertNull(CrossFieldErrorAttribution.attributedFieldOf(FormCrossFieldRule("LTE", listOf("only_one"))))
    assertNull(CrossFieldErrorAttribution.attributedFieldOf(FormCrossFieldRule("SUM_EQUALS", listOf("a", "b"))))
    assertNull(CrossFieldErrorAttribution.attributedFieldOf(FormCrossFieldRule("FUTURE_RULE", listOf("a", "b"))))

    assertTrue(
      CrossFieldErrorAttribution.byQuestionCode(
        listOf(FormCrossFieldRule("LTE", listOf("only_one")), FormCrossFieldRule("FUTURE_RULE", emptyList())),
      ).isEmpty(),
    )
  }

  @Test
  fun `no violations means nothing to show`() {
    assertTrue(CrossFieldErrorAttribution.byQuestionCode(emptyList()).isEmpty())
  }
}
