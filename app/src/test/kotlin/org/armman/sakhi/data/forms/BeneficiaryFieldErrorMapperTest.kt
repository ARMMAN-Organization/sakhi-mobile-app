package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [BeneficiaryFieldErrorMapper] — turning the backend's dotted DTO-path `fieldErrors` back
 * into per-`question_code` errors, the reverse of [DynamicFormSubmissionMapper]'s routing. Guards
 * the mappings a real 400 hits (names, mother-details counts, geography) and the two safety rules:
 * the split-vs-combined name fallback, and silently dropping paths with no field home.
 */
class BeneficiaryFieldErrorMapperTest {

  /** The split-name schema (v2+) plus the mother-detail and geography codes the tests touch. */
  private val splitNameSchemaCodes = setOf(
    "first_name",
    "middle_name",
    "last_name",
    "still_births",
    "gravida_total_number_of_pregnancies",
    "abortions_pregnancy_losses_before_24_weeks",
    "living_children",
    GeographyQuestionCodes.PHC,
  )

  @Test
  fun `maps split name paths to their own question codes`() {
    val result = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
      mapOf(
        "pii.firstName" to "First name is required",
        "pii.lastName" to "Last name is required",
      ),
      splitNameSchemaCodes,
    )

    assertEquals("First name is required", result["first_name"])
    assertEquals("Last name is required", result["last_name"])
  }

  @Test
  fun `pins a registration date error to whichever spelling the active schema declares`() {
    val errors = mapOf("case.registrationDate" to "Registration date cannot be in the future")

    assertEquals(
      "Registration date cannot be in the future",
      BeneficiaryFieldErrorMapper
        .toQuestionCodeErrors(errors, setOf(REGISTRATION_DATE_QUESTION_CODE))[REGISTRATION_DATE_QUESTION_CODE],
    )
    assertEquals(
      "Registration date cannot be in the future",
      BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
        errors,
        setOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED),
      )[REGISTRATION_DATE_QUESTION_CODE_CORRECTED],
    )
    // No registration-date field in the schema: dropped to the page-level banner, not guessed at.
    assertTrue(BeneficiaryFieldErrorMapper.toQuestionCodeErrors(errors, setOf("first_name")).isEmpty())
  }

  @Test
  fun `maps mother-detail counts to their schema question codes`() {
    val result = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
      mapOf(
        "motherDetails.stillbirths" to "too many",
        "motherDetails.gravida" to "out of range",
        "motherDetails.abortions" to "invalid",
        // liveBirths is fed by living_children in the forward mapper — the reverse must agree.
        "motherDetails.liveBirths" to "invalid",
      ),
      splitNameSchemaCodes,
    )

    assertEquals("too many", result["still_births"])
    assertEquals("out of range", result["gravida_total_number_of_pregnancies"])
    assertEquals("invalid", result["abortions_pregnancy_losses_before_24_weeks"])
    assertEquals("invalid", result["living_children"])
  }

  @Test
  fun `maps a geography path to its geography question code`() {
    val result = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
      mapOf("pii.phcId" to "does not refer to a known geography unit"),
      splitNameSchemaCodes,
    )

    assertEquals(
      "does not refer to a known geography unit",
      result[GeographyQuestionCodes.PHC],
    )
  }

  @Test
  fun `falls back to the combined name field when the split fields are absent (older schema)`() {
    val combinedNameCode = "beneficary_name_first_name_middle_name_last_name"
    val v1SchemaCodes = setOf(combinedNameCode, "still_births")

    val result = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
      mapOf(
        "pii.firstName" to "First name is required",
        "pii.lastName" to "Last name is required",
      ),
      v1SchemaCodes,
    )

    // Both name paths collapse onto the single combined field; the first message wins.
    assertEquals(1, result.size)
    assertEquals("First name is required", result[combinedNameCode])
  }

  @Test
  fun `drops a path with no field home rather than guessing`() {
    val result = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
      mapOf(
        "case.projectId" to "some backend-only field",
        "pii.someBrandNewField" to "unknown to this app version",
      ),
      splitNameSchemaCodes,
    )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `drops a mapped path whose question code is not in the active schema`() {
    // `pii.dateOfBirth` maps to `date_of_birth`, but this schema doesn't render it — so there's no
    // field to pin the error to; it stays banner-only rather than being force-mapped.
    val result = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(
      mapOf("pii.dateOfBirth" to "Invalid date"),
      splitNameSchemaCodes,
    )

    assertFalse(result.containsKey("date_of_birth"))
    assertTrue(result.isEmpty())
  }
}
