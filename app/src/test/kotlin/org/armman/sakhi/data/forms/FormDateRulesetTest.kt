package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Rules under test come from the `Registration_PW_D` form-spec tab — DOB age 10-50 (row 23), LMP
 * more than 30 and less than 240 days before registration (row 7), registration date never in the
 * future (row 13) — plus the `Infant Registration form` tab's "Age or DOB of the mother" (row 20.0),
 * which reuses the same 10-50 range.
 */
class FormDateRulesetTest {

  private val registrationDate = LocalDate.of(2026, 7, 28)

  private fun answers(vararg pairs: Pair<String, String>): FormAnswers =
    pairs.fold(FormAnswers()) { acc, (code, value) -> acc.withSingleValue(code, value) }

  private fun dateField(questionCode: String) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "date",
    questionCode = questionCode,
  )

  // --- Date of birth: age must be 10..50 -------------------------------------------------------

  @Test
  fun `future date of birth is a violation`() {
    val violation = FormDateRuleset.violationFor(
      DOB_QUESTION_CODE,
      answers(DOB_QUESTION_CODE to registrationDate.plusDays(1).toString()),
      registrationDate,
    )

    assertEquals(FormDateRuleset.Violation.AGE_OUT_OF_RANGE, violation)
  }

  @Test
  fun `date of birth at both age bounds is accepted`() {
    val youngest = registrationDate.minusYears(FormDateRuleset.MIN_AGE_YEARS)
    val oldest = registrationDate.minusYears(FormDateRuleset.MAX_AGE_YEARS)

    assertNull(violationForDob(youngest))
    assertNull(violationForDob(oldest))
  }

  @Test
  fun `date of birth just outside each age bound is rejected`() {
    // A day later than the youngest allowed DOB floors to age 9; a year-and-a-day earlier than the
    // oldest floors to 51.
    val tooYoung = registrationDate.minusYears(FormDateRuleset.MIN_AGE_YEARS).plusDays(1)
    val tooOld = registrationDate.minusYears(FormDateRuleset.MAX_AGE_YEARS + 1).minusDays(1)

    assertEquals(FormDateRuleset.Violation.AGE_OUT_OF_RANGE, violationForDob(tooYoung))
    assertEquals(FormDateRuleset.Violation.AGE_OUT_OF_RANGE, violationForDob(tooOld))
  }

  @Test
  fun `date of birth bounds match the accepted age range`() {
    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(DOB_QUESTION_CODE, FormAnswers(), registrationDate),
    )

    // Every bound the picker offers must itself pass validation, or the picker would hand back a
    // value the gate then rejects.
    assertNull(violationForDob(requireNotNull(bounds.max)))
    assertNull(violationForDob(requireNotNull(bounds.min)))
    assertEquals(registrationDate.minusYears(FormDateRuleset.MIN_AGE_YEARS), bounds.max)
  }

  // --- Mother's DOB on the child form: same age 10..50 rule (Infant Registration row 20.0) ------

  @Test
  fun `MD-1 mother dob bounds span exactly the 10 to 50 age range`() {
    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(MOTHER_DOB_QUESTION_CODE, FormAnswers(), registrationDate),
    )

    assertEquals(registrationDate.minusYears(FormDateRuleset.MIN_AGE_YEARS), bounds.max)
    assertEquals(
      registrationDate.minusYears(FormDateRuleset.MAX_AGE_YEARS + 1).plusDays(1),
      bounds.min,
    )
    // Every date the picker offers must itself pass validation, or the picker would hand back a
    // value the gate then rejects.
    assertNull(violationForMotherDob(requireNotNull(bounds.max)))
    assertNull(violationForMotherDob(requireNotNull(bounds.min)))
  }

  @Test
  fun `MD-2 mother aged exactly 10 is accepted`() {
    assertNull(violationForMotherDob(registrationDate.minusYears(FormDateRuleset.MIN_AGE_YEARS)))
  }

  @Test
  fun `MD-3 mother aged exactly 50 is accepted`() {
    assertNull(violationForMotherDob(registrationDate.minusYears(FormDateRuleset.MAX_AGE_YEARS)))
  }

  @Test
  fun `MD-4 mother aged 9 is rejected`() {
    // One day later than the youngest allowed DOB floors to 9.
    val tooYoung = registrationDate.minusYears(FormDateRuleset.MIN_AGE_YEARS).plusDays(1)

    assertEquals(
      FormDateRuleset.Violation.AGE_OUT_OF_RANGE,
      violationForMotherDob(tooYoung),
    )
  }

  @Test
  fun `MD-5 mother aged 51 is rejected`() {
    val tooOld = registrationDate.minusYears(FormDateRuleset.MAX_AGE_YEARS + 1).minusDays(1)

    assertEquals(
      FormDateRuleset.Violation.AGE_OUT_OF_RANGE,
      violationForMotherDob(tooOld),
    )
  }

  @Test
  fun `MD-6 future mother dob is rejected`() {
    assertEquals(
      FormDateRuleset.Violation.AGE_OUT_OF_RANGE,
      violationForMotherDob(registrationDate.plusDays(1)),
    )
  }

  @Test
  fun `MD-7 blank and unparseable mother dob are not date violations`() {
    // Blank is the required-field gate's job; an unparseable value isn't a *range* problem.
    assertNull(
      FormDateRuleset.violationFor(MOTHER_DOB_QUESTION_CODE, FormAnswers(), registrationDate),
    )
    assertNull(violationForRaw(MOTHER_DOB_QUESTION_CODE, ""))
    assertNull(violationForRaw(MOTHER_DOB_QUESTION_CODE, "not-a-date"))
    // The spec writes dates as dd-mm-yyyy, but answers are stored ISO; a dd-mm-yyyy string is
    // unparseable, not out of range.
    assertNull(violationForRaw(MOTHER_DOB_QUESTION_CODE, "28-07-2000"))
  }

  @Test
  fun `MD-8 age is floored, not rounded, so a birthday not yet reached still counts as younger`() {
    // Turns 10 one day after the registration date -> floors to 9, must be rejected. A rounding
    // implementation would call this 10 and wrongly accept it.
    val turnsTenTomorrow = registrationDate
      .minusYears(FormDateRuleset.MIN_AGE_YEARS)
      .plusDays(1)

    assertEquals(
      FormDateRuleset.Violation.AGE_OUT_OF_RANGE,
      violationForMotherDob(turnsTenTomorrow),
    )
    // Guard the premise: one day earlier (birthday reached) is accepted.
    assertNull(violationForMotherDob(turnsTenTomorrow.minusDays(1)))
  }

  @Test
  fun `MD-9 mother dob and beneficiary dob are judged independently`() {
    val bothAnswered = answers(
      DOB_QUESTION_CODE to registrationDate.minusYears(25).toString(),
      MOTHER_DOB_QUESTION_CODE to registrationDate.minusYears(60).toString(),
    )

    assertNull(FormDateRuleset.violationFor(DOB_QUESTION_CODE, bothAnswered, registrationDate))
    assertEquals(
      FormDateRuleset.Violation.AGE_OUT_OF_RANGE,
      FormDateRuleset.violationFor(MOTHER_DOB_QUESTION_CODE, bothAnswered, registrationDate),
    )
  }

  @Test
  fun `MD-10 mother dob age is measured against the answered registration date`() {
    // A backdated registration must not drift: this DOB floors to 50 against the answered
    // registration date but to 51 against today, so it only passes if the answered date is used.
    val answeredRegistration = registrationDate.minusYears(1)
    val dob = answeredRegistration.minusYears(FormDateRuleset.MAX_AGE_YEARS)

    val violation = FormDateRuleset.violationFor(
      MOTHER_DOB_QUESTION_CODE,
      answers(
        REGISTRATION_DATE_QUESTION_CODE to answeredRegistration.toString(),
        MOTHER_DOB_QUESTION_CODE to dob.toString(),
      ),
      registrationDate,
    )

    assertNull(violation)
    // Guard the premise: without the answered registration date the same DOB floors to 51.
    assertEquals(
      FormDateRuleset.Violation.AGE_OUT_OF_RANGE,
      violationForMotherDob(dob),
    )
  }

  // --- Infant DOB: path-dependent 0-183 / 0-365 day window, never future ------------------------
  //
  // SRS FR-S-2.3 splits the window by registration path; the backend's create-beneficiary.dto.ts
  // CHILD_AGE_CEILING_DAYS enforces the same split. The form spec CSV's flat "0-183 days" is
  // superseded (see Appendix J of the SRS).
  //
  // This object BOUNDS the picker but deliberately reports NO violation for this field — detection
  // lives in DynamicChildRegistrationViewModel, which has the path-specific messages. ID-6 and ID-7
  // lock that contract so the two layers can't start double-reporting.

  private val infantDob = ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT
  private val pathQuestion = ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING

  private fun infantDobBounds(path: String? = null): FormDateRuleset.Bounds = requireNotNull(
    FormDateRuleset.boundsFor(
      infantDob,
      if (path == null) FormAnswers() else answers(pathQuestion to path),
      registrationDate,
    ),
  )

  @Test
  fun `ID-1 registered-mother path bounds the picker to 183 days`() {
    val bounds = infantDobBounds(ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER)

    assertEquals(
      registrationDate.minusDays(FormDateRuleset.CHILD_AGE_CEILING_DAYS_MOTHER_LINKED),
      bounds.min,
    )
    assertEquals(registrationDate, bounds.max)
  }

  @Test
  fun `ID-2 direct path bounds the picker to 365 days`() {
    val bounds = infantDobBounds(ChildRegistrationQuestionCodes.PATH_DIRECT)

    assertEquals(
      registrationDate.minusDays(FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT),
      bounds.min,
    )
    assertEquals(registrationDate, bounds.max)
  }

  @Test
  fun `ID-3 an unanswered path falls back to the wider window`() {
    // Restricting to 183 before the path is known would block legitimate direct registrations with
    // no visible reason. The ViewModel's gate still catches an out-of-window value afterwards.
    assertEquals(
      registrationDate.minusDays(FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT),
      infantDobBounds().min,
    )
  }

  @Test
  fun `ID-4 an unrecognised path value falls back to the wider window`() {
    // A backend rename of the value_code must degrade to the permissive window, not to null bounds
    // (which would leave the picker wide open) or a crash.
    assertEquals(
      registrationDate.minusDays(FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT),
      infantDobBounds("some_new_path_code_the_backend_added").min,
    )
  }

  @Test
  fun `ID-5 today is selectable and tomorrow is not, on both paths`() {
    // "Should not accept future date" (spec row 6.0). An infant aged 0 days is valid.
    listOf(
      ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
      ChildRegistrationQuestionCodes.PATH_DIRECT,
    ).forEach { path ->
      assertEquals("max must be today for $path", registrationDate, infantDobBounds(path).max)
    }
  }

  @Test
  fun `ID-6 infant dob reports no violation - detection belongs to the ViewModel`() {
    // Contract lock. If this starts failing because a Violation case was added, the ViewModel's
    // INELIGIBLE_MOTHER / INELIGIBLE_DIRECT / DOB_FUTURE gate must be removed in the same change,
    // or the Sakhi sees two errors for one problem.
    listOf(
      ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
      ChildRegistrationQuestionCodes.PATH_DIRECT,
    ).forEach { path ->
      listOf(
        registrationDate.minusDays(30),  // in window on either path
        registrationDate.minusDays(200), // out of window for the mother path
        registrationDate.minusDays(400), // out of window for both
        registrationDate.plusDays(1),    // future
      ).forEach { date ->
        assertNull(
          "expected no ruleset violation for $date on $path",
          FormDateRuleset.violationFor(
            infantDob,
            answers(pathQuestion to path, infantDob to date.toString()),
            registrationDate,
          ),
        )
      }
    }
  }

  @Test
  fun `ID-7 allDatesValid stays true for an out-of-window infant dob`() {
    // Follows from ID-6, asserted separately because allDatesValid is what the submit gate calls:
    // this object must not quietly become a second gate over the eligibility rule.
    val fields = listOf(dateField(infantDob))
    val outOfWindow = answers(
      pathQuestion to ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
      infantDob to registrationDate.minusDays(400).toString(),
    )

    assertTrue(FormDateRuleset.allDatesValid(fields, outOfWindow, registrationDate))
  }

  @Test
  fun `ID-8 the lower bound is inclusive - a DOB on it is exactly the ceiling age in days`() {
    // Guards off-by-one: the oldest date the picker offers must be an age the ViewModel's
    // `ageDays > ceiling` check accepts, not reject the value it just handed out.
    val motherBound = infantDobBounds(ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER).min
    val directBound = infantDobBounds(ChildRegistrationQuestionCodes.PATH_DIRECT).min

    assertEquals(
      FormDateRuleset.CHILD_AGE_CEILING_DAYS_MOTHER_LINKED,
      ChronoUnit.DAYS.between(requireNotNull(motherBound), registrationDate),
    )
    assertEquals(
      FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT,
      ChronoUnit.DAYS.between(requireNotNull(directBound), registrationDate),
    )
  }

  @Test
  fun `ID-9 the client ceilings match the backend's CHILD_AGE_CEILING_DAYS`() {
    // Mirrors arogyasakhi-service create-beneficiary.dto.ts: MOTHER_LINKED 183 / INDEPENDENT 365.
    // Hardcoded on purpose — reading them from the constants would assert nothing. If ARMMAN changes
    // the window, this test is the reminder that BOTH sides move together.
    assertEquals(183L, FormDateRuleset.CHILD_AGE_CEILING_DAYS_MOTHER_LINKED)
    assertEquals(365L, FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT)
  }

  // --- LMP: 31..239 days before the registration date ------------------------------------------

  @Test
  fun `future lmp is a violation`() {
    val violation = FormDateRuleset.violationFor(
      LMP_DATE_QUESTION_CODE,
      answers(LMP_DATE_QUESTION_CODE to registrationDate.plusDays(1).toString()),
      registrationDate,
    )

    assertEquals(FormDateRuleset.Violation.LMP_FUTURE, violation)
  }

  @Test
  fun `lmp at both window bounds is accepted`() {
    assertNull(violationForLmp(registrationDate.minusDays(FormDateRuleset.LMP_MIN_DAYS_BEFORE_REGISTRATION)))
    assertNull(violationForLmp(registrationDate.minusDays(FormDateRuleset.LMP_MAX_DAYS_BEFORE_REGISTRATION)))
  }

  @Test
  fun `lmp one day outside each window bound is rejected`() {
    val tooRecent = registrationDate.minusDays(FormDateRuleset.LMP_MIN_DAYS_BEFORE_REGISTRATION - 1)
    val tooOld = registrationDate.minusDays(FormDateRuleset.LMP_MAX_DAYS_BEFORE_REGISTRATION + 1)

    assertEquals(FormDateRuleset.Violation.LMP_TOO_RECENT, violationForLmp(tooRecent))
    assertEquals(FormDateRuleset.Violation.LMP_TOO_OLD, violationForLmp(tooOld))
  }

  @Test
  fun `lmp window is measured from the answered registration date`() {
    val answeredRegistration = registrationDate.minusDays(10)
    // Exactly on the oldest allowed bound relative to the ANSWERED registration date. Measured
    // against today instead it would be 249 days back — past the 239-day limit — so this only
    // passes if the answered registration date is the reference point.
    val lmp = answeredRegistration.minusDays(FormDateRuleset.LMP_MAX_DAYS_BEFORE_REGISTRATION)

    val violation = FormDateRuleset.violationFor(
      LMP_DATE_QUESTION_CODE,
      answers(
        REGISTRATION_DATE_QUESTION_CODE to answeredRegistration.toString(),
        LMP_DATE_QUESTION_CODE to lmp.toString(),
      ),
      registrationDate,
    )

    assertNull(violation)
    // Guard the premise: without the answered registration date, the same LMP is out of window.
    assertEquals(FormDateRuleset.Violation.LMP_TOO_OLD, violationForLmp(lmp))
  }

  @Test
  fun `a future registration date does not widen the lmp window`() {
    // Guards against one bad answer loosening another: an LMP 250 days before *today* is out of
    // window and must stay out even though the (invalid) registration date would allow it.
    val violation = FormDateRuleset.violationFor(
      LMP_DATE_QUESTION_CODE,
      answers(
        REGISTRATION_DATE_QUESTION_CODE to registrationDate.plusDays(20).toString(),
        LMP_DATE_QUESTION_CODE to registrationDate.minusDays(250).toString(),
      ),
      registrationDate,
    )

    assertEquals(FormDateRuleset.Violation.LMP_TOO_OLD, violation)
  }

  // --- ANC1 completion date (row 42): strictly after LMP, <=5 days after registration ----------

  private val anc1Date = FormDateRuleset.ANC1_DATE_QUESTION_CODE

  @Test
  fun `AD-1 bounds run from the day after LMP to registration date plus 5 days`() {
    val lmp = registrationDate.minusDays(60)
    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(anc1Date, answers(LMP_DATE_QUESTION_CODE to lmp.toString()), registrationDate),
    )

    assertEquals(lmp.plusDays(1), bounds.min)
    assertEquals(registrationDate.plusDays(FormDateRuleset.ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION), bounds.max)
  }

  @Test
  fun `AD-2 with no LMP answered yet the lower bound is left open, not guessed`() {
    val bounds = requireNotNull(FormDateRuleset.boundsFor(anc1Date, FormAnswers(), registrationDate))

    assertNull(bounds.min)
    assertEquals(registrationDate.plusDays(FormDateRuleset.ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION), bounds.max)
  }

  @Test
  fun `AD-3 a date on or before LMP is rejected - must be strictly after`() {
    val lmp = registrationDate.minusDays(60)

    assertEquals(FormDateRuleset.Violation.ANC1_DATE_NOT_AFTER_LMP, violationForAnc1(lmp, lmp))
    assertEquals(
      FormDateRuleset.Violation.ANC1_DATE_NOT_AFTER_LMP,
      violationForAnc1(lmp, lmp.minusDays(1)),
    )
    // One day after LMP is the earliest accepted value.
    assertNull(violationForAnc1(lmp, lmp.plusDays(1)))
  }

  @Test
  fun `AD-4 registration date plus 5 days is accepted, plus 6 days is rejected`() {
    val lmp = registrationDate.minusDays(60)
    val atBound = registrationDate.plusDays(FormDateRuleset.ANC1_DATE_MAX_DAYS_AFTER_REGISTRATION)

    assertNull(violationForAnc1(lmp, atBound))
    assertEquals(
      FormDateRuleset.Violation.ANC1_DATE_TOO_LATE,
      violationForAnc1(lmp, atBound.plusDays(1)),
    )
  }

  @Test
  fun `AD-5 blank and unparseable values are not date violations`() {
    assertNull(FormDateRuleset.violationFor(anc1Date, FormAnswers(), registrationDate))
    assertNull(violationForRaw(anc1Date, ""))
    assertNull(violationForRaw(anc1Date, "not-a-date"))
  }

  @Test
  fun `AD-6 allDatesValid rejects an anc1 date on or before LMP`() {
    val fields = listOf(dateField(LMP_DATE_QUESTION_CODE), dateField(anc1Date))
    val lmp = registrationDate.minusDays(60)
    val valid = answers(
      LMP_DATE_QUESTION_CODE to lmp.toString(),
      anc1Date to lmp.plusDays(10).toString(),
    )

    assertTrue(FormDateRuleset.allDatesValid(fields, valid, registrationDate))

    val onLmp = valid.withSingleValue(anc1Date, lmp.toString())
    assertFalse(FormDateRuleset.allDatesValid(fields, onLmp, registrationDate))
  }

  private fun violationForAnc1(lmp: LocalDate, value: LocalDate) = FormDateRuleset.violationFor(
    anc1Date,
    answers(LMP_DATE_QUESTION_CODE to lmp.toString(), anc1Date to value.toString()),
    registrationDate,
  )

  // --- Registration date: never in the future --------------------------------------------------

  @Test
  fun `registration date today is accepted and tomorrow is rejected`() {
    assertNull(
      FormDateRuleset.violationFor(
        REGISTRATION_DATE_QUESTION_CODE,
        answers(REGISTRATION_DATE_QUESTION_CODE to registrationDate.toString()),
        registrationDate,
      ),
    )
    assertEquals(
      FormDateRuleset.Violation.REGISTRATION_DATE_IN_FUTURE,
      FormDateRuleset.violationFor(
        REGISTRATION_DATE_QUESTION_CODE,
        answers(REGISTRATION_DATE_QUESTION_CODE to registrationDate.plusDays(1).toString()),
        registrationDate,
      ),
    )
  }

  @Test
  fun `registration date bounds cap at today`() {
    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(REGISTRATION_DATE_QUESTION_CODE, FormAnswers(), registrationDate),
    )

    assertEquals(registrationDate, bounds.max)
    assertNull(bounds.min)
  }

  @Test
  fun `the corrected registration date spelling is ruled identically`() {
    // MOTHER_REGISTRATION v3 renamed the question to `registration_date`; every rule must follow the
    // rename, not silently stop matching.
    assertEquals(
      FormDateRuleset.Violation.REGISTRATION_DATE_IN_FUTURE,
      FormDateRuleset.violationFor(
        REGISTRATION_DATE_QUESTION_CODE_CORRECTED,
        answers(REGISTRATION_DATE_QUESTION_CODE_CORRECTED to registrationDate.plusDays(1).toString()),
        registrationDate,
      ),
    )
    assertEquals(
      registrationDate,
      FormDateRuleset
        .boundsFor(REGISTRATION_DATE_QUESTION_CODE_CORRECTED, FormAnswers(), registrationDate)
        ?.max,
    )
  }

  @Test
  fun `an answered corrected-spelling registration date is the LMP reference date`() {
    val answeredRegistration = registrationDate.minusDays(10)

    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(
        LMP_DATE_QUESTION_CODE,
        answers(REGISTRATION_DATE_QUESTION_CODE_CORRECTED to answeredRegistration.toString()),
        registrationDate,
      ),
    )

    assertEquals(
      answeredRegistration.minusDays(FormDateRuleset.LMP_MIN_DAYS_BEFORE_REGISTRATION),
      bounds.max,
    )
  }

  // --- Non-violations and unruled fields -------------------------------------------------------

  @Test
  fun `blank and unparseable values are not date violations`() {
    // Blank is the required-field gate's job; an unparseable value isn't a *range* problem, and
    // reporting it as one would name the wrong cause.
    assertNull(FormDateRuleset.violationFor(DOB_QUESTION_CODE, FormAnswers(), registrationDate))
    assertNull(violationForRaw(DOB_QUESTION_CODE, ""))
    assertNull(violationForRaw(DOB_QUESTION_CODE, "not-a-date"))
    assertNull(violationForRaw(LMP_DATE_QUESTION_CODE, "28-07-2026"))
  }

  @Test
  fun `a date field with no rule is unconstrained`() {
    // Was `date_of_birth_of_infant` until that field gained path-aware bounds; any code with no
    // entry in the ruleset does. `date_of_last_visit_12months` is a real one (Infant Registration
    // row 8.0, DoB+365days) — computed, not picked, so it needs no bounds.
    val code = "date_of_last_visit_12months"

    assertNull(FormDateRuleset.boundsFor(code, FormAnswers(), registrationDate))
    assertNull(violationForRaw(code, registrationDate.plusYears(5).toString()))
  }

  // --- Gate helper -----------------------------------------------------------------------------

  @Test
  fun `allDatesValid fails when any date field breaks its rule`() {
    val fields = listOf(dateField(DOB_QUESTION_CODE), dateField(LMP_DATE_QUESTION_CODE))
    val valid = answers(
      DOB_QUESTION_CODE to registrationDate.minusYears(25).toString(),
      LMP_DATE_QUESTION_CODE to registrationDate.minusDays(60).toString(),
    )

    assertTrue(FormDateRuleset.allDatesValid(fields, valid, registrationDate))

    val futureDob = valid.withSingleValue(DOB_QUESTION_CODE, registrationDate.plusDays(1).toString())
    assertFalse(FormDateRuleset.allDatesValid(fields, futureDob, registrationDate))
  }

  @Test
  fun `allDatesValid ignores non-date fields`() {
    val numberField = FormFieldSchema(
      label = "Gravida",
      required = true,
      inputTypeRaw = "number",
      questionCode = DOB_QUESTION_CODE, // same code, wrong input type — must be skipped
    )

    val futureDob = answers(DOB_QUESTION_CODE to registrationDate.plusYears(1).toString())
    assertTrue(FormDateRuleset.allDatesValid(listOf(numberField), futureDob, registrationDate))
  }

  // --- Td dose dates (row 44): Td-2 after Td-1, Booster after Td-2, none in the future --------
  //
  // Added 2026-08-06 once ARMMAN's schema change gave each dose a real `date` field
  // (TdDoseQuestionCodes) — previously there was nowhere to store these at all.

  private val td1 = TdDoseQuestionCodes.TD_1_DATE_QUESTION_CODE
  private val td2 = TdDoseQuestionCodes.TD_2_DATE_QUESTION_CODE
  private val tdBooster = TdDoseQuestionCodes.TD_BOOSTER_DATE_QUESTION_CODE

  @Test
  fun `TD-1 today is accepted and tomorrow is rejected for every dose date`() {
    listOf(td1, td2, tdBooster).forEach { code ->
      assertNull("expected today to be accepted for $code", violationForRaw(code, registrationDate.toString()))
      assertEquals(
        "expected tomorrow to be rejected for $code",
        FormDateRuleset.Violation.TD_DATE_IN_FUTURE,
        violationForRaw(code, registrationDate.plusDays(1).toString()),
      )
    }
  }

  @Test
  fun `TD-2 td-1 alone has no ordering rule to break`() {
    assertNull(violationForRaw(td1, registrationDate.minusDays(400).toString()))
  }

  @Test
  fun `TD-3 td-2 must be strictly after td-1`() {
    val td1Date = registrationDate.minusDays(30)

    assertEquals(
      FormDateRuleset.Violation.TD_2_NOT_AFTER_TD_1,
      violationForTd2(td1Date, td1Date),
    )
    assertEquals(
      FormDateRuleset.Violation.TD_2_NOT_AFTER_TD_1,
      violationForTd2(td1Date, td1Date.minusDays(1)),
    )
    assertNull(violationForTd2(td1Date, td1Date.plusDays(1)))
  }

  @Test
  fun `TD-4 td-2 with no td-1 answered yet has nothing to compare against`() {
    assertNull(FormDateRuleset.violationFor(td2, answers(td2 to registrationDate.toString()), registrationDate))
  }

  @Test
  fun `TD-5 td-booster must be strictly after td-2`() {
    val td2Date = registrationDate.minusDays(10)

    assertEquals(
      FormDateRuleset.Violation.TD_BOOSTER_NOT_AFTER_TD_2,
      violationForTdBooster(td2Date, td2Date),
    )
    assertNull(violationForTdBooster(td2Date, td2Date.plusDays(1)))
  }

  @Test
  fun `TD-6 td-2 bounds start the day after the answered td-1 date and cap at today`() {
    val td1Date = registrationDate.minusDays(30)
    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(td2, answers(td1 to td1Date.toString()), registrationDate),
    )

    assertEquals(td1Date.plusDays(1), bounds.min)
    assertEquals(registrationDate, bounds.max)
  }

  @Test
  fun `TD-7 td-2 bounds are open at the bottom until td-1 is answered`() {
    val bounds = requireNotNull(FormDateRuleset.boundsFor(td2, FormAnswers(), registrationDate))

    assertNull(bounds.min)
    assertEquals(registrationDate, bounds.max)
  }

  @Test
  fun `TD-8 td-booster bounds start the day after the answered td-2 date and cap at today`() {
    val td2Date = registrationDate.minusDays(10)
    val bounds = requireNotNull(
      FormDateRuleset.boundsFor(tdBooster, answers(td2 to td2Date.toString()), registrationDate),
    )

    assertEquals(td2Date.plusDays(1), bounds.min)
    assertEquals(registrationDate, bounds.max)
  }

  @Test
  fun `TD-9 allDatesValid rejects a td-2 date on or before td-1`() {
    val fields = listOf(dateField(td1), dateField(td2), dateField(tdBooster))
    val td1Date = registrationDate.minusDays(30)
    val valid = answers(td1 to td1Date.toString(), td2 to td1Date.plusDays(5).toString())

    assertTrue(FormDateRuleset.allDatesValid(fields, valid, registrationDate))

    val onTd1 = valid.withSingleValue(td2, td1Date.toString())
    assertFalse(FormDateRuleset.allDatesValid(fields, onTd1, registrationDate))
  }

  @Test
  fun `TD-10 blank and unparseable td dose dates are not date violations`() {
    listOf(td1, td2, tdBooster).forEach { code ->
      assertNull(FormDateRuleset.violationFor(code, FormAnswers(), registrationDate))
      assertNull(violationForRaw(code, ""))
      assertNull(violationForRaw(code, "not-a-date"))
    }
  }

  private fun violationForTd2(td1Date: LocalDate, td2Date: LocalDate) = FormDateRuleset.violationFor(
    td2,
    answers(td1 to td1Date.toString(), td2 to td2Date.toString()),
    registrationDate,
  )

  private fun violationForTdBooster(td2Date: LocalDate, boosterDate: LocalDate) = FormDateRuleset.violationFor(
    tdBooster,
    answers(td2 to td2Date.toString(), tdBooster to boosterDate.toString()),
    registrationDate,
  )

  private fun violationForDob(date: LocalDate) = violationForRaw(DOB_QUESTION_CODE, date.toString())

  private fun violationForMotherDob(date: LocalDate) =
    violationForRaw(MOTHER_DOB_QUESTION_CODE, date.toString())

  private fun violationForLmp(date: LocalDate) =
    violationForRaw(LMP_DATE_QUESTION_CODE, date.toString())

  private fun violationForRaw(questionCode: String, raw: String) =
    FormDateRuleset.violationFor(questionCode, answers(questionCode to raw), registrationDate)
}
