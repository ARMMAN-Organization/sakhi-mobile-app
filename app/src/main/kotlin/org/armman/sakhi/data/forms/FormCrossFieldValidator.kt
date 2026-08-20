package org.armman.sakhi.data.forms

private const val RULE_LTE = "LTE"
private const val RULE_SUM_EQUALS = "SUM_EQUALS"
/** CR-037: "either date_of_birth or age_of_the_beneficiary" (spec row 20) is the first use of
 * this rule — the backend's `crossFieldRuleSchema` twin. Violated when EVERY listed field is
 * empty; any one answered satisfies it. Deliberately the opposite of LTE/SUM_EQUALS' "skip until
 * evaluable" stance: for those, an unanswered operand means "can't tell yet, don't block." For
 * this rule, "nothing answered yet" IS the failure the required-check exists to catch. */
private const val RULE_ANY_OF_REQUIRED = "ANY_OF_REQUIRED"
/** ANC_CLOSURE_VISIT/CHILD_CLOSURE_VISIT's "other, please specify" death-cause detail fields
 * (e.g. `maternal_death_cause` -> `other` -> `maternal_death_cause_other_specify`). The trigger
 * field is a multiselect; a non-array/absent trigger answer means the rule doesn't apply at all
 * (nothing to require yet) rather than being a violation — mirrors the backend's own server-side
 * pseudocode exactly, string membership only, no normalization. */
private const val RULE_REQUIRED_IF_SELECTED = "REQUIRED_IF_SELECTED"
/**
 * Confirmed 2026-08-19 against the live `DELIVERY_VISIT` (`did_mother_experience_complications`)
 * and `CHILD_REGISTRATION` (`vaccination_taken_at_birth`) `validationJson` payloads — both declare
 * this rule shape (`field` + `exclusiveValues`, e.g. `["none"]`). Previously unhandled: this rule
 * type fell into the `else` branch below and was silently never enforced, so a Sakhi could select
 * "None" alongside another option in either multiselect and Submit would not block it. Searched
 * the rest of the codebase before adding this — no form currently relies on it being unenforced.
 */
private const val RULE_EXCLUSIVE_OPTION = "EXCLUSIVE_OPTION"

/**
 * Evaluates the `validationJson` cross-field rules the backend ships alongside a form version
 * (e.g. "Para must not exceed Gravida"). Mirrors the backend's own `crossFieldRuleSchema`
 * discriminated union exactly (`LTE`, `SUM_EQUALS`, per CR-037 `ANY_OF_REQUIRED`,
 * `REQUIRED_IF_SELECTED`, and now `EXCLUSIVE_OPTION`) — this is
 * the client-side twin of the same rules `EnrollmentApiMapper.validateMotherCrossFieldRules`
 * already enforces for the `/beneficiaries` submission, but driven by data instead of hardcoded,
 * so a rule the backend changes takes effect without an app release.
 *
 * A consistency rule (`LTE`/`SUM_EQUALS`) is silently skipped (not violated, not passed — simply
 * not evaluable yet) if any field it references hasn't been answered. That's deliberate: an
 * incomplete draft shouldn't show a cross-field error before the Sakhi has even reached the
 * second field. `ANY_OF_REQUIRED` is the exception — see [RULE_ANY_OF_REQUIRED]'s doc — since for
 * that rule "unanswered" across every listed field is exactly the condition it exists to flag.
 * `EXCLUSIVE_OPTION` is evaluated the same "skip until evaluable" way as `LTE`/`SUM_EQUALS`: an
 * empty selection is not a violation, only a selection that mixes an exclusive value with a
 * non-exclusive one is.
 *
 * ONE further exception, [isKnownBuggyGravidaSumRule]: the live schema's `validationJson` as of
 * 2026-08-06 declares a `SUM_EQUALS` rule that makes Submit mathematically impossible — see that
 * function's doc. It is skipped by exact signature, not by disabling `SUM_EQUALS` generally, so a
 * future correctly-authored `SUM_EQUALS` rule (on this form or another) still gets enforced.
 *
 * ONE addition in the other direction, [SUPPLEMENTAL_RULES]: both `MOTHER_REGISTRATION` and
 * `CHILD_REGISTRATION` ship an EMPTY `validationJson` (confirmed against the live active-version
 * responses) even though both declare spec rows 33/34 — "family members in your household
 * (including children under 5)" and "children under 5 years of age" — where the second can never
 * exceed the first by definition. Nothing server-side enforces that today, so it is injected here
 * unconditionally rather than waiting on a schema fix, same rationale as
 * [org.armman.sakhi.data.forms.FormNumericInputRule]'s digit-cap stopgap for the same field pair.
 */
object FormCrossFieldValidator {

  /** CR: children under 5 (already included in the household total) must never exceed it. See the
   * class doc's [SUPPLEMENTAL_RULES] section — this stands in for a schema rule neither live form
   * (`MOTHER_REGISTRATION` nor `CHILD_REGISTRATION`) currently declares. Evaluates to a no-op via
   * the same "skip until both sides answered" rule as any other `LTE` when a form doesn't carry
   * these two question codes at all. Delete once the backend's `validationJson` carries it. */
  private val SUPPLEMENTAL_RULES = listOf(
    FormCrossFieldRule(
      rule = RULE_LTE,
      fields = listOf(CHILDREN_UNDER_FIVE_QUESTION_CODE, FAMILY_MEMBERS_QUESTION_CODE),
    ),
  )

  /** Returns the rules that are currently violated, given every referenced field has an answer.
   * Empty means either "all fine" or "not enough answered yet to tell" — callers only need to
   * know whether to block submission, and an unevaluated rule never blocks it. */
  fun violatedRules(rules: List<FormCrossFieldRule>, answers: FormAnswers): List<FormCrossFieldRule> =
    (rules + SUPPLEMENTAL_RULES).filter { !isKnownBuggyGravidaSumRule(it) && isViolated(it, answers) }

  /**
   * True for the ONE specific `SUM_EQUALS` rule shape the live schema started declaring
   * 2026-08-06: `equals = gravida_total_number_of_pregnancies`, `fields = [living_children,
   * still_births, abortions_...]`, checked as `sum(fields) == gravida` with NO adjustment for the
   * current pregnancy.
   *
   * That contradicts three things that all agree with each other:
   *  - This form's own spec, row 45: "Gravida = Live birth + Abortion + Still birth + 1".
   *  - [FormObstetricRuleset.CURRENT_PREGNANCY], which already enforces the correct
   *    `sum(fields) == gravida - 1` version client-side.
   *  - The REAL `/beneficiaries` submission endpoint, confirmed against real backend 400
   *    responses ([org.armman.sakhi.data.forms.DynamicFormSubmissionMapper
   *    .validateMotherCrossFieldRules] enforces the SAME `- 1` version).
   *
   * Enforcing the schema's un-adjusted version here TOO makes every submission with a prior
   * pregnancy answered unsatisfiable: `sum` cannot equal both `gravida` and `gravida - 1` at once,
   * for any values. This is a backend schema bug — being raised with ARMMAN separately — not a
   * case where the client should trust the schema over its own (server-verified) rule. Matched by
   * exact signature so an unrelated `SUM_EQUALS` rule is never silently dropped.
   */
  private fun isKnownBuggyGravidaSumRule(rule: FormCrossFieldRule): Boolean =
    rule.rule == RULE_SUM_EQUALS &&
      rule.equals == FormObstetricRuleset.GRAVIDA &&
      rule.fields.toSet() == setOf(
        FormObstetricRuleset.LIVING_CHILDREN,
        FormObstetricRuleset.STILL_BIRTHS,
        FormObstetricRuleset.ABORTIONS,
      )

  private fun isViolated(rule: FormCrossFieldRule, answers: FormAnswers): Boolean {
    return when (rule.rule) {
      RULE_LTE -> {
        val fields = rule.fields.takeIf { it.size == 2 } ?: return false
        val left = answers.valueOf(fields[0])?.toDoubleOrNull()
        val right = answers.valueOf(fields[1])?.toDoubleOrNull()
        left != null && right != null && left > right
      }

      RULE_SUM_EQUALS -> {
        val equalsCode = rule.equals ?: return false
        val target = answers.valueOf(equalsCode)?.toDoubleOrNull() ?: return false
        val values = rule.fields.map { answers.valueOf(it)?.toDoubleOrNull() }
        if (values.any { it == null }) return false
        values.filterNotNull().sum() != target
      }

      RULE_ANY_OF_REQUIRED -> rule.fields.all { answers.valueOf(it).isNullOrBlank() }

      RULE_REQUIRED_IF_SELECTED -> {
        val triggerField = rule.field ?: return false
        val optionMap = rule.optionFieldMap ?: return false
        val triggerAnswer = answers.multiValueOf(triggerField)
        optionMap.any { (optionCode, targetField) -> triggerAnswer.contains(optionCode) && answers.valueOf(targetField).isNullOrBlank() }
      }

      RULE_EXCLUSIVE_OPTION -> {
        val triggerField = rule.field ?: return false
        val exclusiveValues = rule.exclusiveValues ?: return false
        val selected = answers.multiValueOf(triggerField)
        val hasExclusive = selected.any { it in exclusiveValues }
        val hasOther = selected.any { it !in exclusiveValues }
        hasExclusive && hasOther
      }

      // Unknown future rule types: not evaluable client-side yet, so not treated as a violation —
      // same fail-open rationale as an unrecognized visibleWhen operator.
      else -> false
    }
  }
}
