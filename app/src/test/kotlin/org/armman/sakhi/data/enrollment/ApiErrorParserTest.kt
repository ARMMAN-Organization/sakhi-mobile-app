package org.armman.sakhi.data.enrollment

import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [ApiErrorParser]: the backend's error envelope for `POST /beneficiaries`, whose two live
 * shapes (`400 VALIDATION_ERROR` with `fieldErrors`, `422 UNPROCESSABLE` without) decide whether a
 * failure can be shown inline per-field or only as a page-level banner. Bodies mirror real
 * responses captured in `api-calls.jsonl`.
 */
class ApiErrorParserTest {

  @Test
  fun `parses a 400 VALIDATION_ERROR with fieldErrors`() {
    val body = """
      {"success":false,"message":"pii.firstName: String must contain at least 1 character(s)",
      "errorCode":"VALIDATION_ERROR",
      "fieldErrors":{"pii.firstName":"String must contain at least 1 character(s)",
      "pii.lastName":"String must contain at least 1 character(s)"}}
    """.trimIndent()

    val error = ApiErrorParser.parse(body)

    assertEquals("VALIDATION_ERROR", error.errorCode)
    assertEquals(2, error.fieldErrors.size)
    assertEquals("String must contain at least 1 character(s)", error.fieldErrors["pii.firstName"])
    assertEquals("String must contain at least 1 character(s)", error.fieldErrors["pii.lastName"])
  }

  @Test
  fun `parses a 422 UNPROCESSABLE with no fieldErrors — message kept, map empty`() {
    val body = """
      {"success":false,"message":"pii.phcId does not refer to a known geography unit.",
      "errorCode":"UNPROCESSABLE","traceId":"abc123"}
    """.trimIndent()

    val error = ApiErrorParser.parse(body)

    assertEquals("UNPROCESSABLE", error.errorCode)
    assertEquals("pii.phcId does not refer to a known geography unit.", error.message)
    assertTrue(error.fieldErrors.isEmpty())
  }

  @Test
  fun `an explicitly empty fieldErrors object parses to an empty map, not a crash`() {
    val body = """{"errorCode":"VALIDATION_ERROR","message":"nothing specific","fieldErrors":{}}"""

    val error = ApiErrorParser.parse(body)

    assertEquals("VALIDATION_ERROR", error.errorCode)
    assertTrue(error.fieldErrors.isEmpty())
  }

  @Test
  fun `a null or blank body degrades to an empty error, never throws`() {
    listOf(null, "", "   ").forEach { body ->
      val error = ApiErrorParser.parse(body)
      assertNull(error.errorCode)
      assertTrue(error.fieldErrors.isEmpty())
    }
  }

  @Test
  fun `malformed non-JSON body falls back to the raw text as the message`() {
    val error = ApiErrorParser.parse("502 Bad Gateway")

    assertEquals("502 Bad Gateway", error.message)
    assertNull(error.errorCode)
    assertTrue(error.fieldErrors.isEmpty())
  }

  // ---------------------------------------------------------------------------------------------
  // The submissions endpoint's 422: `fieldErrors` values are ARRAYS, not strings.
  //
  // Real body from POST /forms/CHILD_REGISTRATION/submissions. `fieldErrors` was typed
  // Map<String, String>, so Gson threw on the array value, parse() degraded to `message = body`,
  // and the Sakhi was shown the whole raw JSON envelope — traceId and all — in the error banner.
  // ---------------------------------------------------------------------------------------------

  private val validatorBody = """
    {"success":false,"message":"Submission failed validation.","errorCode":"UNPROCESSABLE",
    "traceId":"f511c789986dc2bcc54c19677bf287ed",
    "fieldErrors":{"violations":["Missing required field: mother_beneficiary_id"]}}
  """.trimIndent()

  @Test
  fun `a fieldErrors array is parsed into violations instead of breaking the parse`() {
    val error = ApiErrorParser.parse(validatorBody)

    assertEquals(listOf("Missing required field: mother_beneficiary_id"), error.violations)
    assertEquals("Submission failed validation.", error.message)
    assertEquals("UNPROCESSABLE", error.errorCode)
    // Validator messages name a question_code, not a DTO path — not field-attributable.
    assertTrue(error.fieldErrors.isEmpty())
  }

  @Test
  fun `the raw envelope never becomes the message when the body is parseable`() {
    val error = ApiErrorParser.parse(validatorBody)

    assertFalse(error.message!!.contains("traceId"))
    assertFalse(error.message!!.contains("{"))
  }

  @Test
  fun `string and array valued fieldErrors entries are split, not conflated`() {
    val both = """
      {"message":"Validation failed",
      "fieldErrors":{"pii.phone":"Mobile number is invalid","violations":["Some validator note"]}}
    """.trimIndent()

    val error = ApiErrorParser.parse(both)

    assertEquals(mapOf("pii.phone" to "Mobile number is invalid"), error.fieldErrors)
    assertEquals(listOf("Some validator note"), error.violations)
  }

  @Test
  fun `non-primitive entries are dropped rather than stringified onto the screen`() {
    val error = ApiErrorParser.parse("""{"fieldErrors":{"violations":[{"a":"b"}],"other":{"c":"d"}}}""")

    assertTrue(error.violations.isEmpty())
    assertTrue(error.fieldErrors.isEmpty())
  }

  @Test
  fun `the banner sentence is the violation, not the generic envelope message`() {
    val error = ApiErrorParser.parse(validatorBody)

    assertEquals(
      "Missing required field: mother_beneficiary_id",
      SubmitErrorCopy.forApiError(error.message, error.fieldErrors, error.violations),
    )
  }

  @Test
  fun `per-field errors still win over violations when both are present`() {
    val both = """
      {"message":"Validation failed",
      "fieldErrors":{"pii.phone":"Mobile number is invalid","violations":["Some validator note"]}}
    """.trimIndent()
    val error = ApiErrorParser.parse(both)

    assertEquals(
      "Mobile number is invalid",
      SubmitErrorCopy.forApiError(error.message, error.fieldErrors, error.violations),
    )
  }

  @Test
  fun `an empty violations array falls back to the envelope message`() {
    val error = ApiErrorParser.parse("""{"message":"Submission failed.","fieldErrors":{"violations":[]}}""")

    assertEquals(
      "Submission failed.",
      SubmitErrorCopy.forApiError(error.message, error.fieldErrors, error.violations),
    )
  }
}
