package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormCrossFieldValidatorTest {

  private val lteRule = FormCrossFieldRule(
    rule = "LTE",
    fields = listOf("para_number_of_births_after_24_weeks", "gravida_total_number_of_pregnancies"),
  )

  // Deliberately NOT the Gravida/Living/StillBirths/Abortions combo — that exact signature is the
  // known-buggy rule under test separately below, and using it here would make these generic
  // SUM_EQUALS mechanism tests silently pass for the wrong reason (suppression, not a real match).
  private val sumEqualsRule = FormCrossFieldRule(
    rule = "SUM_EQUALS",
    fields = listOf("dose_1_count", "dose_2_count"),
    equals = "total_doses_given",
  )

  @Test
  fun `LTE passes when left is less than or equal to right`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        "para_number_of_births_after_24_weeks" to "1",
        "gravida_total_number_of_pregnancies" to "2",
      ),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(lteRule), answers).isEmpty())
  }

  @Test
  fun `LTE fails when left exceeds right`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        "para_number_of_births_after_24_weeks" to "3",
        "gravida_total_number_of_pregnancies" to "2",
      ),
    )
    assertEquals(listOf(lteRule), FormCrossFieldValidator.violatedRules(listOf(lteRule), answers))
  }

  @Test
  fun `LTE not evaluable yet when a field is unanswered`() {
    val answers = FormAnswers(singleValues = mapOf("gravida_total_number_of_pregnancies" to "2"))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(lteRule), answers).isEmpty())
  }

  @Test
  fun `SUM_EQUALS passes when the sum matches`() {
    val answers = FormAnswers(
      singleValues = mapOf("dose_1_count" to "1", "dose_2_count" to "2", "total_doses_given" to "3"),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(sumEqualsRule), answers).isEmpty())
  }

  @Test
  fun `SUM_EQUALS fails when the sum does not match`() {
    val answers = FormAnswers(
      singleValues = mapOf("dose_1_count" to "1", "dose_2_count" to "0", "total_doses_given" to "2"),
    )
    assertEquals(listOf(sumEqualsRule), FormCrossFieldValidator.violatedRules(listOf(sumEqualsRule), answers))
  }

  // --- Known-buggy Gravida SUM_EQUALS rule (2026-08-06) — must NEVER block submission ----------
  //
  // The live schema declares this exact SUM_EQUALS rule with no "-1" adjustment for the current
  // pregnancy, contradicting FormObstetricRuleset's (server-verified) rule and making Submit
  // mathematically impossible for any Gravida answered alongside a prior-pregnancy outcome. See
  // isKnownBuggyGravidaSumRule's doc.

  private val gravidaSumRule = FormCrossFieldRule(
    rule = "SUM_EQUALS",
    fields = listOf(
      FormObstetricRuleset.LIVING_CHILDREN,
      FormObstetricRuleset.STILL_BIRTHS,
      FormObstetricRuleset.ABORTIONS,
    ),
    equals = FormObstetricRuleset.GRAVIDA,
  )

  @Test
  fun `the known-buggy Gravida SUM_EQUALS rule is never reported as violated`() {
    // The exact real-world case that was blocking Submit: the CORRECT answer (sum = gravida - 1)
    // fails the schema's own un-adjusted rule every time.
    val correctPerFormObstetricRuleset = FormAnswers(
      singleValues = mapOf(
        FormObstetricRuleset.LIVING_CHILDREN to "0",
        FormObstetricRuleset.STILL_BIRTHS to "0",
        FormObstetricRuleset.ABORTIONS to "0",
        FormObstetricRuleset.GRAVIDA to "1",
      ),
    )

    assertTrue(
      FormCrossFieldValidator.violatedRules(listOf(gravidaSumRule), correctPerFormObstetricRuleset).isEmpty(),
    )
  }

  @Test
  fun `the known-buggy Gravida SUM_EQUALS rule is suppressed regardless of field order`() {
    val reordered = gravidaSumRule.copy(
      fields = listOf(
        FormObstetricRuleset.ABORTIONS,
        FormObstetricRuleset.LIVING_CHILDREN,
        FormObstetricRuleset.STILL_BIRTHS,
      ),
    )
    val answers = FormAnswers(
      singleValues = mapOf(
        FormObstetricRuleset.LIVING_CHILDREN to "0",
        FormObstetricRuleset.STILL_BIRTHS to "0",
        FormObstetricRuleset.ABORTIONS to "0",
        FormObstetricRuleset.GRAVIDA to "1",
      ),
    )

    assertTrue(FormCrossFieldValidator.violatedRules(listOf(reordered), answers).isEmpty())
  }

  @Test
  fun `a SUM_EQUALS rule with the SAME fields but a DIFFERENT equals target is still enforced`() {
    // Guards against over-broad suppression: only the exact (fields, equals) combo is skipped.
    val differentTarget = gravidaSumRule.copy(equals = "some_other_field")
    val answers = FormAnswers(
      singleValues = mapOf(
        FormObstetricRuleset.LIVING_CHILDREN to "1",
        FormObstetricRuleset.STILL_BIRTHS to "0",
        FormObstetricRuleset.ABORTIONS to "0",
        "some_other_field" to "99",
      ),
    )

    assertEquals(listOf(differentTarget), FormCrossFieldValidator.violatedRules(listOf(differentTarget), answers))
  }

  @Test
  fun `a SUM_EQUALS rule targeting Gravida with DIFFERENT fields is still enforced`() {
    val differentFields = gravidaSumRule.copy(fields = listOf(FormObstetricRuleset.PARA))
    val answers = FormAnswers(
      singleValues = mapOf(FormObstetricRuleset.PARA to "5", FormObstetricRuleset.GRAVIDA to "2"),
    )

    assertEquals(listOf(differentFields), FormCrossFieldValidator.violatedRules(listOf(differentFields), answers))
  }

  private val anyOfRequiredRule = FormCrossFieldRule(
    rule = "ANY_OF_REQUIRED",
    fields = listOf("date_of_birth", "age_of_the_beneficiary"),
  )

  @Test
  fun `ANY_OF_REQUIRED fails when every listed field is empty`() {
    assertEquals(
      listOf(anyOfRequiredRule),
      FormCrossFieldValidator.violatedRules(listOf(anyOfRequiredRule), FormAnswers()),
    )
  }

  @Test
  fun `ANY_OF_REQUIRED passes when only one listed field is answered`() {
    val dobOnly = FormAnswers(singleValues = mapOf("date_of_birth" to "2000-01-01"))
    val ageOnly = FormAnswers(singleValues = mapOf("age_of_the_beneficiary" to "25"))

    assertTrue(FormCrossFieldValidator.violatedRules(listOf(anyOfRequiredRule), dobOnly).isEmpty())
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(anyOfRequiredRule), ageOnly).isEmpty())
  }

  @Test
  fun `ANY_OF_REQUIRED passes when both listed fields are answered`() {
    val both = FormAnswers(
      singleValues = mapOf("date_of_birth" to "2000-01-01", "age_of_the_beneficiary" to "25"),
    )

    assertTrue(FormCrossFieldValidator.violatedRules(listOf(anyOfRequiredRule), both).isEmpty())
  }

  @Test
  fun `unknown rule type is never a violation`() {
    val rule = FormCrossFieldRule(rule = "SOME_FUTURE_RULE", fields = listOf("a", "b"))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(rule), FormAnswers()).isEmpty())
  }
}
