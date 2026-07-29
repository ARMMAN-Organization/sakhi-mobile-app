package org.armman.sakhi.data.forms

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Date rules for the mother-enrollment form, from the `Registration_PW_D` tab of the form spec
 * ("Revised App Form Final 20.3.26"):
 *
 * - **LMP date** (row 7): "Cannot be future and on or after ANC registration date. Difference
 *   between registration and LMP should be >30 days and <240 days."
 * - **Registration date** (row 13): "Automatically popup todays date" — so never in the future.
 * - **Date of birth** (row 23): "Should accept only those dates whose age will be in range 10-50
 *   years. Error message to be shown if the age is out of the range."
 *
 * These live client-side because the backend cannot express them: [FormFieldSchema] carries only
 * `numericRange` (no date bounds), and [FormCrossFieldValidator]'s `LTE` rule parses its operands
 * with `toDoubleOrNull()`, so a date-vs-date rule in `validationJson` would silently never
 * evaluate. Keyed by `question_code`, so a date field with no entry here is unconstrained and
 * behaves exactly as before.
 *
 * Ages are measured against the **registration date**, not "now", to stay consistent with the
 * derived age field ([FormComputedFieldEvaluator]'s `AGE_FROM_DOB`, which uses
 * `ChronoUnit.YEARS.between(dob, registrationDate)`). Otherwise a form drafted one day and
 * submitted the next could show an age the validator disagrees with.
 */
object FormDateRuleset {

  /** Inclusive age range the beneficiary's DOB must produce (spec row 23). */
  const val MIN_AGE_YEARS = 10L
  const val MAX_AGE_YEARS = 50L

  /**
   * Spec row 7 states the registration-to-LMP difference must be strictly `>30` and `<240` days,
   * so the inclusive day window an LMP answer may sit in is 31..239 days before registration.
   */
  const val LMP_MIN_DAYS_BEFORE_REGISTRATION = 31L
  const val LMP_MAX_DAYS_BEFORE_REGISTRATION = 239L

  /**
   * Which rule a date answer breaks. Mapped to a localized message by the UI layer.
   *
   * Mirrors the legacy static flow's [org.armman.sakhi.ui.enrollment.FieldError] cases
   * (`LMP_FUTURE`/`LMP_TOO_RECENT`/`LMP_TOO_OLD`/`AGE_OUT_OF_RANGE`) so both flows say the same
   * thing to the Sakhi and share the same already-translated strings. Delete that copy along with
   * the static enrollment steps.
   */
  enum class Violation {
    /** DOB implies an age outside [MIN_AGE_YEARS]..[MAX_AGE_YEARS] (includes any future DOB). */
    AGE_OUT_OF_RANGE,

    /** LMP is after the registration date. Reported separately from the window cases because
     * "cannot be in the future" is a clearer thing to read than a day count. */
    LMP_FUTURE,

    /** LMP is [LMP_MIN_DAYS_BEFORE_REGISTRATION] days or fewer before the registration date. */
    LMP_TOO_RECENT,

    /** LMP is more than [LMP_MAX_DAYS_BEFORE_REGISTRATION] days before the registration date. */
    LMP_TOO_OLD,

    /** Registration date is after today. */
    REGISTRATION_DATE_IN_FUTURE,
  }

  /** Selectable range for a field's date picker — the prevention half of the rule. Null means the
   * field has no date rule; a null [Bounds.min]/[Bounds.max] means that end is unbounded. */
  data class Bounds(val min: LocalDate?, val max: LocalDate?)

  /**
   * Picker bounds for [questionCode], or null if it has no date rule.
   *
   * [registrationDate] is the form's registration date — today for a form being filled now (the
   * ViewModel's own `registrationDate`). The LMP window is measured from the *answered*
   * registration date when there is one, falling back to [registrationDate], so the two fields stay
   * consistent with each other.
   */
  fun boundsFor(
    questionCode: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Bounds? {
    val reference = referenceDate(answers, registrationDate)
    return when (questionCode) {
      DOB_QUESTION_CODE -> Bounds(
        // A DOB on this boundary still floors to MAX_AGE_YEARS; one day earlier would floor to
        // MAX_AGE_YEARS + 1 and be out of range.
        min = reference.minusYears(MAX_AGE_YEARS + 1).plusDays(1),
        max = reference.minusYears(MIN_AGE_YEARS),
      )

      LMP_DATE_QUESTION_CODE -> Bounds(
        min = reference.minusDays(LMP_MAX_DAYS_BEFORE_REGISTRATION),
        max = reference.minusDays(LMP_MIN_DAYS_BEFORE_REGISTRATION),
      )

      REGISTRATION_DATE_QUESTION_CODE -> Bounds(min = null, max = registrationDate)

      else -> null
    }
  }

  /**
   * The rule [questionCode]'s current answer breaks, or null if it's fine — the detection half of
   * the rule, for values the picker never gated: drafts saved by an older build, answers restored
   * from the backend, or a date typed in via automation.
   *
   * A blank or unparseable answer is NOT a violation. Blank is the required-field gate's job, and
   * flagging an unparseable value as a *date-range* error would report the wrong problem.
   */
  fun violationFor(
    questionCode: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Violation? {
    val value = parse(answers.valueOf(questionCode)) ?: return null
    val reference = referenceDate(answers, registrationDate)

    return when (questionCode) {
      DOB_QUESTION_CODE -> {
        val age = ChronoUnit.YEARS.between(value, reference)
        Violation.AGE_OUT_OF_RANGE.takeIf { age < MIN_AGE_YEARS || age > MAX_AGE_YEARS }
      }

      LMP_DATE_QUESTION_CODE -> {
        val daysBefore = ChronoUnit.DAYS.between(value, reference)
        when {
          daysBefore < 0 -> Violation.LMP_FUTURE
          daysBefore < LMP_MIN_DAYS_BEFORE_REGISTRATION -> Violation.LMP_TOO_RECENT
          daysBefore > LMP_MAX_DAYS_BEFORE_REGISTRATION -> Violation.LMP_TOO_OLD
          else -> null
        }
      }

      REGISTRATION_DATE_QUESTION_CODE ->
        Violation.REGISTRATION_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      else -> null
    }
  }

  /** True when every date field in [fields] holds an acceptable value — the submit/next gate. */
  fun allDatesValid(
    fields: List<FormFieldSchema>,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Boolean = fields
    .filter { it.inputType == FormFieldInputType.DATE }
    .all { violationFor(it.questionCode, answers, registrationDate) == null }

  /**
   * Date the DOB and LMP rules are measured against: the answered registration date when there is
   * one, else [registrationDate] (today).
   *
   * Clamped so it can never be later than [registrationDate] — otherwise a registration date that
   * is itself invalid (in the future, e.g. from a draft written before this rule existed) would
   * shift the DOB and LMP windows forward with it and quietly widen them. The registration date's
   * own violation still blocks the form; this just stops one bad answer from loosening the others.
   */
  private fun referenceDate(answers: FormAnswers, registrationDate: LocalDate): LocalDate =
    (answeredRegistrationDate(answers) ?: registrationDate).coerceAtMost(registrationDate)

  private fun answeredRegistrationDate(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))

  private fun parse(raw: String?): LocalDate? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
