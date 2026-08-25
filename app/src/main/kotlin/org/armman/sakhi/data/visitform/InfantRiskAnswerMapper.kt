package org.armman.sakhi.data.visitform

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.armman.sakhi.data.forms.FormAnswers

/**
 * Maps INFANT_VISIT/INC_VISIT/CCV_VISIT/NEONATAL_VISIT [FormAnswers] into the `answers` input the
 * [org.armman.sakhi.data.rules.RuleSetIds.RISK_INFANT]/
 * [org.armman.sakhi.data.rules.GoRulesRiskAdapter.gradeInfantRisk] GoRules pack expects. Same
 * partial-input contract as [AncRiskAnswerMapper] — see that object's doc.
 *
 * Every pack input backend confirmed now has a verified `question_code` (2026-08-24 schema dump)
 * for at least the forms it actually applies to — see [InfantFormFamily] and each field's
 * per-family handling below. Three fields (temperature, respiratory rate, MUAC) genuinely do not
 * exist on NEONATAL_VISIT's schema at all — not a mapping gap, the form itself doesn't collect
 * them (0-28 day neonates use different vitals) — so [formFamily] gates them out for that family
 * rather than a missing constant silently doing the same thing less legibly.
 *
 * `deformity`/`activity_level`/`feeding_concerns` ([InfantVisitFormQuestionCodes.DEFORMITY] etc.)
 * are intentionally NOT included even though this app has verified codes for them on every
 * family — the infant risk pack itself does not read these fields yet, pending the clinical
 * decision on whether/how to wire them in (see the risk-grading findings docs). Adding them here
 * would be inert (the pack ignores unrecognized input keys) but would create a false impression
 * this mapper is "done" for those three fields — left out until the pack actually reads them.
 */
object InfantRiskAnswerMapper {

  /** Which live form is supplying [answers] — determines which pack inputs apply and which
   * question_code spelling to use for the two fields that differ by form (birth weight, danger
   * signs) — see this object's class doc. */
  enum class InfantFormFamily { NEONATAL, INFANT, INC, CCV }

  fun toRuleInput(answers: FormAnswers, formFamily: InfantFormFamily): JsonObject = JsonObject().apply {
    answers.valueOf(InfantVisitFormQuestionCodes.AGE_IN_MONTHS)?.toDoubleOrNull()
      ?.let { addProperty("age_in_months", it) }

    val birthWeightCode = if (formFamily == InfantFormFamily.NEONATAL) {
      InfantVisitFormQuestionCodes.BIRTH_WEIGHT_KG_NEONATAL
    } else {
      InfantVisitFormQuestionCodes.BIRTH_WEIGHT_IN_KG
    }
    answers.valueOf(birthWeightCode)?.toDoubleOrNull()?.let { addProperty("birth_weight_kg", it) }

    gradedField(answers, InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_WASTING)
      ?.let { addProperty("nutritional_status_wasting", it) }
    gradedField(answers, InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_STUNTING)
      ?.let { addProperty("nutritional_status_stunting", it) }
    gradedField(answers, InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_UNDERWEIGHT)
      ?.let { addProperty("nutritional_status_underweight", it) }

    answers.valueOf(InfantVisitFormQuestionCodes.MILESTONES_AS_PER_AGE)?.let {
      addProperty("is_the_child_showing_all_developmental_milestones_as_per_his_her_age", it)
    }
    answers.valueOf(InfantVisitFormQuestionCodes.CURRENT_FEEDING_PRACTICE)?.let {
      addProperty("current_feeding_practice", it)
    }

    // NEONATAL_VISIT's own field, no equivalent on the other three forms.
    if (formFamily == InfantFormFamily.NEONATAL) {
      answers.valueOf(InfantVisitFormQuestionCodes.UMBILICAL_CORD_CARE)?.let {
        addProperty("umbilical_cord_care", it)
      }
    } else {
      // Confirmed absent from NEONATAL_VISIT's schema (2026-08-24) — not collected for 0-28 day
      // neonates, so simply not sent rather than sent as a false zero/null.
      answers.valueOf(InfantVisitFormQuestionCodes.CHILD_TEMPERATURE_F)?.toDoubleOrNull()
        ?.let { addProperty("child_temprature_in_f", it) }
      answers.valueOf(InfantVisitFormQuestionCodes.CHILD_RESPIRATORY_RATE)?.toDoubleOrNull()
        ?.let { addProperty("child_respiratory_rate_2_12_months", it) }
      answers.valueOf(InfantVisitFormQuestionCodes.MUAC_CM)?.toDoubleOrNull()
        ?.let { addProperty("muac_in_cms", it) }
    }

    when (formFamily) {
      InfantFormFamily.INFANT, InfantFormFamily.INC, InfantFormFamily.CCV -> {
        val dangerSigns = answers.multiValueOf(InfantVisitFormQuestionCodes.DANGER_SIGNS)
          .filter { it != InfantVisitFormQuestionCodes.ValueCode.NO_ABNORMAL_SIGNS_SYMPTOMS }
        if (dangerSigns.isNotEmpty() || answers.valueOf(InfantVisitFormQuestionCodes.DANGER_SIGNS) != null) {
          add(
            "is_the_baby_showing_any_danger_signs_since_last_visit",
            JsonArray().apply { dangerSigns.forEach { add(it) } },
          )
        }
      }
      InfantFormFamily.NEONATAL -> {
        val dangerSigns = answers.multiValueOf(InfantVisitFormQuestionCodes.DANGER_SIGNS_NEONATAL)
          .filter { it != InfantVisitFormQuestionCodes.ValueCode.NO_ABNORMAL_SIGNS_SYMPTOMS_NEONATAL }
        if (dangerSigns.isNotEmpty() || answers.valueOf(InfantVisitFormQuestionCodes.DANGER_SIGNS_NEONATAL) != null) {
          add("danger_signs", JsonArray().apply { dangerSigns.forEach { add(it) } })
        }
      }
    }
  }

  private fun gradedField(answers: FormAnswers, questionCode: String): String? =
    answers.valueOf(questionCode)?.takeIf { it.isNotBlank() }
}
