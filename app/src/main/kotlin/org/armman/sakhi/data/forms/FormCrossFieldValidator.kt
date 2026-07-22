package org.armman.sakhi.data.forms

private const val RULE_LTE = "LTE"
private const val RULE_SUM_EQUALS = "SUM_EQUALS"

/**
 * Evaluates the `validationJson` cross-field rules the backend ships alongside a form version
 * (e.g. "Para must not exceed Gravida"). Mirrors the backend's own `crossFieldRuleSchema`
 * discriminated union exactly (`LTE`, `SUM_EQUALS`) — this is the client-side twin of the same
 * rules `EnrollmentApiMapper.validateMotherCrossFieldRules` already enforces for the
 * `/beneficiaries` submission, but driven by data instead of hardcoded, so a rule the backend
 * changes takes effect without an app release.
 *
 * A rule is silently skipped (not violated, not passed — simply not evaluable yet) if any field
 * it references hasn't been answered. That's deliberate: an incomplete draft shouldn't show a
 * cross-field error before the Sakhi has even reached the second field; required-ness is a
 * separate, already-enforced concern.
 */
object FormCrossFieldValidator {

  /** Returns the rules that are currently violated, given every referenced field has an answer.
   * Empty means either "all fine" or "not enough answered yet to tell" — callers only need to
   * know whether to block submission, and an unevaluated rule never blocks it. */
  fun violatedRules(rules: List<FormCrossFieldRule>, answers: FormAnswers): List<FormCrossFieldRule> =
    rules.filter { isViolated(it, answers) }

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

      // Unknown future rule types: not evaluable client-side yet, so not treated as a violation —
      // same fail-open rationale as an unrecognized visibleWhen operator.
      else -> false
    }
  }
}
