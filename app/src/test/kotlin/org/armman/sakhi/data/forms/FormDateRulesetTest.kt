package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Rules under test come from the `Registration_PW_D` form-spec tab — DOB age 10-50 (row 23), LMP
 * more than 30 and less than 240 days before registration (row 7), registration date never in the
 * future (row 13).
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
    val code = "date_of_birth_of_infant"

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

  private fun violationForDob(date: LocalDate) = violationForRaw(DOB_QUESTION_CODE, date.toString())

  private fun violationForLmp(date: LocalDate) =
    violationForRaw(LMP_DATE_QUESTION_CODE, date.toString())

  private fun violationForRaw(questionCode: String, raw: String) =
    FormDateRuleset.violationFor(questionCode, answers(questionCode to raw), registrationDate)
}
