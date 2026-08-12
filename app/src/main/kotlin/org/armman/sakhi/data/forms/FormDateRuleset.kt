package org.armman.sakhi.data.forms

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Date rules from the form spec ("Revised App Form Final 20.3.26").
 *
 * From the `Registration_PW_D` (mother enrollment) tab:
 * - **LMP date** (row 7): "Cannot be future and on or after ANC registration date. Difference
 *   between registration and LMP should be >30 days and <240 days."
 * - **Registration date** (row 13): "Automatically popup todays date" — so never in the future.
 * - **Date of birth** (row 23): "Should accept only those dates whose age will be in range 10-50
 *   years. Error message to be shown if the age is out of the range."
 * - **Td dose dates** (row 44, [TdDoseQuestionCodes]): "Should accept the old date or today's
 *   date... Should not accept a future date." Td-2 must be after Td-1, and Td-Booster after Td-2.
 *   These 3 fields didn't exist until ARMMAN's 2026-08-06 schema change (see
 *   `td-dose-dates-schema-gap.md`) — the checkbox-only field they hang off,
 *   [TdDoseQuestionCodes.TD_DOSE_QUESTION_CODE], has its own mutual-exclusivity rule in
 *   [FormMultiSelectExclusivity], not here.
 *
 * From the `Infant Registration form` (child registration) tab:
 * - **Age or DOB of the mother** (row 20.0): same 10-50 year range, so [MOTHER_DOB_QUESTION_CODE]
 *   shares the DOB branch rather than duplicating the rule — and therefore also shares the
 *   already-translated `enrollment_error_age_range` message via [Violation.AGE_OUT_OF_RANGE].
 * - **Date of birth of infant** (row 6.0): "Should not accept future date." The row's flat
 *   `0-183 days` is SUPERSEDED — SRS FR-S-2.3 splits the window by registration path (0-183 days
 *   when linked to an enrolled mother, 0-365 days when registered directly), and the backend's
 *   `create-beneficiary.dto.ts` `CHILD_AGE_CEILING_DAYS` enforces exactly that split. This file
 *   therefore bounds the picker per path; see [CHILD_AGE_CEILING_DAYS_MOTHER_LINKED].
 *   **Bounds only — no [Violation] case**: `DynamicChildRegistrationViewModel` already detects an
 *   out-of-window or future infant DOB with three path-specific localized messages
 *   (`INELIGIBLE_MOTHER`/`INELIGIBLE_DIRECT`/`DOB_FUTURE`), so adding a violation here would render a
 *   SECOND error for one problem. Division of labour: this file prevents the bad pick, the ViewModel
 *   explains a bad value that is already in state (an older draft, a path switched after the fact).
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

  /** Inclusive age range a person's DOB must produce — the beneficiary's on the mother form
   * (spec row 23) and the mother's on the child form (Infant Registration row 20.0). */
  const val MIN_AGE_YEARS = 10L
  const val MAX_AGE_YEARS = 50L

  /**
   * Inclusive upper bound, in days, on the infant's age at registration when the child is linked to
   * an enrolled mother (SRS FR-S-2.3: "child must be registered between 0 and 6 months (0-183
   * days)"). Named to match the backend's `CHILD_AGE_CEILING_DAYS.MOTHER_LINKED` so the two are
   * greppable together — they must always agree, or the app lets through a submission the backend
   * then rejects.
   */
  const val CHILD_AGE_CEILING_DAYS_MOTHER_LINKED = 183L

  /**
   * Inclusive upper bound, in days, on the infant's age when registered directly (SRS FR-S-2.3:
   * "child can be registered between 0 and 12 months (0-365 days). Mother data is not linked").
   * Backend counterpart: `CHILD_AGE_CEILING_DAYS.INDEPENDENT`.
   *
   * Also the fallback used before the path radio is answered — see [childAgeCeilingDays].
   */
  const val CHILD_AGE_CEILING_DAYS_INDEPENDENT = 365L

  /**
   * Spec row 7 states the registration-to-LMP difference must be strictly `>30` and `<240` days,
   * so the inclusive day window an LMP answer may sit in is 31..239 days before registration.
   *
   * This is a data-sanity bound only — it catches an implausible/mistyped LMP, not "is this
   * pregnancy still enrollable". [GESTATIONAL_AGE_CEILING_WEEKS] below is the separate, stricter
   * eligibility rule; a value can sit inside this window and still be rejected by that one.
   */
  const val LMP_MIN_DAYS_BEFORE_REGISTRATION = 31L
  const val LMP_MAX_DAYS_BEFORE_REGISTRATION = 239L

  /**
   * Inclusive ceiling, in whole weeks, on gestational age at registration — reported bug: "allows
   * registration of a pregnant woman beyond the permitted 0-6 months pregnancy enrollment period."
   * The `Registration_PW_D` form-spec tab's own title states the intended window directly
   * ("ANC Enrollment form : 1 to 6 months of pregnancy"), and the same sheet treats "6 months" and
   * "24 weeks" as equivalent (rows 46/48, re: prior pregnancies/losses "before 6 months (24
   * weeks)") — there is no separate day-based figure anywhere in the spec, so 24 weeks is the
   * number used here.
   *
   * Deliberately separate from [LMP_MAX_DAYS_BEFORE_REGISTRATION]: that constant is a much wider
   * (34-week) data-sanity bound on the LMP date itself (is this a plausible date at all), while this
   * one is the PRD's actual enrollment-eligibility cutoff (should this pregnancy be enrolled at
   * all) — a value can pass the first and still fail this one, which is exactly the reported bug
   * (LMP 239 days before registration sits inside the sanity window but is ~34 weeks pregnant).
   *
   * Uses the same whole-weeks floor as [FormComputedFieldEvaluator]'s `GESTATIONAL_AGE_AT_REGISTRATION`
   * (`daysBetween / 7`) so the number shown on the form and the number this rule judges never
   * disagree.
   */
  const val GESTATIONAL_AGE_CEILING_WEEKS = 24L

  private const val DAYS_PER_WEEK = 7L

  /**
   * Spec row 42 ("If ANC1 completed, please record the date"): "Only accept date after LMP or +5
   * days after enrollment form submission date. Should not accept any date post this." Read as two
   * bounds — must be strictly after LMP, and no later than the registration date (this form's own
   * submission-day proxy, per this file's existing LMP/DOB convention) plus 5 days.
   */
  const val ANC1_DATE_QUESTION_CODE = "if_anc1_completed_please_record_the_date"
  const val ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION = 5L

  /**
   * ANC_VISIT's sonography-confirmed LMP edit (spec row 8, "Copy of ... ANC visit form.csv").
   * Spec asks for two checks: not future, AND the registration-to-LMP gap must be 31..239 days —
   * only the first is implemented (bharath, 2026-08-07). The second needs the beneficiary's ANC
   * registration date, which isn't available anywhere client-side yet (checked BeneficiaryProfile
   * and VisitContext directly — neither carries one); guessing a source risks blocking a real
   * Sakhi on a check that's silently wrong. [org.armman.sakhi.ui.visitform
   * .DynamicVisitFormViewModel] passes its own `visitDate` (today) as [boundsFor]'s
   * `registrationDate` param for this question code, so "not future" here really means
   * "not after today" — revisit once a real registration date is wired through.
   */
  const val LMP_DATE_EDIT_QUESTION_CODE = "lmp_date_edit"

  /**
   * Which rule a date answer breaks. Mapped to a localized message by the UI layer.
   *
   * Mirrors the legacy static flow's [org.armman.sakhi.ui.enrollment.FieldError] cases
   * (`LMP_FUTURE`/`LMP_TOO_RECENT`/`LMP_TOO_OLD`/`AGE_OUT_OF_RANGE`) so both flows say the same
   * thing to the Sakhi and share the same already-translated strings. Delete that copy along with
   * the static enrollment steps.
   */
  enum class Violation {
    /** A DOB ([DOB_QUESTION_CODE] or [MOTHER_DOB_QUESTION_CODE]) implies an age outside
     * [MIN_AGE_YEARS]..[MAX_AGE_YEARS] (includes any future DOB). */
    AGE_OUT_OF_RANGE,

    /** LMP is after the registration date. Reported separately from the window cases because
     * "cannot be in the future" is a clearer thing to read than a day count. */
    LMP_FUTURE,

    /** LMP is [LMP_MIN_DAYS_BEFORE_REGISTRATION] days or fewer before the registration date. */
    LMP_TOO_RECENT,

    /** LMP is more than [LMP_MAX_DAYS_BEFORE_REGISTRATION] days before the registration date. */
    LMP_TOO_OLD,

    /** Gestational age at registration (from LMP) exceeds [GESTATIONAL_AGE_CEILING_WEEKS] — the
     * pregnancy is beyond the 0-6 month enrollment window. Checked ahead of [LMP_TOO_OLD] in
     * [violationFor], so an LMP that is both "too old" for data-sanity AND beyond the enrollment
     * window reports this — the more specific, PRD-accurate reason — rather than the generic one. */
    GESTATIONAL_AGE_BEYOND_ENROLLMENT_WINDOW,

    /** Registration date is after today. */
    REGISTRATION_DATE_IN_FUTURE,

    /** [ANC1_DATE_QUESTION_CODE] is on or before the answered LMP date (must be strictly after). */
    ANC1_DATE_NOT_AFTER_LMP,

    /** [ANC1_DATE_QUESTION_CODE] is more than [ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION] days after
     * the registration date. */
    ANC1_DATE_TOO_LATE,

    /** One of the 3 Td-dose dates ([TdDoseQuestionCodes]) is after the registration date — spec
     * row 44: "Should not accept a future date." */
    TD_DATE_IN_FUTURE,

    /** Td-2 date is not strictly after the answered Td-1 date — spec row 44: "TD2 date should be
     * after TD1". */
    TD_2_NOT_AFTER_TD_1,

    /** Td-Booster date is not strictly after the answered Td-2 date — spec row 44: "the TD
     * booster dose should be after the TD2 date". */
    TD_BOOSTER_NOT_AFTER_TD_2,

    /** [LMP_DATE_EDIT_QUESTION_CODE] is after today — the registration-gap half of spec row 8 is
     * deliberately not checked yet, see that constant's doc. */
    LMP_DATE_EDIT_FUTURE,

    /** One of the 4 vaccination-at-birth dose dates ([VaccinationAtBirthQuestionCodes]) is after
     * the registration date — mirrors [TD_DATE_IN_FUTURE]: a vaccination cannot be dated after the
     * day the Sakhi is filling out the form. */
    VACCINATION_AT_BIRTH_DATE_IN_FUTURE,
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
      DOB_QUESTION_CODE, MOTHER_DOB_QUESTION_CODE -> Bounds(
        // A DOB on this boundary still floors to MAX_AGE_YEARS; one day earlier would floor to
        // MAX_AGE_YEARS + 1 and be out of range.
        min = reference.minusYears(MAX_AGE_YEARS + 1).plusDays(1),
        max = reference.minusYears(MIN_AGE_YEARS),
      )

      LMP_DATE_QUESTION_CODE -> Bounds(
        min = reference.minusDays(LMP_MAX_DAYS_BEFORE_REGISTRATION),
        max = reference.minusDays(LMP_MIN_DAYS_BEFORE_REGISTRATION),
      )

      // "After LMP" with no LMP answer yet has nothing to bound against — leave the lower end
      // open rather than guessing; violationFor is likewise a no-op until LMP is answered (parse()
      // returns null and the whole check is skipped, same convention as every other rule here).
      ANC1_DATE_QUESTION_CODE -> Bounds(
        min = lmpDateAnswer(answers)?.plusDays(1),
        max = registrationDate.plusDays(ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION),
      )

      // Matches BOTH published spellings — see REGISTRATION_DATE_QUESTION_CODES.
      in REGISTRATION_DATE_QUESTION_CODES -> Bounds(min = null, max = registrationDate)

      // "Not future" only — see LMP_DATE_EDIT_QUESTION_CODE's doc for why the registration-gap
      // check is deliberately left out.
      LMP_DATE_EDIT_QUESTION_CODE -> Bounds(min = null, max = reference)

      // Spec row 44: "Should not accept a future date" on all three, plus "TD2 date should be
      // after TD1, and the TD booster dose should be after the TD2 date". Each lower bound comes
      // from the PREVIOUS dose's answered date (open/unbounded until that's answered — nothing to
      // derive from yet, same convention as ANC1_DATE_QUESTION_CODE's LMP-based lower bound above).
      TdDoseQuestionCodes.TD_1_DATE_QUESTION_CODE -> Bounds(min = null, max = registrationDate)

      TdDoseQuestionCodes.TD_2_DATE_QUESTION_CODE -> Bounds(
        min = td1DateAnswer(answers)?.plusDays(1),
        max = registrationDate,
      )

      TdDoseQuestionCodes.TD_BOOSTER_DATE_QUESTION_CODE -> Bounds(
        min = td2DateAnswer(answers)?.plusDays(1),
        max = registrationDate,
      )

      // Q49 "Vaccination taken at birth?" ([VaccinationAtBirthQuestionCodes]) — each of the 4
      // per-vaccine dates must not be in the future, same as the Td-dose dates above. No lower
      // bound: unlike Td-1/Td-2/Td-Booster these 4 have no defined ordering against each other.
      VaccinationAtBirthQuestionCodes.BCG_DATE_QUESTION_CODE,
      VaccinationAtBirthQuestionCodes.OPV_DATE_QUESTION_CODE,
      VaccinationAtBirthQuestionCodes.HEPATITIS_B_DATE_QUESTION_CODE,
      VaccinationAtBirthQuestionCodes.VITAMIN_K_DATE_QUESTION_CODE,
      -> Bounds(min = null, max = registrationDate)

      // Measured against registrationDate rather than `reference` on purpose: the ViewModel's
      // eligibility gate counts days from the same registrationDate, and prevention must not be able
      // to disagree with detection. CHILD_REGISTRATION v2 does declare a registration-date question,
      // but it is prefilled with — and capped at — today, so the two values agree in practice; this
      // keeps them agreeing even if a Sakhi back-dates it.
      ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT -> Bounds(
        min = registrationDate.minusDays(childAgeCeilingDays(answers)),
        // "Should not accept future date" (spec row 6.0) — an infant aged 0 days is valid, so today
        // is selectable.
        max = registrationDate,
      )

      else -> null
    }
  }

  /**
   * The eligibility ceiling that applies to the infant DOB, per SRS FR-S-2.3's two sub-rules.
   *
   * An unanswered — or unrecognised — path falls back to the WIDER window. The path radio sits on an
   * earlier tab than the DOB, so in practice it is answered first; restricting the picker to 183 days
   * before we know the path would block legitimate direct registrations with no way for the Sakhi to
   * see why. The ViewModel's detection gate still refuses a value that is out of window for the path
   * she eventually picks, so the wider fallback cannot let a bad submission through.
   */
  private fun childAgeCeilingDays(answers: FormAnswers): Long =
    when (answers.valueOf(ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING)) {
      ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER -> CHILD_AGE_CEILING_DAYS_MOTHER_LINKED
      else -> CHILD_AGE_CEILING_DAYS_INDEPENDENT
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
      DOB_QUESTION_CODE, MOTHER_DOB_QUESTION_CODE -> {
        // Floored whole years, per the spec's "consider floor".
        val age = ChronoUnit.YEARS.between(value, reference)
        Violation.AGE_OUT_OF_RANGE.takeIf { age < MIN_AGE_YEARS || age > MAX_AGE_YEARS }
      }

      LMP_DATE_QUESTION_CODE -> {
        val daysBefore = ChronoUnit.DAYS.between(value, reference)
        // Same floor-to-whole-weeks the GESTATIONAL_AGE_AT_REGISTRATION computed field uses, so
        // this rule and the number displayed on the form always agree.
        val gestationalAgeWeeks = daysBefore / DAYS_PER_WEEK
        when {
          daysBefore < 0 -> Violation.LMP_FUTURE
          daysBefore < LMP_MIN_DAYS_BEFORE_REGISTRATION -> Violation.LMP_TOO_RECENT
          gestationalAgeWeeks > GESTATIONAL_AGE_CEILING_WEEKS ->
            Violation.GESTATIONAL_AGE_BEYOND_ENROLLMENT_WINDOW
          daysBefore > LMP_MAX_DAYS_BEFORE_REGISTRATION -> Violation.LMP_TOO_OLD
          else -> null
        }
      }

      ANC1_DATE_QUESTION_CODE -> {
        val lmp = lmpDateAnswer(answers)
        val tooLate = value.isAfter(registrationDate.plusDays(ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION))
        when {
          lmp != null && !value.isAfter(lmp) -> Violation.ANC1_DATE_NOT_AFTER_LMP
          tooLate -> Violation.ANC1_DATE_TOO_LATE
          else -> null
        }
      }

      in REGISTRATION_DATE_QUESTION_CODES ->
        Violation.REGISTRATION_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      LMP_DATE_EDIT_QUESTION_CODE -> Violation.LMP_DATE_EDIT_FUTURE.takeIf { value.isAfter(reference) }

      TdDoseQuestionCodes.TD_1_DATE_QUESTION_CODE ->
        Violation.TD_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      TdDoseQuestionCodes.TD_2_DATE_QUESTION_CODE -> {
        val td1 = td1DateAnswer(answers)
        when {
          value.isAfter(registrationDate) -> Violation.TD_DATE_IN_FUTURE
          td1 != null && !value.isAfter(td1) -> Violation.TD_2_NOT_AFTER_TD_1
          else -> null
        }
      }

      TdDoseQuestionCodes.TD_BOOSTER_DATE_QUESTION_CODE -> {
        val td2 = td2DateAnswer(answers)
        when {
          value.isAfter(registrationDate) -> Violation.TD_DATE_IN_FUTURE
          td2 != null && !value.isAfter(td2) -> Violation.TD_BOOSTER_NOT_AFTER_TD_2
          else -> null
        }
      }

      VaccinationAtBirthQuestionCodes.BCG_DATE_QUESTION_CODE,
      VaccinationAtBirthQuestionCodes.OPV_DATE_QUESTION_CODE,
      VaccinationAtBirthQuestionCodes.HEPATITIS_B_DATE_QUESTION_CODE,
      VaccinationAtBirthQuestionCodes.VITAMIN_K_DATE_QUESTION_CODE,
      -> Violation.VACCINATION_AT_BIRTH_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      // NOTE: DATE_OF_BIRTH_OF_INFANT is deliberately absent, even though `boundsFor` bounds it.
      // Its eligibility windows are detected by DynamicChildRegistrationViewModel, which has the
      // path-specific messages; reporting them here too would show the Sakhi two errors for one
      // problem, and would make `allDatesValid` a second gate over the same rule. Do not add it
      // without removing the ViewModel's gate first. See this object's KDoc.
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
    parse(answers.registrationDateAnswer())

  /** The answered [LMP_DATE_QUESTION_CODE] value, or null if LMP hasn't been answered (or isn't
   * parseable) yet — [ANC1_DATE_QUESTION_CODE]'s lower bound has nothing to derive from in that
   * case. */
  private fun lmpDateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(LMP_DATE_QUESTION_CODE))

  private fun td1DateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(TdDoseQuestionCodes.TD_1_DATE_QUESTION_CODE))

  private fun td2DateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(TdDoseQuestionCodes.TD_2_DATE_QUESTION_CODE))

  private fun parse(raw: String?): LocalDate? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
