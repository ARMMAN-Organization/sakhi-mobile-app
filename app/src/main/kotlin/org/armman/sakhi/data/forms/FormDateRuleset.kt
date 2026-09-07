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
* From the `DELIVERY_VISIT` form (spec rows given directly by product, not the form-spec sheet
 * above): "Delivery form filled date" auto-selects today (bounds-only "not future" floor here;
 * the actual prefill is [org.armman.sakhi.ui.delivery.DeliverySessionViewModel]'s job). "Date of
 * delivery" and "Date of discharge" must both be strictly after the mother's ANC registration date
 * AND her LMP, and no later than today; discharge is additionally floored at the answered delivery
 * date (same day allowed, not earlier). "Date of death" has no registration/LMP floor at all —
 * just strictly after the answered delivery date, and not in the future. See
 * [DeliveryQuestionCodes] for the question codes (two of the four — [DeliveryQuestionCodes
 * .DATE_OF_DISCHARGE]/[DeliveryQuestionCodes.DATE_OF_DEATH] — are UNCONFIRMED against a live
 * schema payload; see their own docs).
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

  /** Bug fix (2026-08-22): [DOB_QUESTION_CODE] ("date_of_birth") is reused by two unrelated
   * fields — MOTHER_REGISTRATION's own beneficiary DOB (needs [MIN_AGE_YEARS]..[MAX_AGE_YEARS],
   * an adult range) and INFANT_VISIT/INC_VISIT/CCV_VISIT's child-registration-carried-forward DOB
   * in the visit form's "Tests" section (an infant, already validated against
   * [CHILD_AGE_CEILING_DAYS_MOTHER_LINKED]/[CHILD_AGE_CEILING_DAYS_INDEPENDENT] at registration
   * time — re-applying an adult age range here rejected every real infant DOB with
   * "Age must be between 10 and 50 years"). [boundsFor]/[violationFor] take the field's [formCode]
   * to tell the two apart; literal strings, not a reference to
   * [org.armman.sakhi.ui.visitform.FORM_CODES_INFANT_FAMILY], for the same reason [FALLBACK_MAP]
   * in [VisitCodeFormResolver] duplicates its own literals — this is the data layer, and a
   * data->ui import would invert the app's dependency direction. */
  private val CHILD_VISIT_FORM_CODES = setOf("INFANT_VISIT", "INC_VISIT", "CCV_VISIT")

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
   * bounds — must be strictly after LMP, and no later than today (this form's own submission-day
   * proxy, per this file's existing LMP/DOB convention).
   *
   * Bug fix (2026-09-02): this previously capped the upper bound at registration date + 5 days,
   * which is a literal reading of "+5 days after ... submission date" but let the picker (and a
   * manually-entered value) accept a date up to 5 days in the future — contradicting the spec
   * row's own closing sentence, "Should not accept any date post this" (i.e. post today). "No
   * future date" now wins; there is no longer a grace window past today.
   */
  const val ANC1_DATE_QUESTION_CODE = "if_anc1_completed_please_record_the_date"

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
   * ANC/Infant Closure forms' "Closure visit date" (spec row 1): "dd-mm-yyyy, Should auto populate
   * today's date." The prefill itself lives in [org.armman.sakhi.ui.adhocform.AdHocFormViewModel]
   * (`prefillTodayDateFields`); this file only supplies the matching "not future" bound so a
   * Sakhi can't backdate a prefilled value into the future by hand.
   */
  const val CLOSURE_VISIT_DATE_QUESTION_CODE = "closure_visit_date"

  /**
   * ANC/Infant Closure forms' "Date of event" (spec row 4): "Should not accept future date and
   * before registration date." Two-sided: [registrationDate] (this form's own fill date, i.e.
   * "today" — same convention as every other bound in this file) caps it from above, and the
   * beneficiary's actual enrollment date — passed in separately as [boundsFor]'s
   * `beneficiaryRegistrationDate`, since it is a different value from [registrationDate] here and
   * genuinely unavailable for some rows (see [org.armman.sakhi.data.beneficiary.Beneficiary
   * .registrationDate]) — floors it from below. No lower bound at all when that value is
   * unavailable, matching this file's existing "unconstrained until the data exists" convention.
   */
  const val DATE_OF_EVENT_QUESTION_CODE = "date_of_event"

  /**
   * Referral form's "Referral visit form filled date" (spec row 1): "Should accept only today's
   * date." The backend's own `dateRule` for this field only declares `notFuture: true` (confirmed
   * 2026-08-18 against a real `GET /forms/REFERRAL_VISIT/active-version` payload) — looser than the
   * spec text, which reads as "must equal today."
   *
   * Bug fix (2026-09-04): this file previously followed the backend's looser rule (no lower bound
   * at all), which let a Sakhi manually edit this field into a past date after it was auto-populated
   * with today — contradicting spec row 1's "only today's date." Now floored at [registrationDate]
   * in [boundsFor] (min = max = today), stricter client-side than the backend actually enforces.
   * Safe: the backend still accepts anything not in the future, so this only narrows what the app
   * offers, never what it submits. Auto-populated with today on load
   * (`AdHocFormViewModel.prefillTodayDateFields`), so in practice this only matters if the Sakhi
   * manually edits it.
   */
  const val REFERRAL_FORM_FILLED_DATE_QUESTION_CODE = "referral_form_filled_date"

  /**
   * Referral form's "Decided date for visit to health facility" (spec row 8): "Should accept
   * todays date or future date." The backend expresses part of this relationally —
   * `dateRule.notBefore.field = "referral_form_filled_date"` — so the floor honours whatever date
   * the Sakhi put in [REFERRAL_FORM_FILLED_DATE_QUESTION_CODE] when that is today or later.
   *
   * Bug fix (2026-09-04): at the time of this fix, [REFERRAL_FORM_FILLED_DATE_QUESTION_CODE] had
   * no lower bound of its own, so a Sakhi could backdate it, and this field's floor was inheriting
   * that past date wholesale — letting her then pick yesterday (or earlier) here too, which
   * directly contradicted this field's own "todays date or future date" rule. The floor was set to
   * the LATER of [registrationDate] (today) and the answered [REFERRAL_FORM_FILLED_DATE_QUESTION_CODE]
   * value, so today is always the hard floor regardless of what that other field holds.
   *
   * [REFERRAL_FORM_FILLED_DATE_QUESTION_CODE] was itself floored at today in a later fix the same
   * day, so the backdated-answer path this guards against should no longer be reachable through the
   * UI — this floor is kept anyway as a defensive second layer, and its own bounds check still
   * defaults correctly to today if that field is ever unanswered.
   */
  const val DECIDED_VISIT_DATE_QUESTION_CODE = "decided_visit_date"

  /**
   * Referral Follow-up's "Date of the form filled" (spec row 1): "Should accept today's date."
   * Same shape as [REFERRAL_FORM_FILLED_DATE_QUESTION_CODE] — backend's own `dateRule` only
   * declares `notFuture: true` (confirmed 2026-08-18 against a real
   * `GET /forms/REFERRAL_FOLLOWUP_VISIT/active-version` payload), and it's auto-populated with
   * today on load (`AdHocFormViewModel.prefillTodayDateFields`).
   */
  const val FOLLOWUP_FORM_FILLED_DATE_QUESTION_CODE = "form_filled_date"

  /**
   * Referral Follow-up's "Date when the beneficiary visited the first health facility?" (spec row
   * 10): "Should accept todays date or past date." Backend's `dateRule` is `notFuture: true` only
   * — no lower bound at all, matching the spec's "or past date" (unbounded).
   */
  const val FIRST_FACILITY_VISIT_DATE_QUESTION_CODE = "first_facility_visit_date"

  /**
   * Referral Follow-up's "Date when the beneficiary visited the LAST referred center?" (spec row
   * 15). The spec's own prose says "referral form filled date or after that," but the backend's
   * actual `dateRule` — `notBefore.field: "first_facility_visit_date"`, `notFuture: true` — floors
   * it against this SAME follow-up form's own [FIRST_FACILITY_VISIT_DATE_QUESTION_CODE] answer
   * instead (which makes more sense anyway: a Sakhi can't have visited the LAST facility before
   * the FIRST one). Following the backend's actual rule, same convention as every other bound in
   * this file. Falls back to [registrationDate] (today) if the first-facility date is somehow
   * still blank, so this never opens up to the unbounded past.
   */
  const val LAST_FACILITY_VISIT_DATE_QUESTION_CODE = "last_facility_visit_date"

  /**
   * Referral Follow-up's "Date when beneficiary planning to visit further referral center" (spec
   * row 24): "Should accept todays date or future date." Unlike every other Follow-up date field,
   * the backend's own schema carries NO `dateRule` at all for this one (confirmed 2026-08-18
   * against the same `GET /forms/REFERRAL_FOLLOWUP_VISIT/active-version` payload) — so this bound
   * is purely a client-side addition from the spec text, not a mirror of an existing backend rule.
   */
  const val FURTHER_REFERRAL_PLANNED_DATE_QUESTION_CODE = "further_referral_planned_date"

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

    /** [ANC1_DATE_QUESTION_CODE] is after today (the registration date). */
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

    /** [DeliveryQuestionCodes.DATE_OF_DELIVERY] is after today. The stricter "must be after
     * registration/LMP" half of that field's rule is prevention-only (see [boundsFor]) — this
     * file has no [motherLmpDate]-equivalent input on [violationFor], same "bounds-only" gap as
     * [DATE_OF_EVENT_QUESTION_CODE]'s registration-date floor. */
    DELIVERY_DATE_IN_FUTURE,

    /** [DeliveryQuestionCodes.DATE_OF_DISCHARGE] is after today. */
    DISCHARGE_DATE_IN_FUTURE,

    /** [DeliveryQuestionCodes.DATE_OF_DISCHARGE] is before the answered
     * [DeliveryQuestionCodes.DATE_OF_DELIVERY] — discharge can be the same day as delivery, just
     * not earlier. */
    DISCHARGE_DATE_BEFORE_DELIVERY,

    /** [DeliveryQuestionCodes.DATE_OF_DEATH] is after today. */
    DEATH_DATE_IN_FUTURE,

    /** [DeliveryQuestionCodes.DATE_OF_DEATH] is on or before the answered
     * [DeliveryQuestionCodes.DATE_OF_DELIVERY] (must be strictly after). */
    DEATH_DATE_NOT_AFTER_DELIVERY,
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
    /** The active form's own code — only meaningful for disambiguating [DOB_QUESTION_CODE]
     * between MOTHER_REGISTRATION and the infant-visit family (see [CHILD_VISIT_FORM_CODES]'s
     * doc). Every other question code ignores it. */
    formCode: String? = null,
    /** The beneficiary's actual enrollment date, only meaningful for
     * [DATE_OF_EVENT_QUESTION_CODE] — see that constant's doc. Every other caller (registration
     * forms, visit forms) leaves this null; it has no bearing on any other question code. */
    beneficiaryRegistrationDate: LocalDate? = null,
    /** The mother's LMP on file, only meaningful for [DeliveryQuestionCodes.DATE_OF_DELIVERY] /
     * [DeliveryQuestionCodes.DATE_OF_DISCHARGE] (spec: both must be strictly after her LMP, same
     * as her registration date). Sourced from [org.armman.sakhi.data.beneficiaryprofile
     * .BeneficiaryProfile.lmp] by [org.armman.sakhi.ui.delivery.DeliverySessionViewModel] — the
     * only caller that ever passes this; null (unbounded lower end) when unavailable, same
     * "missing data gap" convention as [beneficiaryRegistrationDate]. */
    motherLmpDate: LocalDate? = null,
    /** The mother's actual delivery date (spec/reported bug, 2026-08-25: "Baby's date of birth
     * cannot be earlier than the mother's delivery date"). Only meaningful for
     * [ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT] — floors that field's picker at the
     * delivery date instead of the wider age-ceiling window when a delivery event produced this
     * registration. Sourced from the submitted `DELIVERY_VISIT` answers by
     * [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationViewModel]; null for the standalone
     * Child Registration flow (no delivery event to floor against), same "missing data gap"
     * convention as [beneficiaryRegistrationDate]/[motherLmpDate] above. */
    deliveryDate: LocalDate? = null,
  ): Bounds? {
    val reference = referenceDate(answers, registrationDate)
    return when (questionCode) {
      CLOSURE_VISIT_DATE_QUESTION_CODE -> Bounds(min = null, max = registrationDate)

      DATE_OF_EVENT_QUESTION_CODE -> Bounds(min = beneficiaryRegistrationDate, max = registrationDate)

      // Bug fix (2026-09-04): backend's own dateRule only declares notFuture (no lower bound),
      // which is looser than spec row 1's "Should accept only today's date" — a Sakhi could
      // backdate this into the past. The picker/prefill already default it to today, but nothing
      // stopped a manual edit into an earlier date. Flooring it at registrationDate (today) makes
      // this field today-only client-side, stricter than the backend's enforced rule but matching
      // the SRS text; the backend still accepts anything not in the future, so this is safe.
      REFERRAL_FORM_FILLED_DATE_QUESTION_CODE -> Bounds(min = registrationDate, max = registrationDate)

      // Bug fix (2026-09-04): floored solely at the answered referral_form_filled_date, which
      // itself has no lower bound (see that constant's own case above) and can therefore hold a
      // backdated value if the Sakhi edits it into the past. That let this field's picker inherit
      // a past-date floor and offer yesterday (or earlier) for "Decided date for visit to health
      // facility" — contradicting spec row 8's "Should accept todays date or future date". Now
      // floored at whichever is LATER of the two: the backend's own relational rule
      // (dateRule.notBefore.field = referral_form_filled_date) still applies when that answer is
      // today or later, but registrationDate (today) always wins as the hard floor otherwise.
      DECIDED_VISIT_DATE_QUESTION_CODE -> Bounds(
        min = maxOfNullable(referralFormFilledDateAnswer(answers), registrationDate),
        max = null,
      )

      FOLLOWUP_FORM_FILLED_DATE_QUESTION_CODE -> Bounds(min = null, max = registrationDate)

      FIRST_FACILITY_VISIT_DATE_QUESTION_CODE -> Bounds(min = null, max = registrationDate)

      LAST_FACILITY_VISIT_DATE_QUESTION_CODE -> Bounds(
        min = firstFacilityVisitDateAnswer(answers) ?: registrationDate,
        max = registrationDate,
      )

      FURTHER_REFERRAL_PLANNED_DATE_QUESTION_CODE -> Bounds(min = registrationDate, max = null)

      DOB_QUESTION_CODE -> if (formCode in CHILD_VISIT_FORM_CODES) {
        // Child's DOB in the visit form — already bounded at registration time; no adult age
        // rule applies here (see this constant's own doc).
        null
      } else {
        adultDobBounds(reference)
      }

      MOTHER_DOB_QUESTION_CODE -> adultDobBounds(reference)

      // Bug fix (2026-09-02): the picker's ceiling was wrongly set to
      // reference.minusDays(LMP_MIN_DAYS_BEFORE_REGISTRATION) — the "registration/LMP gap must be
      // >30 days" rule applied as a CALENDAR bound, which greyed out every date from ~31 days ago
      // up to today (reported: max selectable was ~a month before "today", not "today" itself).
      // "Cannot be future" (this constant's own KDoc) is the only picker-level ceiling; the
      // >30-day recency rule stays enforced, just as a post-pick validation message
      // ([Violation.LMP_TOO_RECENT] below) rather than blocking the date from being selected at all.
      LMP_DATE_QUESTION_CODE -> Bounds(
        min = reference.minusDays(LMP_MAX_DAYS_BEFORE_REGISTRATION),
        max = reference,
      )

      // "After LMP" with no LMP answer yet has nothing to bound against — leave the lower end
      // open rather than guessing; violationFor is likewise a no-op until LMP is answered (parse()
      // returns null and the whole check is skipped, same convention as every other rule here).
      // Bug fix (2026-09-02): "max = registrationDate.plusDays(5)" let the picker open 5 days
      // into the future, which is what let a Sakhi pick a future ANC-1 date at all — see this
      // question code's own KDoc for why "no future date" now wins over the literal "+5 days"
      // spec reading.
      ANC1_DATE_QUESTION_CODE -> Bounds(
        min = lmpDateAnswer(answers)?.plusDays(1),
        max = registrationDate,
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

      // INC/infant-visit form's OPV-0 dose date — same "not in the future" rule as the
      // vaccination-at-birth dates above, but kept as its own case since this question belongs to
      // a different form (INC/INFANT_VISIT, not CHILD_REGISTRATION).
      "opv_0_date" -> Bounds(min = null, max = registrationDate)

      // Bug fix (found in manual QA, 2026-08-21): none of INC1's other per-dose vaccination dates
      // had a bounds case at all, so each one silently fell through to `else -> null` (no picker
      // limit, no violation) and accepted future dates — same gap opv_0_date had before its own
      // fix above. Spec rows 39/43/45/49/51 (Infant Visits CSV). Same "not in the future" rule,
      // same registrationDate ceiling as every other dose date in this file.
      //
      // UNCONFIRMED — these `question_code`s are a snake_case guess from the spec's own field
      // labels, following this file's established naming convention (see DATE_OF_DISCHARGE's own
      // doc for the exact silent-failure risk of an unverified code). Verify each against a live
      // `GET /forms/INFANT_VISIT/active-version` response and correct here if any differ — a wrong
      // guess means this case never matches and the field is right back to unbounded.
      //
      // Reopened bug (2026-08-21): QA confirmed OPV-1/Pentavalent-1/Rotavirus1/PCV1 all validate
      // correctly now, but Hepatitis B date still accepted future dates — "hepatitis_b_birth_dose_date"
      // was the wrong guess for that one field. Every OTHER guess above is a literal word-order
      // transform of its own spec label (e.g. "Rotavirus1 date" -> "rotavirus1_date", "PCV1 date" ->
      // "pcv1_date"), but "hepatitis_b_birth_dose_date" reorders Q39's label ("Hepatitis B date—
      // Birth Dose") by moving "date" to the end instead of keeping it in the middle. Adding the
      // literal-order equivalent, "hepatitis_b_date_birth_dose", alongside the original guess —
      // still UNCONFIRMED against a live schema, so both stay listed rather than replacing one
      // guess with another equally-unverified one.
      "hepatitis_b_birth_dose_date", // Q39 "Hepatitis B date– Birth Dose" (original guess)
      "hepatitis_b_date_birth_dose", // Q39, literal-order guess — see reopened-bug note above
      "opv_1_date", // Q43 "OPV-1 date"
      "pentavalent_1_dpt1_date", // Q45 "Pentavalent-1/DPT1 date"
      "rotavirus1_date", // Q49 "Rotavirus1 date"
      "pcv1_date", // Q51 "PCV1 date (if Applicable)"
      -> Bounds(min = null, max = registrationDate)

      // Measured against registrationDate rather than `reference` on purpose: the ViewModel's
      // eligibility gate counts days from the same registrationDate, and prevention must not be able
      // to disagree with detection. CHILD_REGISTRATION v2 does declare a registration-date question,
      // but it is prefilled with — and capped at — today, so the two values agree in practice; this
      // keeps them agreeing even if a Sakhi back-dates it.
      ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT -> Bounds(
        // Floored at the mother's actual delivery date when known (a delivery-session
        // registration) — a baby cannot be born before the delivery that produced this
        // registration. Falls back to the wider age-ceiling window when there is no delivery event
        // to floor against (the standalone/direct Child Registration flow).
        min = deliveryDate ?: registrationDate.minusDays(childAgeCeilingDays(answers)),
        // "Should not accept future date" (spec row 6.0) — an infant aged 0 days is valid, so today
        // is selectable.
        max = registrationDate,
      )

      // "Should automatically select today's date" — bounds-only "not future" floor, same shape
      // as CLOSURE_VISIT_DATE_QUESTION_CODE/REFERRAL_FORM_FILLED_DATE_QUESTION_CODE above (the
      // actual today-prefill is DeliverySessionViewModel's job, same division of labour as those
      // two).
      DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON -> Bounds(min = null, max = registrationDate)

      // "Should be > registration and LMP date and <= todays date". registrationDate here is
      // "today" (this form's own fill date, this file's usual convention) — the ceiling. The
      // floor is the day after whichever of her actual ANC registration date / LMP is later.
      DeliveryQuestionCodes.DATE_OF_DELIVERY -> Bounds(
        min = deliveryLowerBound(beneficiaryRegistrationDate, motherLmpDate),
        max = registrationDate,
      )

      // Same floor as DATE_OF_DELIVERY (> registration and LMP), ADDITIONALLY floored at the
      // answered delivery date itself (discharge can be same-day, not earlier) — take whichever
      // of the two lower bounds is later. Ceiling: <= today, same as every other field here.
      DeliveryQuestionCodes.DATE_OF_DISCHARGE -> Bounds(
        min = maxOfNullable(
          deliveryLowerBound(beneficiaryRegistrationDate, motherLmpDate),
          dateOfDeliveryAnswer(answers),
        ),
        max = registrationDate,
      )

      // "Should accept old date or todays date. Should be after delivery date." No registration/
      // LMP floor at all here (unlike DATE_OF_DELIVERY/DATE_OF_DISCHARGE) — only strictly after
      // the answered delivery date. Open (unbounded) until delivery is answered — nothing to
      // derive from yet, same convention as ANC1_DATE_QUESTION_CODE's LMP-based lower bound.
      DeliveryQuestionCodes.DATE_OF_DEATH -> Bounds(
        min = dateOfDeliveryAnswer(answers)?.plusDays(1),
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

  /** Shared DOB bound for [DOB_QUESTION_CODE] (when not the visit-form child DOB) and
   * [MOTHER_DOB_QUESTION_CODE] — same [MIN_AGE_YEARS]..[MAX_AGE_YEARS] adult range either way. */
  private fun adultDobBounds(reference: LocalDate): Bounds = Bounds(
    // A DOB on this boundary still floors to MAX_AGE_YEARS; one day earlier would floor to
    // MAX_AGE_YEARS + 1 and be out of range.
    min = reference.minusYears(MAX_AGE_YEARS + 1).plusDays(1),
    max = reference.minusYears(MIN_AGE_YEARS),
  )

  /** Shared DOB violation check for [DOB_QUESTION_CODE] (when not the visit-form child DOB) and
   * [MOTHER_DOB_QUESTION_CODE] — same [MIN_AGE_YEARS]..[MAX_AGE_YEARS] adult range either way. */
  private fun adultDobViolation(value: LocalDate, reference: LocalDate): Violation? {
    // Floored whole years, per the spec's "consider floor".
    val age = ChronoUnit.YEARS.between(value, reference)
    return Violation.AGE_OUT_OF_RANGE.takeIf { age < MIN_AGE_YEARS || age > MAX_AGE_YEARS }
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
    /** See [boundsFor]'s own [formCode] doc — same disambiguation, same default. */
    formCode: String? = null,
  ): Violation? {
    val value = parse(answers.valueOf(questionCode)) ?: return null
    val reference = referenceDate(answers, registrationDate)

    return when (questionCode) {
      DOB_QUESTION_CODE -> if (formCode in CHILD_VISIT_FORM_CODES) {
        null
      } else {
        adultDobViolation(value, reference)
      }

      MOTHER_DOB_QUESTION_CODE -> adultDobViolation(value, reference)

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
        val tooLate = value.isAfter(registrationDate)
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

      // INC/infant-visit form's OPV-0 dose date — same "not in the future" rule as the
      // vaccination-at-birth dates above, but kept as its own case since this question belongs to
      // a different form (INC/INFANT_VISIT, not CHILD_REGISTRATION). No form-agnostic "date in
      // future" violation exists in this enum (every existing case is field-specific), so this
      // reuses VACCINATION_AT_BIRTH_DATE_IN_FUTURE rather than introducing a new constant.
      "opv_0_date" -> Violation.VACCINATION_AT_BIRTH_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      // Bug fix (found in manual QA, 2026-08-21) — see the matching case in boundsFor() above for
      // why these codes are added together and why they're UNCONFIRMED guesses pending live
      // schema verification. Reopened same day: "hepatitis_b_birth_dose_date" alone didn't cover
      // Hepatitis B's real question_code (still accepted future dates after the other 4 were
      // confirmed fixed), so "hepatitis_b_date_birth_dose" — the same literal-label-order pattern
      // every other guess here follows — was added alongside it. See boundsFor()'s own note.
      "hepatitis_b_birth_dose_date",
      "hepatitis_b_date_birth_dose",
      "opv_1_date",
      "pentavalent_1_dpt1_date",
      "rotavirus1_date",
      "pcv1_date",
      -> Violation.VACCINATION_AT_BIRTH_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      // Only the "not future" half is detectable here — the "> registration/LMP" half needs
      // motherLmpDate, which this function has no parameter for (see DELIVERY_DATE_IN_FUTURE's own
      // doc). The picker still prevents it via `boundsFor`; this only catches a value that got in
      // another way (older draft, backend-restored answer).
      DeliveryQuestionCodes.DATE_OF_DELIVERY ->
        Violation.DELIVERY_DATE_IN_FUTURE.takeIf { value.isAfter(registrationDate) }

      DeliveryQuestionCodes.DATE_OF_DISCHARGE -> {
        val delivery = dateOfDeliveryAnswer(answers)
        when {
          value.isAfter(registrationDate) -> Violation.DISCHARGE_DATE_IN_FUTURE
          delivery != null && value.isBefore(delivery) -> Violation.DISCHARGE_DATE_BEFORE_DELIVERY
          else -> null
        }
      }

      DeliveryQuestionCodes.DATE_OF_DEATH -> {
        val delivery = dateOfDeliveryAnswer(answers)
        when {
          value.isAfter(registrationDate) -> Violation.DEATH_DATE_IN_FUTURE
          delivery != null && !value.isAfter(delivery) -> Violation.DEATH_DATE_NOT_AFTER_DELIVERY
          else -> null
        }
      }

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

  private fun referralFormFilledDateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(REFERRAL_FORM_FILLED_DATE_QUESTION_CODE))

  private fun firstFacilityVisitDateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(FIRST_FACILITY_VISIT_DATE_QUESTION_CODE))

  private fun td1DateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(TdDoseQuestionCodes.TD_1_DATE_QUESTION_CODE))

  private fun td2DateAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(TdDoseQuestionCodes.TD_2_DATE_QUESTION_CODE))

  /** The answered [DeliveryQuestionCodes.DATE_OF_DELIVERY] value, or null if it hasn't been
   * answered (or isn't parseable) yet — [DeliveryQuestionCodes.DATE_OF_DISCHARGE]'s and
   * [DeliveryQuestionCodes.DATE_OF_DEATH]'s lower bounds have nothing to derive from in that
   * case. */
  private fun dateOfDeliveryAnswer(answers: FormAnswers): LocalDate? =
    parse(answers.valueOf(DeliveryQuestionCodes.DATE_OF_DELIVERY))

  /** Inclusive lower bound for a date that must be strictly AFTER both the mother's ANC
   * registration date and her LMP: whichever of the two is later, plus one day. Either or both may
   * be unavailable (this file's usual "missing data gap" convention) — the bound simply narrows to
   * whichever is present, or stays fully open (null) if neither is. */
  private fun deliveryLowerBound(beneficiaryRegistrationDate: LocalDate?, motherLmpDate: LocalDate?): LocalDate? =
    maxOfNullable(beneficiaryRegistrationDate, motherLmpDate)?.plusDays(1)

  /** Null-tolerant [maxOf] — null loses to any real date rather than winning outright, so a bound
   * built from two optional dates only excludes a side that's actually missing instead of
   * collapsing the whole bound to "unconstrained" the moment either input is null. */
  private fun maxOfNullable(a: LocalDate?, b: LocalDate?): LocalDate? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
  }

  private fun parse(raw: String?): LocalDate? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
