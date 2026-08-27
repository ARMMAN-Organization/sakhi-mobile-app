package org.armman.sakhi.ui.forms

import org.armman.sakhi.data.forms.AGE_FROM_DOB_QUESTION_CODES
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.TdDoseQuestionCodes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredFieldMarkerTest {

  private fun field(
    questionCode: String = "mother_name",
    inputTypeRaw: String = "text",
    required: Boolean = true,
    computedFrom: String? = null,
  ) = FormFieldSchema(
    label = "Mother's name",
    required = required,
    inputTypeRaw = inputTypeRaw,
    questionCode = questionCode,
    computedFrom = computedFrom,
  )

  // --- shown ---------------------------------------------------------------

  @Test
  fun `shows marker for a required text field`() {
    assertTrue(RequiredFieldMarker.isShownFor(field()))
  }

  @Test
  fun `shows marker for every entry input type when required`() {
    val entryTypes = listOf("text", "text_geo", "number", "date", "select", "radio", "multiselect", "multiselect_date")
    entryTypes.forEach { type ->
      assertTrue(type, RequiredFieldMarker.isShownFor(field(inputTypeRaw = type)))
    }
  }

  // --- not shown -----------------------------------------------------------

  @Test
  fun `hides marker when field is optional`() {
    assertFalse(RequiredFieldMarker.isShownFor(field(required = false)))
  }

  @Test
  fun `hides marker for a computed field even when required`() {
    // Rendered read-only via AppReadOnlyField — the Sakhi cannot fill it in.
    assertFalse(RequiredFieldMarker.isShownFor(field(questionCode = "edd", computedFrom = "EDD_FROM_LMP")))
  }

  @Test
  fun `hides marker for DOB-derived age fields not yet flagged as computed by the schema`() {
    AGE_FROM_DOB_QUESTION_CODES.forEach { code ->
      assertFalse(code, RequiredFieldMarker.isShownFor(field(questionCode = code, inputTypeRaw = "number")))
    }
  }

  @Test
  fun `hides marker for media and image fields whose label is a button caption`() {
    assertFalse(RequiredFieldMarker.isShownFor(field(questionCode = "consent_audio", inputTypeRaw = "media")))
    assertFalse(RequiredFieldMarker.isShownFor(field(questionCode = "consent_photo", inputTypeRaw = "image")))
  }

  @Test
  fun `hides marker for an unsupported input type`() {
    // That branch renders an "app update needed" error line, not a labelled field.
    assertFalse(RequiredFieldMarker.isShownFor(field(inputTypeRaw = "signature_pad")))
  }

  @Test
  fun `optional field of an excluded type is still unmarked`() {
    assertFalse(RequiredFieldMarker.isShownFor(field(inputTypeRaw = "media", required = false)))
  }

  // --- Td dose date fields: required:false in the schema, but conditionally required client-side -

  @Test
  fun `shows marker for a Td-dose date field even though the schema says required false`() {
    TdDoseQuestionCodes.CONDITIONALLY_REQUIRED_DATE_QUESTION_CODES.forEach { code ->
      assertTrue(
        code,
        RequiredFieldMarker.isShownFor(field(questionCode = code, inputTypeRaw = "date", required = false)),
      )
    }
  }

  @Test
  fun `a Td-dose date field is unaffected if the schema ever does mark it required`() {
    TdDoseQuestionCodes.CONDITIONALLY_REQUIRED_DATE_QUESTION_CODES.forEach { code ->
      assertTrue(
        code,
        RequiredFieldMarker.isShownFor(field(questionCode = code, inputTypeRaw = "date", required = true)),
      )
    }
  }

  @Test
  fun `an ordinary date field with the same input type stays governed by its own required flag`() {
    assertFalse(RequiredFieldMarker.isShownFor(field(questionCode = "date_of_birth", inputTypeRaw = "date", required = false)))
  }
}
