package org.armman.sakhi.data.forms

/**
 * Evaluates a field's [FormFieldSchema.visibleWhen] condition against the current [FormAnswers].
 * Pure/stateless so the renderer can call it on every recomposition without side effects.
 *
 * Mirrors the service's own `isVisible` (`visit-form-service/src/forms/form-validation.ts`) so a
 * field the app hides is exactly the field the backend excludes from its required-field check.
 * Both sides support the same four operators — the backend's Zod enum is
 * `['eq', 'gte', 'lt', 'isSet']`. Until this class caught up, `gte`/`lt`/`isSet` fell through to
 * the unknown-operator branch and rendered unconditionally: a `gte` rule the backend accepted and
 * enforced simply did nothing on the device.
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
   */
  fun isVisible(field: FormFieldSchema, answers: FormAnswers): Boolean {
    val condition = field.visibleWhen ?: return true
    val actual = answers.valueOf(condition.field)
    // `isSet` is the one operator that asks about presence, so it must be answered before the
    // unanswered-means-hidden rule below (which would give the same result here, but by accident).
    if (condition.operator == OPERATOR_IS_SET) return !actual.isNullOrBlank()
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
}
