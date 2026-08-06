package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubmitErrorCopyTest {

  @Test
  fun `field errors win and are humanized`() {
    val message = SubmitErrorCopy.forApiError(
      message = "motherDetails.lmpDate: lmpDate cannot be in the future",
      fieldErrors = mapOf("motherDetails.lmpDate" to "lmpDate cannot be in the future"),
    )

    assertEquals("LMP date cannot be in the future", message)
  }

  @Test
  fun `the dotted DTO path prefix is stripped from the envelope message`() {
    val message = SubmitErrorCopy.forApiError(
      message = "motherDetails.lmpDate: lmpDate cannot be in the future",
      fieldErrors = emptyMap(),
    )

    assertEquals("LMP date cannot be in the future", message)
  }

  @Test
  fun `several field errors are listed one per line, without duplicates`() {
    val message = SubmitErrorCopy.forApiError(
      message = null,
      fieldErrors = linkedMapOf(
        "pii.firstName" to "firstName is required",
        "pii.lastName" to "lastName is required",
        "pii.phone" to "firstName is required",
      ),
    )

    assertEquals("First name is required\nLast name is required", message)
  }

  @Test
  fun `a raw response body is never shown`() {
    // ApiErrorParser hands back the whole body as `message` when the response isn't the expected
    // envelope — exactly the JSON dump this class exists to keep off the screen.
    val body = """{"success":false,"message":"boom","traceId":"5c42c1e5"}"""

    assertEquals(SubmitErrorCopy.GENERIC, SubmitErrorCopy.forApiError(body, emptyMap()))
    assertNull(SubmitErrorCopy.humanize(body))
    assertNull(SubmitErrorCopy.humanize("<html><body>502 Bad Gateway</body></html>"))
  }

  @Test
  fun `null or blank falls back to the generic sentence`() {
    assertEquals(SubmitErrorCopy.GENERIC, SubmitErrorCopy.forApiError(null, emptyMap()))
    assertEquals(SubmitErrorCopy.GENERIC, SubmitErrorCopy.forApiError("   ", emptyMap()))
    assertNull(SubmitErrorCopy.humanize(null))
  }

  @Test
  fun `an unrecognised field token keeps the backend wording, only the path goes`() {
    assertEquals(
      "someNewField must be provided",
      SubmitErrorCopy.humanize("pii.someNewField: someNewField must be provided"),
    )
  }

  @Test
  fun `a message with no path prefix is passed through untouched`() {
    assertEquals(
      "A possible duplicate beneficiary already exists",
      SubmitErrorCopy.humanize("A possible duplicate beneficiary already exists"),
    )
    // Free-text backend messages must not be cosmetically rewritten.
    assertEquals("too many", SubmitErrorCopy.humanize("too many"))
  }

  @Test
  fun `duplicate 409 detail keys are never rendered as field errors`() {
    // The re-enrolment 409 puts machine-readable detail under fieldErrors. Before this filter the
    // Sakhi was shown "RE_ENROLLMENT" and "Resubmit with acknowledgeDuplicate: true…" verbatim.
    val details = mapOf(
      "reason" to "RE_ENROLLMENT",
      "existingBeneficiaryId" to "11111111-2222-3333-4444-555555555555",
      "resolution" to "Resubmit with acknowledgeDuplicate: true to enroll a new pregnancy.",
    )

    assertEquals(SubmitErrorCopy.GENERIC, SubmitErrorCopy.forApiError(null, details))
  }

  @Test
  fun `a real field error still wins even when duplicate detail keys are present alongside it`() {
    val mixed = mapOf(
      "reason" to "RE_ENROLLMENT",
      "pii.firstName" to "First name is required",
    )

    assertEquals("First name is required", SubmitErrorCopy.forApiError(null, mixed))
  }
}
