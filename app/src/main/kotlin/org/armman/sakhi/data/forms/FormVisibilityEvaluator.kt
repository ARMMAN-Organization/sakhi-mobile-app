package org.armman.sakhi.data.forms

/**
 * Evaluates a field's [FormFieldSchema.visibleWhen] condition against the current [FormAnswers].
 * Pure/stateless so the renderer can call it on every recomposition without side effects.
 */
object FormVisibilityEvaluator {

  /**
   * Whether [field] should currently be shown/collected.
   *
   * Two deliberate fail-safe choices, both favoring *not silently losing data* over a tidy UI:
   * - If the governing field hasn't been answered yet, the dependent field stays hidden (nothing
   *   to condition on yet) rather than guessing.
   * - If [FormVisibleWhen.operator] is anything other than the one operator we've actually seen
   *   (`"eq"`), the field is shown rather than hidden — an unrecognized rule failing open means a
   *   Sakhi might see one extra field, not that a field she needed to fill in silently vanished.
   */
  fun isVisible(field: FormFieldSchema, answers: FormAnswers): Boolean {
    val condition = field.visibleWhen ?: return true
    val actual = answers.valueOf(condition.field) ?: return false
    return when (condition.operator) {
      "eq" -> actual == condition.value
      else -> true
    }
  }
}
