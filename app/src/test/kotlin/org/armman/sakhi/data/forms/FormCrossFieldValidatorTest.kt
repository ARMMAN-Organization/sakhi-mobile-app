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

  // --- Supplemental children-under-5 <= family-members rule --------------------------------
  //
  // Both MOTHER_REGISTRATION and CHILD_REGISTRATION ship an EMPTY `validationJson`, so nothing in
  // the backend-supplied `rules` list enforces spec rows 33/34. FormCrossFieldValidator injects
  // this LTE itself (SUPPLEMENTAL_RULES) — these tests exercise it directly via an EMPTY input
  // rule list, proving the check does not depend on the schema declaring it.

  @Test
  fun `supplemental rule flags children under 5 exceeding family members even with no schema rules`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        CHILDREN_UNDER_FIVE_QUESTION_CODE to "5",
        FAMILY_MEMBERS_QUESTION_CODE to "2",
      ),
    )
    assertEquals(1, FormCrossFieldValidator.violatedRules(emptyList(), answers).size)
  }

  @Test
  fun `supplemental rule passes when children under 5 does not exceed family members`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        CHILDREN_UNDER_FIVE_QUESTION_CODE to "2",
        FAMILY_MEMBERS_QUESTION_CODE to "5",
      ),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(emptyList(), answers).isEmpty())
  }

  @Test
  fun `supplemental rule is not evaluable yet when only one side is answered`() {
    val answers = FormAnswers(singleValues = mapOf(FAMILY_MEMBERS_QUESTION_CODE to "2"))
    assertTrue(FormCrossFieldValidator.violatedRules(emptyList(), answers).isEmpty())
  }

  @Test
  fun `supplemental rule stays inert for a form that has neither question code`() {
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(lteRule), FormAnswers()).isEmpty())
  }

  // --- REQUIRED_IF_SELECTED (ANC_CLOSURE_VISIT / CHILD_CLOSURE_VISIT "other, please specify") --
  //
  // Backend's literal seed shape: `field` names the trigger multiselect question, `optionFieldMap`
  // maps a selected option code to the question_code that becomes required when that option is
  // selected. Trigger answers live in FormAnswers.multiValues (multiselect); a non-array/absent
  // answer means the rule doesn't apply at all — see FormCrossFieldValidator's own doc.

  private val requiredIfSelectedRule = FormCrossFieldRule(
    rule = "REQUIRED_IF_SELECTED",
    fields = emptyList(),
    field = "maternal_death_cause",
    optionFieldMap = mapOf("other" to "maternal_death_cause_other_specify"),
  )

  @Test
  fun `REQUIRED_IF_SELECTED fails when the trigger option is selected and the target field is blank`() {
    val answers = FormAnswers(multiValues = mapOf("maternal_death_cause" to listOf("other")))
    assertEquals(
      listOf(requiredIfSelectedRule),
      FormCrossFieldValidator.violatedRules(listOf(requiredIfSelectedRule), answers),
    )
  }

  @Test
  fun `REQUIRED_IF_SELECTED passes when the trigger option is selected and the target field is answered`() {
    val answers = FormAnswers(
      multiValues = mapOf("maternal_death_cause" to listOf("other")),
      singleValues = mapOf("maternal_death_cause_other_specify" to "Sepsis"),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(requiredIfSelectedRule), answers).isEmpty())
  }

  @Test
  fun `REQUIRED_IF_SELECTED passes when the trigger option is NOT selected, regardless of the target field`() {
    val answers = FormAnswers(multiValues = mapOf("maternal_death_cause" to listOf("hemorrhage")))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(requiredIfSelectedRule), answers).isEmpty())
  }

  @Test
  fun `REQUIRED_IF_SELECTED does not apply when the trigger field's answer is not an array`() {
    val answers = FormAnswers(singleValues = mapOf("maternal_death_cause" to "other"))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(requiredIfSelectedRule), answers).isEmpty())
  }

  @Test
  fun `REQUIRED_IF_SELECTED is exact string membership - a similar but different option code does not trigger it`() {
    val answers = FormAnswers(multiValues = mapOf("maternal_death_cause" to listOf("other_reason")))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(requiredIfSelectedRule), answers).isEmpty())
  }

  // --- EXCLUSIVE_OPTION (DELIVERY_VISIT's did_mother_experience_complications, CHILD_REGISTRATION's
  // vaccination_taken_at_birth) — confirmed 2026-08-19 against both live schemas' validationJson.
  // `field` names the trigger multiselect; `exclusiveValues` are option codes that must not be
  // selected alongside any other option in that same field.

  private val exclusiveOptionRule = FormCrossFieldRule(
    rule = "EXCLUSIVE_OPTION",
    fields = emptyList(),
    field = "did_mother_experience_complications",
    exclusiveValues = listOf("none"),
  )

  @Test
  fun `EXCLUSIVE_OPTION fails when the exclusive value is selected alongside another option`() {
    val answers = FormAnswers(
      multiValues = mapOf("did_mother_experience_complications" to listOf("none", "eclampsia")),
    )
    assertEquals(
      listOf(exclusiveOptionRule),
      FormCrossFieldValidator.violatedRules(listOf(exclusiveOptionRule), answers),
    )
  }

  @Test
  fun `EXCLUSIVE_OPTION passes when only the exclusive value is selected`() {
    val answers = FormAnswers(multiValues = mapOf("did_mother_experience_complications" to listOf("none")))
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(exclusiveOptionRule), answers).isEmpty())
  }

  @Test
  fun `EXCLUSIVE_OPTION passes when only non-exclusive values are selected`() {
    val answers = FormAnswers(
      multiValues = mapOf("did_mother_experience_complications" to listOf("eclampsia", "abruption")),
    )
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(exclusiveOptionRule), answers).isEmpty())
  }

  @Test
  fun `EXCLUSIVE_OPTION passes when the field is unanswered`() {
    assertTrue(FormCrossFieldValidator.violatedRules(listOf(exclusiveOptionRule), FormAnswers()).isEmpty())
  }

  @Test
  fun `EXCLUSIVE_OPTION with more than one exclusive value still fails on any mix with a non-exclusive one`() {
    val twoExclusiveValuesRule = exclusiveOptionRule.copy(exclusiveValues = listOf("none", "dont_know"))
    val answers = FormAnswers(
      multiValues = mapOf("did_mother_experience_complications" to listOf("dont_know", "eclampsia")),
    )
    assertEquals(
      listOf(twoExclusiveValuesRule),
      FormCrossFieldValidator.violatedRules(listOf(twoExclusiveValuesRule), answers),
    )
  }
}
