package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FormComputedFieldEvaluatorTest {

  @Test
  fun `EDD_FROM_LMP adds 280 days to lmp_date`() {
    val answers = FormAnswers(singleValues = mapOf("lmp_date" to "2026-05-01"))

    val edd = FormComputedFieldEvaluator.compute("EDD_FROM_LMP", answers, LocalDate.of(2026, 7, 20))

    assertEquals(LocalDate.of(2026, 5, 1).plusDays(280).toString(), edd)
  }

  @Test
  fun `GESTATIONAL_AGE_AT_REGISTRATION is floor weeks between lmp and registration`() {
    val answers = FormAnswers(singleValues = mapOf("lmp_date" to "2026-05-01"))

    val ga = FormComputedFieldEvaluator.compute(
      "GESTATIONAL_AGE_AT_REGISTRATION",
      answers,
      LocalDate.of(2026, 7, 20),
    )

    assertEquals("11", ga)
  }

  @Test
  fun `missing lmp_date yields null for both LMP-derived fields`() {
    assertNull(FormComputedFieldEvaluator.compute("EDD_FROM_LMP", FormAnswers(), LocalDate.now()))
    assertNull(
      FormComputedFieldEvaluator.compute("GESTATIONAL_AGE_AT_REGISTRATION", FormAnswers(), LocalDate.now()),
    )
  }

  @Test
  fun `UNIQUE_ID is deliberately unimplemented (formula not yet confirmed)`() {
    assertNull(FormComputedFieldEvaluator.compute("UNIQUE_ID", FormAnswers(), LocalDate.now()))
  }

  @Test
  fun `AGE_FROM_DOB is whole years between dob and registration date`() {
    val answers = FormAnswers(singleValues = mapOf(DOB_QUESTION_CODE to "2009-07-22"))

    val age = FormComputedFieldEvaluator.compute(COMPUTED_AGE_FROM_DOB, answers, LocalDate.of(2026, 7, 22))

    assertEquals("17", age)
  }

  @Test
  fun `AGE_FROM_DOB is floored, not rounded, before the birthday this year`() {
    val answers = FormAnswers(singleValues = mapOf(DOB_QUESTION_CODE to "2009-07-22"))

    // One day before the 17th birthday relative to registration date — still 16, not 17.
    val age = FormComputedFieldEvaluator.compute(COMPUTED_AGE_FROM_DOB, answers, LocalDate.of(2026, 7, 21))

    assertEquals("16", age)
  }

  @Test
  fun `missing dob yields null for AGE_FROM_DOB`() {
    assertNull(FormComputedFieldEvaluator.compute(COMPUTED_AGE_FROM_DOB, FormAnswers(), LocalDate.now()))
  }

  @Test
  fun `COMPUTED_TRIMESTER is 1st before 14 weeks`() {
    // Registration exactly 13 weeks after LMP -> gestational age 13 -> 1st trimester.
    val registrationDate = LocalDate.of(2026, 7, 20)
    val answers = FormAnswers(singleValues = mapOf(LMP_DATE_QUESTION_CODE to registrationDate.minusWeeks(13).toString()))

    assertEquals("1", FormComputedFieldEvaluator.compute(COMPUTED_TRIMESTER, answers, registrationDate))
  }

  @Test
  fun `COMPUTED_TRIMESTER is 2nd from 14 up to and including 27 weeks`() {
    val registrationDate = LocalDate.of(2026, 7, 20)
    val lowerBound = FormAnswers(singleValues = mapOf(LMP_DATE_QUESTION_CODE to registrationDate.minusWeeks(14).toString()))
    val upperBound = FormAnswers(singleValues = mapOf(LMP_DATE_QUESTION_CODE to registrationDate.minusWeeks(27).toString()))

    assertEquals("2", FormComputedFieldEvaluator.compute(COMPUTED_TRIMESTER, lowerBound, registrationDate))
    assertEquals("2", FormComputedFieldEvaluator.compute(COMPUTED_TRIMESTER, upperBound, registrationDate))
  }

  @Test
  fun `COMPUTED_TRIMESTER is 3rd from 28 weeks onward`() {
    val registrationDate = LocalDate.of(2026, 7, 20)
    val answers = FormAnswers(singleValues = mapOf(LMP_DATE_QUESTION_CODE to registrationDate.minusWeeks(28).toString()))

    assertEquals("3", FormComputedFieldEvaluator.compute(COMPUTED_TRIMESTER, answers, registrationDate))
  }

  @Test
  fun `missing lmp_date yields null for COMPUTED_TRIMESTER`() {
    assertNull(FormComputedFieldEvaluator.compute(COMPUTED_TRIMESTER, FormAnswers(), LocalDate.now()))
  }

  @Test
  fun `unrecognized computedFrom returns null`() {
    assertNull(FormComputedFieldEvaluator.compute("SOME_FUTURE_FORMULA", FormAnswers(), LocalDate.now()))
  }

  // --- isAgeFromDobReadOnly (CR-037: age is read-only only while DOB is answered) ---------------

  @Test
  fun `isAgeFromDobReadOnly is true once date_of_birth is answered`() {
    val answers = FormAnswers(singleValues = mapOf(DOB_QUESTION_CODE to "2000-01-01"))

    assertTrue(isAgeFromDobReadOnly(answers))
  }

  @Test
  fun `isAgeFromDobReadOnly is false when date_of_birth is blank or unanswered`() {
    assertFalse(isAgeFromDobReadOnly(FormAnswers()))
    assertFalse(isAgeFromDobReadOnly(FormAnswers(singleValues = mapOf(DOB_QUESTION_CODE to ""))))
  }
}
