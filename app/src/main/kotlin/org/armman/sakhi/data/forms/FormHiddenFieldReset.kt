package org.armman.sakhi.data.forms

/**
 * A field's answer is reset the moment it becomes hidden ([FormFieldSchema.visibleWhen] flips
 * true → false), instead of lingering in [FormAnswers] where a stale value can silently corrupt a
 * cross-field check or ride along in the submission payload for a question the Sakhi can no
 * longer see or edit.
 *
 * Reported bug: dropping Gravida from 2 to 1 hid Para/Living children/Abortions/Still births/Dead
 * children/the whole Last-pregnancy block, but a previously-typed Still Births stayed in memory
 * and later collided with [FormObstetricRuleset]'s Gravida-total check with no field left on
 * screen the Sakhi could fix — and the error naming it fell back to its raw `question_code`
 * (see [labelResolver][org.armman.sakhi.ui.forms.labelResolver]'s doc for that half of the bug).
 *
 * Deliberately one-directional, per bharath's call (2026-08-06): a field resets every time it
 * hides, but nothing restores or remembers a value once it's reset, even if the field becomes
 * visible again later (e.g. Gravida going back up to 2) — the Sakhi re-enters it. This mirrors the
 * spec's own "goto 58" being a hard skip, not a pause.
 *
 * Driven entirely by [FormVisibilityEvaluator]/schema `visibleWhen` rather than a hardcoded
 * "Gravida <= 1" check — a threshold the backend changes takes effect here without an app release,
 * the same reasoning [FormNumericInputRule] and [FormCrossFieldValidator] already follow.
 *
 * [RESET_TO_ZERO_QUESTION_CODES] covers the obstetric-count fields the Gravida-total formula treats
 * as zero when there is no prior-pregnancy outcome to report — "0" there is a real, meaningful
 * answer (and exactly what [DynamicFormSubmissionMapper] already substitutes for an unanswered
 * one), not a placeholder. Every other hidden field is cleared to blank instead, since it has no
 * such zero-value tied to Gravida's arithmetic.
 */
object FormHiddenFieldReset {

  private val RESET_TO_ZERO_QUESTION_CODES: Set<String> = setOf(
    FormObstetricRuleset.PARA,
    FormObstetricRuleset.LIVING_CHILDREN,
    FormObstetricRuleset.ABORTIONS,
    FormObstetricRuleset.STILL_BIRTHS,
    FormObstetricRuleset.DEAD_CHILDREN,
  )

  /**
   * [updatedAnswers] with every field that was visible under [previousAnswers] but is hidden under
   * [updatedAnswers] reset — to `"0"` for [RESET_TO_ZERO_QUESTION_CODES], cleared (both single- and
   * multi-value) otherwise. A field that was already hidden, or stays visible either way, is
   * untouched.
   */
  fun apply(
    fields: List<FormFieldSchema>,
    previousAnswers: FormAnswers,
    updatedAnswers: FormAnswers,
  ): FormAnswers {
    var result = updatedAnswers
    newlyHiddenFields(fields, previousAnswers, updatedAnswers).forEach { field ->
      result = if (field.questionCode in RESET_TO_ZERO_QUESTION_CODES) {
        result.withSingleValue(field.questionCode, "0")
      } else {
        result.withSingleValue(field.questionCode, null).withMultiValue(field.questionCode, emptyList())
      }
    }
    return result
  }

  /** The `question_code`s [apply] would reset — exposed separately so callers can also drop any
   * stale [error][org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel] for a field that
   * just went out of view, not just the one the Sakhi directly answered. */
  fun newlyHiddenQuestionCodes(
    fields: List<FormFieldSchema>,
    previousAnswers: FormAnswers,
    updatedAnswers: FormAnswers,
  ): Set<String> = newlyHiddenFields(fields, previousAnswers, updatedAnswers).map { it.questionCode }.toSet()

  private fun newlyHiddenFields(
    fields: List<FormFieldSchema>,
    previousAnswers: FormAnswers,
    updatedAnswers: FormAnswers,
  ): List<FormFieldSchema> = fields.filter { field ->
    FormVisibilityEvaluator.isVisible(field, previousAnswers) &&
      !FormVisibilityEvaluator.isVisible(field, updatedAnswers)
  }
}
