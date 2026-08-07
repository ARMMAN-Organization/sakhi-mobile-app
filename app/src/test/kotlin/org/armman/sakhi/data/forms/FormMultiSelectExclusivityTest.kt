package org.armman.sakhi.data.forms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormMultiSelectExclusivityTest {

  private val q43 = MotherRegistrationQuestionCodes.ANC1_HIGH_RISK_CONDITIONS
  private val noKnownCondition = "no_known_medical_condition_identified_during_check_up"
  private val dontKnow = "don_t_know"
  private val hypertension = "hypertension_high_bp"
  private val diabetes = "diabetes_pre_gestational_diagnosed_before_pregnancy"

  @Test
  fun `nothing selected leaves every option enabled`() {
    assertFalse(FormMultiSelectExclusivity.isDisabled(q43, hypertension, emptyList()))
    assertFalse(FormMultiSelectExclusivity.isDisabled(q43, noKnownCondition, emptyList()))
  }

  @Test
  fun `a checked condition disables the exclusive options`() {
    val selected = listOf(hypertension)

    assertTrue(FormMultiSelectExclusivity.isDisabled(q43, noKnownCondition, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(q43, dontKnow, selected))
    // Another regular condition stays selectable.
    assertFalse(FormMultiSelectExclusivity.isDisabled(q43, diabetes, selected))
  }

  @Test
  fun `checking the exclusive option disables every other condition`() {
    val selected = listOf(noKnownCondition)

    assertTrue(FormMultiSelectExclusivity.isDisabled(q43, hypertension, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(q43, diabetes, selected))
    // The other exclusive option ("Don't know") is also disabled once one exclusive option is set.
    assertTrue(FormMultiSelectExclusivity.isDisabled(q43, dontKnow, selected))
  }

  @Test
  fun `an already-checked option is never disabled - the Sakhi must be able to uncheck it`() {
    // Even in an invalid combination that arrived some other way (older draft, restored answer),
    // both currently-checked codes stay clickable so the Sakhi can fix it.
    val invalidCombo = listOf(noKnownCondition, hypertension)

    assertFalse(FormMultiSelectExclusivity.isDisabled(q43, noKnownCondition, invalidCombo))
    assertFalse(FormMultiSelectExclusivity.isDisabled(q43, hypertension, invalidCombo))
    // A third, not-yet-checked option stays locked out either way.
    assertTrue(FormMultiSelectExclusivity.isDisabled(q43, diabetes, invalidCombo))
  }

  @Test
  fun `a field with no declared exclusivity rule never disables anything`() {
    assertFalse(FormMultiSelectExclusivity.isDisabled("some_other_multiselect_field", hypertension, listOf(hypertension)))
    assertFalse(
      FormMultiSelectExclusivity.isDisabled("some_other_multiselect_field", noKnownCondition, listOf(hypertension)),
    )
  }

  // --- Q58 (own value codes — its "no known condition" is spelled differently from Q43's) ------

  private val q58 = MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS
  private val q58NoKnownCondition = "no_known_medical_condition"
  private val thalassemia = "thalassemia"

  @Test
  fun `Q58 - a checked condition disables the exclusive options`() {
    val selected = listOf(thalassemia)

    assertTrue(FormMultiSelectExclusivity.isDisabled(q58, q58NoKnownCondition, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(q58, dontKnow, selected))
    assertFalse(FormMultiSelectExclusivity.isDisabled(q58, hypertension, selected))
  }

  @Test
  fun `Q58 - checking the exclusive option disables every other condition`() {
    val selected = listOf(q58NoKnownCondition)

    assertTrue(FormMultiSelectExclusivity.isDisabled(q58, thalassemia, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(q58, dontKnow, selected))
  }

  @Test
  fun `Q58's no-known-condition code does not leak into Q43's exclusivity set`() {
    // The two fields' "no known condition" codes are spelled differently (see the class doc) -
    // Q58's code must never be treated as exclusive on Q43, and vice versa.
    assertFalse(FormMultiSelectExclusivity.isDisabled(q43, q58NoKnownCondition, listOf(hypertension)))
    assertFalse(FormMultiSelectExclusivity.isDisabled(q58, noKnownCondition, listOf(thalassemia)))
  }

  // --- Q44 Td dose (one-sided: only "None received yet" is exclusive, no "don't know" pair) ----

  private val q44 = TdDoseQuestionCodes.TD_DOSE_QUESTION_CODE
  private val noneReceivedYet = TdDoseQuestionCodes.NONE_RECEIVED_YET_VALUE_CODE
  private val td1 = TdDoseQuestionCodes.TD_1_DATE_QUESTION_CODE
  private val td2 = TdDoseQuestionCodes.TD_2_DATE_QUESTION_CODE

  @Test
  fun `Q44 - checking a dose disables None received yet`() {
    val selected = listOf(td1)

    assertTrue(FormMultiSelectExclusivity.isDisabled(q44, noneReceivedYet, selected))
    assertFalse(FormMultiSelectExclusivity.isDisabled(q44, td2, selected))
  }

  @Test
  fun `Q44 - checking None received yet disables every dose option`() {
    val selected = listOf(noneReceivedYet)

    assertTrue(FormMultiSelectExclusivity.isDisabled(q44, td1, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(q44, td2, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(q44, TdDoseQuestionCodes.TD_BOOSTER_DATE_QUESTION_CODE, selected))
  }

  @Test
  fun `Q44 - an already-checked dose option is never disabled`() {
    val invalidCombo = listOf(noneReceivedYet, td1)

    assertFalse(FormMultiSelectExclusivity.isDisabled(q44, noneReceivedYet, invalidCombo))
    assertFalse(FormMultiSelectExclusivity.isDisabled(q44, td1, invalidCombo))
  }
}
