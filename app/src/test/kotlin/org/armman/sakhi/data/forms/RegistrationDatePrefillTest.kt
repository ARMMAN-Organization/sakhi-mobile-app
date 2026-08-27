package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Spec row 13 ("Automatically popup todays date") for both registration flows, plus the two ways the
 * previous mother-only implementation broke: a renamed `question_code` and a resumed draft.
 */
class RegistrationDatePrefillTest {

  private val today = LocalDate.of(2026, 7, 31)

  private fun field(questionCode: String, inputType: String = "date") = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = inputType,
    questionCode = questionCode,
  )

  @Test
  fun `fills today under the typo spelling the schema declares`() {
    val answers = RegistrationDatePrefill.apply(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE)),
      FormAnswers(),
      today,
    )

    assertEquals("2026-07-31", answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
    assertNull(answers.valueOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED))
  }

  @Test
  fun `fills today under the corrected spelling the schema declares`() {
    val answers = RegistrationDatePrefill.apply(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE_CORRECTED)),
      FormAnswers(),
      today,
    )

    assertEquals("2026-07-31", answers.valueOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED))
    // Writing the code the schema does NOT declare would 422 the /submissions call.
    assertNull(answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
  }

  @Test
  fun `falls back to the typo code when the schema declares no registration date field`() {
    val answers = RegistrationDatePrefill.apply(listOf(field("first_name", "text")), FormAnswers(), today)

    assertEquals("2026-07-31", answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
  }

  @Test
  fun `keeps an already answered date so a resumed draft does not jump to today`() {
    val started = FormAnswers(singleValues = mapOf(REGISTRATION_DATE_QUESTION_CODE to "2026-07-20"))

    val answers = RegistrationDatePrefill.apply(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE)),
      started,
      today,
    )

    assertEquals("2026-07-20", answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
  }

  @Test
  fun `does not double write when the draft answered the other spelling`() {
    val started =
      FormAnswers(singleValues = mapOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED to "2026-07-20"))

    val answers = RegistrationDatePrefill.apply(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE)),
      started,
      today,
    )

    assertEquals("2026-07-20", answers.valueOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED))
    assertNull(answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
  }

  @Test
  fun `registrationDateAnswer reads either spelling`() {
    assertEquals(
      "2026-07-31",
      FormAnswers(singleValues = mapOf(REGISTRATION_DATE_QUESTION_CODE to "2026-07-31"))
        .registrationDateAnswer(),
    )
    assertEquals(
      "2026-07-31",
      FormAnswers(singleValues = mapOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED to "2026-07-31"))
        .registrationDateAnswer(),
    )
    assertNull(FormAnswers().registrationDateAnswer())
  }
}
