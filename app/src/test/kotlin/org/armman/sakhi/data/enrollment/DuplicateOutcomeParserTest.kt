package org.armman.sakhi.data.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuplicateOutcomeParser] against the real `409` envelopes the beneficiary service produces (see
 * `beneficiary.duplicate-detection.ts`), plus the malformed shapes it must survive.
 *
 * The bodies are parsed through [ApiErrorParser] rather than hand-built [ApiError]s, so a change to
 * how the envelope is read breaks these tests too — the two are only useful together.
 */
class DuplicateOutcomeParserTest {

  private fun outcomeOf(body: String) = DuplicateOutcomeParser.parse(ApiErrorParser.parse(body))

  @Test
  fun `re-enrolment envelope becomes a new pregnancy prompt carrying the earlier case id`() {
    val body = """
      {"success":false,"message":"A previous record exists for this beneficiary. Is this a new pregnancy?",
       "errorCode":"CONFLICT","traceId":"t-1",
       "fieldErrors":{"reason":"RE_ENROLLMENT","existingBeneficiaryId":"11111111-2222-3333-4444-555555555555",
       "resolution":"Resubmit with acknowledgeDuplicate: true to enroll a new pregnancy."}}
    """.trimIndent()

    val outcome = outcomeOf(body)

    assertEquals(
      DuplicateOutcome.NewPregnancyPrompt("11111111-2222-3333-4444-555555555555"),
      outcome,
    )
  }

  @Test
  fun `hard duplicate envelope with no fieldErrors is blocked`() {
    val body = """
      {"success":false,
       "message":"A possible duplicate beneficiary already exists (beneficiaryId: abc).",
       "errorCode":"CONFLICT","traceId":"t-2"}
    """.trimIndent()

    assertEquals(DuplicateOutcome.HardDuplicate, outcomeOf(body))
  }

  @Test
  fun `re-enrolment marker without an existing beneficiary id degrades to blocked`() {
    // Linking is the whole point of FR-S-2.5; an unlinked "new pregnancy" would silently break the
    // woman's history, so blocking is the safer failure.
    val body = """
      {"success":false,"message":"…","errorCode":"CONFLICT","traceId":"t-3",
       "fieldErrors":{"reason":"RE_ENROLLMENT"}}
    """.trimIndent()

    assertEquals(DuplicateOutcome.HardDuplicate, outcomeOf(body))
  }

  @Test
  fun `blank existing beneficiary id degrades to blocked`() {
    val body = """
      {"success":false,"message":"…","errorCode":"CONFLICT","traceId":"t-4",
       "fieldErrors":{"reason":"RE_ENROLLMENT","existingBeneficiaryId":"   "}}
    """.trimIndent()

    assertEquals(DuplicateOutcome.HardDuplicate, outcomeOf(body))
  }

  @Test
  fun `an unrecognised reason is blocked rather than guessed at`() {
    val body = """
      {"success":false,"message":"…","errorCode":"CONFLICT","traceId":"t-5",
       "fieldErrors":{"reason":"SOMETHING_NEW","existingBeneficiaryId":"case-9"}}
    """.trimIndent()

    assertEquals(DuplicateOutcome.HardDuplicate, outcomeOf(body))
  }

  @Test
  fun `reason match is case insensitive`() {
    val body = """
      {"success":false,"message":"…","errorCode":"CONFLICT","traceId":"t-6",
       "fieldErrors":{"reason":"re_enrollment","existingBeneficiaryId":"case-7"}}
    """.trimIndent()

    assertTrue(outcomeOf(body) is DuplicateOutcome.NewPregnancyPrompt)
  }

  @Test
  fun `an empty or malformed body is blocked, never crashes`() {
    assertEquals(DuplicateOutcome.HardDuplicate, outcomeOf(""))
    assertEquals(DuplicateOutcome.HardDuplicate, outcomeOf("<html>gateway timeout</html>"))
  }

  @Test
  fun `detail keys are declared so no UI path renders them as field errors`() {
    assertEquals(
      setOf("reason", "existingBeneficiaryId", "resolution"),
      DuplicateOutcomeParser.DETAIL_KEYS,
    )
  }
}
