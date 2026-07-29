package org.armman.sakhi.data.enrollment

import org.junit.Assert.assertEquals
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
}
