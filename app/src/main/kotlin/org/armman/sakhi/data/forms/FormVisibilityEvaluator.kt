package org.armman.sakhi.data.forms

import android.util.Log

/** Shared with the other temporary "SakhiSync" diagnostics added this session. */
private const val TAG = "SakhiSync"

/**
 * Evaluates a field's [FormFieldSchema.visibleWhen] condition against the current [FormAnswers].
 * Pure/stateless so the renderer can call it on every recomposition without side effects.
 *
 * Mirrors the service's own `isVisible` (`visit-form-service/src/forms/form-validation.ts`) so a
 * field the app hides is exactly the field the backend excludes from its required-field check.
 * Both sides support the same five operators — the backend's Zod enum is
 * `['eq', 'gte', 'lt', 'isSet', 'contains']` (`contains` added 2026-08-06 for the Td-dose date
 * fields, see [TdDoseQuestionCodes]). Until this class caught up, `gte`/`lt`/`isSet` fell through
 * to the unknown-operator branch and rendered unconditionally: a `gte` rule the backend accepted
 * and enforced simply did nothing on the device.
 */
object FormVisibilityEvaluator {

  /**
   * Whether [field] should currently be shown/collected.
   *
   * Three deliberate fail-safe choices, all favoring *not silently losing data* over a tidy UI:
   * - If the governing field hasn't been answered yet, the dependent field stays hidden — there is
   *   nothing to condition on, and guessing "visible" would flash fields in and out as she types.
   * - If a numeric comparison can't be made (either side isn't a number), the field is shown.
   * - If [FormVisibleWhen.operator] is one we don't know, the field is shown. An unrecognized rule
   *   failing open means a Sakhi sees one extra field, not that a field she needed silently
   *   vanished — and the backend applies the real rule regardless.
   *
   * [OPERATOR_CONTAINS] is a genuinely NEW operator (added 2026-08-06, per the Td-dose
   * backend-request doc's "Ask #2" — not a spelling the backend already enforced that the app
   * simply hadn't caught up to, unlike `gte`/`lt` above). It's the one operator that compares
   * against a MULTI-value answer ([FormAnswers.multiValues]) rather than [FormAnswers.valueOf]'s
   * single value — e.g. `has_the_women_received_td_dose` `contains` `"td_1_date"` gates the
   * `td_1_date` date field's visibility on whether that checkbox is checked.
   */
  fun isVisible(field: FormFieldSchema, answers: FormAnswers): Boolean {
    val condition = field.visibleWhen ?: return true
    val actual = answers.valueOf(condition.field)

    // Temporary diagnostic for the "sonography No doesn't hide LMP Date/gestational age/EDD"
    // report — only logs for the specific field the user says is misbehaving, to avoid flooding
    // logcat with a line per field per recomposition. Prints exactly what this function is
    // comparing so a questionCode/casing/value mismatch is visible instead of guessed at.
    if (condition.field.contains("sonography", ignoreCase = true)) {
      Log.d(
        TAG,
        "FormVisibilityEvaluator.isVisible(dependentField='${field.questionCode}'): " +
          "condition.field='${condition.field}' condition.operator='${condition.operator}' " +
          "condition.value='${condition.value}' actual='$actual' " +
          "answers.singleValues.keys=${answers.singleValues.keys}",
      )
    }

    // Temporary diagnostic for the "Gravida alert / Last-Pregnancy block doesn't appear when 2 is
    // entered directly, only after 1 is entered then changed to 2" report. Logs every dependent
    // field gated on a `gravida_total_number_of_pregnancies` condition, so a run that reproduces
    // the symptom shows exactly what this function compared on the keystroke that typed "2" —
    // whether `actual` was already "2" at that point (app-logic bug) or still blank/"1" (the
    // ViewModel/TextField hadn't propagated the keystroke yet — a UI-layer timing issue instead).
    if (condition.field.contains("gravida", ignoreCase = true)) {
      Log.d(
        TAG,
        "FormVisibilityEvaluator.isVisible(dependentField='${field.questionCode}'): " +
          "condition.field='${condition.field}' condition.operator='${condition.operator}' " +
          "condition.value='${condition.value}' actual='$actual' " +
          "answers.singleValues.keys=${answers.singleValues.keys}",
      )
    }

    // `isSet`/`contains` don't compare against [actual] (the single-value slot) at all, so both
    // must be handled before the unanswered-means-hidden rule below.
    if (condition.operator == OPERATOR_IS_SET) return !actual.isNullOrBlank()
    if (condition.operator == OPERATOR_CONTAINS) {
      return condition.value != null && condition.value in answers.multiValueOf(condition.field)
    }
    if (actual.isNullOrBlank()) return false
    return when (condition.operator) {
      OPERATOR_EQ -> actual == condition.value
      OPERATOR_GTE -> compareNumbers(actual, condition.value) { left, right -> left >= right }
      OPERATOR_LT -> compareNumbers(actual, condition.value) { left, right -> left < right }
      else -> true
    }
  }

  /**
   * [compare] applied to [actual] and [expected] as numbers, or true when either isn't numeric.
   *
   * The backend uses JS `Number(...)`, which yields `NaN` for non-numeric text and makes every
   * comparison false — i.e. it hides the field. We fail open instead: a schema/answer type
   * mismatch is a bug to fix, and hiding a required question because of one costs the Sakhi a
   * re-visit, whereas showing a spare question costs her a moment.
   */
  private inline fun compareNumbers(
    actual: String,
    expected: String?,
    compare: (Double, Double) -> Boolean,
  ): Boolean {
    val left = actual.toDoubleOrNull() ?: return true
    val right = expected?.toDoubleOrNull() ?: return true
    return compare(left, right)
  }

  private const val OPERATOR_EQ = "eq"
  private const val OPERATOR_GTE = "gte"
  private const val OPERATOR_LT = "lt"
  private const val OPERATOR_IS_SET = "isSet"
  private const val OPERATOR_CONTAINS = "contains"
}
