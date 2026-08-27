package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BeneficiaryNameQuestionCodes.combinedNameAnswer] is the ONE place that reads the
 * beneficiary-name field's combined shape — both [DynamicFormSubmissionMapper] (the
 * `/beneficiaries` submission) and [org.armman.sakhi.data.beneficiary
 * .LocalEnrolmentBeneficiarySource] (the "My Beneficiaries" list) depend on it. The 2026-08-06
 * production bug this guards against: each of those two call sites had its OWN copy of a
 * first_name/middle_name/last_name read, the live schema moved to one combined `beneficiary_name`
 * field, and fixing one call site left the other silently broken ("Unnamed beneficiary" on the
 * list despite a correct name in the submission).
 */
class BeneficiaryNameQuestionCodesTest {

  @Test
  fun `reads the current combined field`() {
    val answers = FormAnswers(singleValues = mapOf(BeneficiaryNameQuestionCodes.CURRENT to "Priya Sharma"))

    assertEquals("Priya Sharma", BeneficiaryNameQuestionCodes.combinedNameAnswer(answers))
  }

  @Test
  fun `reads the legacy typo'd combined field when that's what the schema has`() {
    val answers = FormAnswers(singleValues = mapOf(BeneficiaryNameQuestionCodes.LEGACY_TYPO to "Priya Sharma"))

    assertEquals("Priya Sharma", BeneficiaryNameQuestionCodes.combinedNameAnswer(answers))
  }

  @Test
  fun `the current spelling wins when both happen to be answered`() {
    val answers = FormAnswers(
      singleValues = mapOf(
        BeneficiaryNameQuestionCodes.CURRENT to "Current Name",
        BeneficiaryNameQuestionCodes.LEGACY_TYPO to "Legacy Name",
      ),
    )

    assertEquals("Current Name", BeneficiaryNameQuestionCodes.combinedNameAnswer(answers))
  }

  @Test
  fun `trims the answer`() {
    val answers = FormAnswers(singleValues = mapOf(BeneficiaryNameQuestionCodes.CURRENT to "  Priya Sharma  "))

    assertEquals("Priya Sharma", BeneficiaryNameQuestionCodes.combinedNameAnswer(answers))
  }

  @Test
  fun `returns null - not the caller's own fallback - when neither combined field is answered`() {
    assertNull(BeneficiaryNameQuestionCodes.combinedNameAnswer(FormAnswers()))
  }

  @Test
  fun `returns null for a blank or whitespace-only combined answer, letting the caller fall back`() {
    val blank = FormAnswers(singleValues = mapOf(BeneficiaryNameQuestionCodes.CURRENT to "   "))

    assertNull(BeneficiaryNameQuestionCodes.combinedNameAnswer(blank))
  }
}
