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

  // ---- PP1 row 24: contraceptive_side_effects / "none" -----------------------------------------

  @Test
  fun `PP1 - checking a side effect disables None`() {
    val selected = listOf(contraceptiveSideEffectHeadache)

    assertTrue(FormMultiSelectExclusivity.isDisabled(pp1ContraceptiveSideEffects, noneSideEffect, selected))
    assertFalse(
      FormMultiSelectExclusivity.isDisabled(pp1ContraceptiveSideEffects, contraceptiveSideEffectNausea, selected),
    )
  }

  @Test
  fun `PP1 - checking None disables every other side effect`() {
    val selected = listOf(noneSideEffect)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(pp1ContraceptiveSideEffects, contraceptiveSideEffectHeadache, selected),
    )
    assertTrue(
      FormMultiSelectExclusivity.isDisabled(pp1ContraceptiveSideEffects, contraceptiveSideEffectNausea, selected),
    )
  }

  @Test
  fun `PP1 - an already-checked side effect is never disabled`() {
    val invalidCombo = listOf(noneSideEffect, contraceptiveSideEffectHeadache)

    assertFalse(FormMultiSelectExclusivity.isDisabled(pp1ContraceptiveSideEffects, noneSideEffect, invalidCombo))
    assertFalse(
      FormMultiSelectExclusivity.isDisabled(pp1ContraceptiveSideEffects, contraceptiveSideEffectHeadache, invalidCombo),
    )
  }

  // ---- NN1/NN2 row 7: danger_signs / "no_abnormal_signs_symptoms" ------------------------------

  @Test
  fun `NN - checking a danger sign disables No abnormal signs`() {
    val selected = listOf(dangerSignLethargic)

    assertTrue(FormMultiSelectExclusivity.isDisabled(nnDangerSigns, noAbnormalSignsSymptoms, selected))
    assertFalse(FormMultiSelectExclusivity.isDisabled(nnDangerSigns, dangerSignCough, selected))
  }

  @Test
  fun `NN - checking No abnormal signs disables every danger sign`() {
    val selected = listOf(noAbnormalSignsSymptoms)

    assertTrue(FormMultiSelectExclusivity.isDisabled(nnDangerSigns, dangerSignLethargic, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(nnDangerSigns, dangerSignCough, selected))
  }

  @Test
  fun `NN - an already-checked danger sign is never disabled`() {
    val invalidCombo = listOf(noAbnormalSignsSymptoms, dangerSignLethargic)

    assertFalse(FormMultiSelectExclusivity.isDisabled(nnDangerSigns, noAbnormalSignsSymptoms, invalidCombo))
    assertFalse(FormMultiSelectExclusivity.isDisabled(nnDangerSigns, dangerSignLethargic, invalidCombo))
  }

  // ---- ANC_VISIT Q29: urine_test / "normal" ---------------------------------------------------

  @Test
  fun `Urine Test - checking a non-normal result disables Normal`() {
    assertTrue(FormMultiSelectExclusivity.isDisabled(urineTest, urineNormal, listOf(urineInfection)))
    assertFalse(FormMultiSelectExclusivity.isDisabled(urineTest, urineSugar, listOf(urineInfection)))
  }

  @Test
  fun `Urine Test - checking Normal disables every other result`() {
    val selected = listOf(urineNormal)

    assertTrue(FormMultiSelectExclusivity.isDisabled(urineTest, urineInfection, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(urineTest, urineSugar, selected))
    assertTrue(FormMultiSelectExclusivity.isDisabled(urineTest, urineProtein, selected))
  }

  // ---- ANC_VISIT Q33: vaccination_status / "none" (distinct from Q44's registration-form code) --

  @Test
  fun `Vaccination status - checking None disables every dose, and does not share Q44's map entry`() {
    val selected = listOf(ancVaccinationNone)

    assertTrue(FormMultiSelectExclusivity.isDisabled(ancVaccinationStatus, "td1_date", selected))
    // Confirms this is its own entry, not accidentally aliasing has_the_women_received_td_dose's.
    assertFalse(FormMultiSelectExclusivity.isDisabled(q44, ancVaccinationNone, selected))
  }

  // ---- POSTPARTUM_VISIT row 4/10: form-unscoped, question_codes unique to this form -------------

  @Test
  fun `PP danger signs - checking No abnormal signs disables every danger sign`() {
    val selected = listOf(ppNoAbnormalSigns)
    assertTrue(FormMultiSelectExclusivity.isDisabled(ppDangerSigns, "lower_abdominal_pain", selected))
  }

  @Test
  fun `PP episiotomy wound site - checking None of the above disables every symptom`() {
    val selected = listOf(ppNoneOfTheAbove)
    assertTrue(FormMultiSelectExclusivity.isDisabled(ppEpisiotomyWoundSite, "severe_pain", selected))
  }

  // ---- dehydration/swelling: form-scoped, now applied to BOTH ANC_VISIT and POSTPARTUM_VISIT ---
  // ---- (POSTPARTUM_VISIT's validationJson declares it; ANC_VISIT's is a UI-only product call, --
  // ---- 2026-08-21 — see FORM_SCOPED_EXCLUSIVE_VALUE_CODES's doc) --------------------------------

  @Test
  fun `dehydration - No is exclusive on POSTPARTUM_VISIT and ANC_VISIT, but not with no formCode`() {
    val selected = listOf(ppDehydrationNo)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(dehydration, "loss_of_skin_turgor", selected, formCode = "POSTPARTUM_VISIT"),
    )
    assertTrue(
      FormMultiSelectExclusivity.isDisabled(dehydration, "loss_of_skin_turgor", selected, formCode = "ANC_VISIT"),
    )
    // No formCode at all (every non-Visit-Form caller, and INFANT_VISIT/NEONATAL_VISIT, which
    // reuse neither field) sees no rule.
    assertFalse(FormMultiSelectExclusivity.isDisabled(dehydration, "loss_of_skin_turgor", selected))
    assertFalse(
      FormMultiSelectExclusivity.isDisabled(dehydration, "loss_of_skin_turgor", selected, formCode = "INFANT_VISIT"),
    )
  }

  @Test
  fun `swelling - No swelling is exclusive on POSTPARTUM_VISIT and ANC_VISIT, but not with no formCode`() {
    val selected = listOf(ppNoSwelling)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(swelling, "swollen_face", selected, formCode = "POSTPARTUM_VISIT"),
    )
    assertTrue(
      FormMultiSelectExclusivity.isDisabled(swelling, "swollen_face", selected, formCode = "ANC_VISIT"),
    )
    assertFalse(FormMultiSelectExclusivity.isDisabled(swelling, "swollen_face", selected))
    assertFalse(
      FormMultiSelectExclusivity.isDisabled(swelling, "swollen_face", selected, formCode = "INFANT_VISIT"),
    )
  }

  @Test
  fun `PP dehydration and swelling - an already-checked option is never disabled regardless of formCode`() {
    val invalidCombo = listOf(ppDehydrationNo, "loss_of_skin_turgor")

    assertFalse(
      FormMultiSelectExclusivity.isDisabled(dehydration, ppDehydrationNo, invalidCombo, formCode = "POSTPARTUM_VISIT"),
    )
    assertFalse(
      FormMultiSelectExclusivity.isDisabled(
        dehydration,
        "loss_of_skin_turgor",
        invalidCombo,
        formCode = "POSTPARTUM_VISIT",
      ),
    )
  }

  // ---- Bug fix (2026-09-02): 4 MOTHER_REGISTRATION multiselects with no exclusivity rule -----

  private val longTermMedicines = MotherRegistrationQuestionCodes.LONG_TERM_MEDICINES
  private val notTakingAnyLongTermMedication =
    MotherRegistrationQuestionCodes.ValueCode.NOT_TAKING_ANY_LONG_TERM_MEDICATION
  private val takingHivDrugs = "taking_hiv_drugs"

  @Test
  fun `Long-term medicines - checking a medicine disables Not taking any`() {
    val selected = listOf(takingHivDrugs)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(longTermMedicines, notTakingAnyLongTermMedication, selected),
    )
  }

  @Test
  fun `Long-term medicines - checking Not taking any disables every medicine`() {
    val selected = listOf(notTakingAnyLongTermMedication)

    assertTrue(FormMultiSelectExclusivity.isDisabled(longTermMedicines, takingHivDrugs, selected))
  }

  @Test
  fun `Long-term medicines - an already-checked option is never disabled`() {
    val invalidCombo = listOf(notTakingAnyLongTermMedication, takingHivDrugs)

    assertFalse(
      FormMultiSelectExclusivity.isDisabled(longTermMedicines, notTakingAnyLongTermMedication, invalidCombo),
    )
    assertFalse(FormMultiSelectExclusivity.isDisabled(longTermMedicines, takingHivDrugs, invalidCombo))
  }

  private val substanceUse = MotherRegistrationQuestionCodes.SUBSTANCE_USE
  private val substanceNone = MotherRegistrationQuestionCodes.ValueCode.SUBSTANCE_NONE
  private val smokingTobacco = "smoking_tobacco_bidi_cigaretde"

  @Test
  fun `Substance use - reported bug - checking No disables Smoking tobacco and vice versa`() {
    assertTrue(FormMultiSelectExclusivity.isDisabled(substanceUse, smokingTobacco, listOf(substanceNone)))
    assertTrue(FormMultiSelectExclusivity.isDisabled(substanceUse, substanceNone, listOf(smokingTobacco)))
  }

  @Test
  fun `Substance use - an already-checked option is never disabled`() {
    val invalidCombo = listOf(substanceNone, smokingTobacco)

    assertFalse(FormMultiSelectExclusivity.isDisabled(substanceUse, substanceNone, invalidCombo))
    assertFalse(FormMultiSelectExclusivity.isDisabled(substanceUse, smokingTobacco, invalidCombo))
  }

  private val previousDeliveryComplications = MotherRegistrationQuestionCodes.PREVIOUS_DELIVERY_COMPLICATIONS
  private val noComplications = MotherRegistrationQuestionCodes.ValueCode.NO_COMPLICATIONS
  private val complicationDuringDelivery = MotherRegistrationQuestionCodes.ValueCode.COMPLICATION_DURING_DELIVERY

  @Test
  fun `Previous delivery complications - checking No complications disables every complication`() {
    val selected = listOf(noComplications)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(previousDeliveryComplications, complicationDuringDelivery, selected),
    )
  }

  @Test
  fun `Previous delivery complications - checking a complication disables No complications`() {
    val selected = listOf(complicationDuringDelivery)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(previousDeliveryComplications, noComplications, selected),
    )
  }

  @Test
  fun `Previous delivery complications - an already-checked option is never disabled`() {
    val invalidCombo = listOf(noComplications, complicationDuringDelivery)

    assertFalse(
      FormMultiSelectExclusivity.isDisabled(previousDeliveryComplications, noComplications, invalidCombo),
    )
    assertFalse(
      FormMultiSelectExclusivity.isDisabled(previousDeliveryComplications, complicationDuringDelivery, invalidCombo),
    )
  }

  // Family planning method — question_code confirmed 2026-09-02 against a live
  // GET /forms/ANC_VISIT/active-version (v9) response.
  private val familyPlanningMethod = "contraceptive_family_planning_method_planned_after_delivery"
  private val notUsingAnyContraceptiveMethod = "not_using_any_contraceptive_method"
  private val condoms = "condoms"

  @Test
  fun `Family planning method - checking a method disables Not using any`() {
    val selected = listOf(condoms)

    assertTrue(
      FormMultiSelectExclusivity.isDisabled(familyPlanningMethod, notUsingAnyContraceptiveMethod, selected),
    )
  }

  @Test
  fun `Family planning method - checking Not using any disables every method`() {
    val selected = listOf(notUsingAnyContraceptiveMethod)

    assertTrue(FormMultiSelectExclusivity.isDisabled(familyPlanningMethod, condoms, selected))
  }

  private companion object {
    const val pp1ContraceptiveSideEffects = "contraceptive_side_effects"
    const val noneSideEffect = "none"
    const val contraceptiveSideEffectHeadache = "headache"
    const val contraceptiveSideEffectNausea = "nausea"
    const val nnDangerSigns = "danger_signs"
    const val noAbnormalSignsSymptoms = "no_abnormal_signs_symptoms"
    const val dangerSignLethargic = "lethargic"
    const val dangerSignCough = "cough"
    const val urineTest = "urine_test"
    const val urineNormal = "normal"
    const val urineInfection = "infection"
    const val urineSugar = "sugar"
    const val urineProtein = "protein"
    const val ancVaccinationStatus = "vaccination_status"
    const val ancVaccinationNone = "none"
    const val ppDangerSigns = "danger_signs_since_delivery_or_last_visit"
    const val ppNoAbnormalSigns = "no_abnormal_signs_and_symptoms"
    const val ppEpisiotomyWoundSite = "episiotomy_or_csection_wound_issues"
    const val ppNoneOfTheAbove = "none_of_the_above"
    const val dehydration = "dehydration"
    const val ppDehydrationNo = "no"
    const val swelling = "swelling"
    const val ppNoSwelling = "no_swelling"
  }
}
