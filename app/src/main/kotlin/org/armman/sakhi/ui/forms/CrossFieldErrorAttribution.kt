package org.armman.sakhi.ui.forms

import org.armman.sakhi.data.forms.FormCrossFieldRule

/** `validationJson` rule discriminators — mirrors `FormCrossFieldValidator`'s own constants. */
private const val RULE_LTE = "LTE"
private const val RULE_SUM_EQUALS = "SUM_EQUALS"

/**
 * Decides which field a violated cross-field rule's message belongs under.
 *
 * Cross-field rules were previously evaluated only inside `isReadyToSubmit()`, so a violation
 * disabled Submit with nothing on screen to explain it — the Sakhi saw a dead button (e.g. "children
 * under 5" entered higher than "family members"). Attributing each rule to one field lets the
 * existing per-field inline error slot carry the message, and the hosting screen additionally shows
 * a banner on the Summary tab so a rule whose fields live on other tabs is still visible at the
 * point where Submit is blocked.
 *
 * Attribution:
 * - **LTE** (`fields[0] <= fields[1]`) attaches to `fields[0]` — the value that is too high, and the
 *   one the Sakhi most likely needs to correct.
 * - **SUM_EQUALS** attaches to the `equals` field — the total that doesn't match its parts.
 */
object CrossFieldErrorAttribution {

  /**
   * Violated [rules] keyed by the `question_code` whose field should show them. Where two rules
   * land on the same field the first wins, so one field never stacks messages; the remaining rule
   * still blocks submission and still appears in the Summary banner.
   */
  fun byQuestionCode(rules: List<FormCrossFieldRule>): Map<String, FormCrossFieldRule> {
    val attributed = LinkedHashMap<String, FormCrossFieldRule>()
    rules.forEach { rule ->
      val code = attributedFieldOf(rule) ?: return@forEach
      attributed.putIfAbsent(code, rule)
    }
    return attributed
  }

  /** The `question_code` [rule]'s message belongs to, or null if the rule is malformed (wrong field
   * count, missing `equals`) — the same fail-open treatment `FormCrossFieldValidator` gives it. */
  fun attributedFieldOf(rule: FormCrossFieldRule): String? = when (rule.rule) {
    RULE_LTE -> rule.fields.takeIf { it.size == 2 }?.first()
    RULE_SUM_EQUALS -> rule.equals
    else -> null
  }
}
