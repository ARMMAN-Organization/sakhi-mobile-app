package org.armman.sakhi.data.visitform

/**
 * Reverse lookup from a risk-pack condition CODE (the keys in
 * [org.armman.sakhi.data.rules.RiskConditionIds.ANC]/`.INFANT`) to the `question_code`(s) the
 * real-time field-highlighting UI should mark when that condition grades MILD or worse.
 *
 * ### Why this exists as its own file, hand-curated, not derived
 * The rule packs (`anc-risk.rulesJson.ts`/`infant-risk.rulesJson.ts`) are backend-owned function
 * nodes this app never inspects (see [org.armman.sakhi.data.rules.RuleEvaluator]'s doc — the app
 * only ever sees `rulesJson` as an opaque blob it feeds to the engine). There is no way to derive
 * "which literal input field produced this clinical finding" from the pack's own output — it
 * returns a `riskConditionId`, not the field(s) that fed it. This map is therefore a hand-built,
 * best-effort correspondence between a clinical condition CODE (whose name plainly describes one
 * physiological concept — e.g. `ANEMIA`) and the one field this app sends the pack under that same
 * concept — not something confirmed against the pack's actual internal logic.
 *
 * ### Deliberately left unmapped — do not guess these
 * `AGE`, `APH`, `BAD_OBSTETRIC_HISTORY`, `PPH`, `STUNTING` (ANC) — these relate to cross-form
 * registration data or fields this app doesn't send the pack at all yet (see
 * [AncRiskAnswerMapper]/[AncRiskRegistrationResolver]'s docs), so there is no wired field to point
 * a highlight at. `JAUNDICE` (ANC) is left unmapped even though its three input fields
 * ([VisitFormQuestionCodes.CHECK_PALM_AND_NAILS]/[CHECK_SCLERA_EYES]/[CHECK_SKIN]) are all wired,
 * because which of the three (or what combination) actually drives the pack's jaundice grading is
 * not confirmed — highlighting the wrong one of three plausible fields would be worse than
 * highlighting none. `MUAC_BMI` maps to MUAC only, not BMI's underlying height/weight fields, for
 * the same one-condition-two-possible-causes reason.
 *
 * A finding whose [org.armman.sakhi.data.rules.RiskConditionFinding.riskConditionId] doesn't
 * resolve to a code here (via [org.armman.sakhi.data.rules.RiskConditionIds]), or whose code isn't
 * a key in these maps, simply doesn't highlight anything — the overall risk category/finding list
 * is unaffected, only the per-field highlight is skipped. Confirm with backend/clinical which
 * single field genuinely drives each unmapped condition before adding it here.
 */
object RiskConditionFieldMap {

  val ANC: Map<String, Set<String>> = mapOf(
    "HYPERTENSION" to setOf(VisitFormQuestionCodes.BLOOD_PRESSURE_SYSTOLIC, VisitFormQuestionCodes.BLOOD_PRESSURE_DIASTOLIC),
    "HYPOTENSION" to setOf(VisitFormQuestionCodes.BLOOD_PRESSURE_SYSTOLIC, VisitFormQuestionCodes.BLOOD_PRESSURE_DIASTOLIC),
    "ANEMIA" to setOf(VisitFormQuestionCodes.HAEMOGLOBIN),
    "HYPERGLYCEMIA" to setOf(VisitFormQuestionCodes.BLOOD_GLUCOSE),
    "HYPOGLYCEMIA" to setOf(VisitFormQuestionCodes.BLOOD_GLUCOSE),
    "HYPERTHERMIA" to setOf(VisitFormQuestionCodes.BODY_TEMPERATURE_F),
    "HYPOTHERMIA" to setOf(VisitFormQuestionCodes.BODY_TEMPERATURE_F),
    "FETAL_HEART_RATE" to setOf(VisitFormQuestionCodes.FETAL_HEART_RATE),
    "GESTATIONAL_WEIGHT_GAIN" to setOf(VisitFormQuestionCodes.GESTATIONAL_WEIGHT_GAIN),
    "DANGER_SIGNS" to setOf(VisitFormQuestionCodes.DANGER_SIGNS),
    "URINE_ANALYSIS" to setOf(VisitFormQuestionCodes.URINE_TEST),
    "MUAC_BMI" to setOf(VisitFormQuestionCodes.MID_UPPER_ARM_CIRCUMFERENCE_CM),
  )

  val INFANT: Map<String, Set<String>> = mapOf(
    "MUAC_MALNUTRITION" to setOf(InfantVisitFormQuestionCodes.MUAC_CM),
    "INFANT_HYPERTHERMIA" to setOf(InfantVisitFormQuestionCodes.CHILD_TEMPERATURE_F),
    "INFANT_HYPOTHERMIA" to setOf(InfantVisitFormQuestionCodes.CHILD_TEMPERATURE_F),
    "RESPIRATORY_DISTRESS" to setOf(InfantVisitFormQuestionCodes.CHILD_RESPIRATORY_RATE),
    "LOW_BIRTH_WEIGHT" to setOf(InfantVisitFormQuestionCodes.BIRTH_WEIGHT_IN_KG, InfantVisitFormQuestionCodes.BIRTH_WEIGHT_KG_NEONATAL),
    "WASTING" to setOf(InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_WASTING),
    "STUNTING_STATUS" to setOf(InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_STUNTING),
    "UNDERWEIGHT" to setOf(InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_UNDERWEIGHT),
    "NEURO_DEVELOPMENTAL_STATUS" to setOf(InfantVisitFormQuestionCodes.MILESTONES_AS_PER_AGE),
    "INFANT_DANGER_SIGNS" to setOf(InfantVisitFormQuestionCodes.DANGER_SIGNS, InfantVisitFormQuestionCodes.DANGER_SIGNS_NEONATAL),
    "FEEDING_ADEQUACY" to setOf(InfantVisitFormQuestionCodes.CURRENT_FEEDING_PRACTICE),
    "CORD_INFECTION" to setOf(InfantVisitFormQuestionCodes.UMBILICAL_CORD_CARE),
  )
}
