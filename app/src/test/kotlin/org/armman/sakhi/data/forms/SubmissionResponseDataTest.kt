package org.armman.sakhi.data.forms

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-042 (Delivery Event Session). Pins the exact Gson deserialization behavior of
 * [SubmissionResponseData.childBeneficiaryIds], per the backend's confirmed contract
 * (2026-08-18): the field is present only on `DELIVERY_VISIT` submissions with a live birth, and
 * "key absent" vs "key present but empty" are two different, meaningful states — not the same
 * thing collapsed by a `?: emptyList()` default.
 *
 * Parses real JSON strings end-to-end (not constructed DTOs), matching the convention in
 * [org.armman.sakhi.data.motherlink.BeneficiaryListResponseDtoTest] — this is exactly the kind of
 * detail a hand-built DTO fixture would paper over.
 */
class SubmissionResponseDataTest {

  private val gson = Gson()

  private fun parse(json: String): CreateSubmissionResponseDto =
    gson.fromJson(json, CreateSubmissionResponseDto::class.java)

  @Test
  fun `a non-DELIVERY_VISIT submission response (key absent) deserializes to null, not empty`() {
    val response = parse(
      """
      {"success": true, "message": null, "data": {
        "id": "sub-1", "submittedByUserId": "user-1"
      }}
      """.trimIndent(),
    )

    assertNull(response.data?.childBeneficiaryIds)
  }

  @Test
  fun `a single live birth returns a one-element list in submitted order`() {
    val response = parse(
      """
      {"success": true, "message": null, "data": {
        "id": "sub-1", "submittedByUserId": "user-1",
        "childBeneficiaryIds": ["child-1"]
      }}
      """.trimIndent(),
    )

    assertEquals(listOf("child-1"), response.data?.childBeneficiaryIds)
  }

  @Test
  fun `twins return both ids in child1, child2 order`() {
    val response = parse(
      """
      {"success": true, "message": null, "data": {
        "id": "sub-1", "childBeneficiaryIds": ["child-1", "child-2"]
      }}
      """.trimIndent(),
    )

    assertEquals(listOf("child-1", "child-2"), response.data?.childBeneficiaryIds)
  }

  @Test
  fun `mixed live and stillborn skips the stillborn id rather than padding with null`() {
    // child2 stillborn: backend returns only child1's id, not ["child-1", null].
    val response = parse(
      """
      {"success": true, "message": null, "data": {
        "id": "sub-1", "childBeneficiaryIds": ["child-1"]
      }}
      """.trimIndent(),
    )

    assertEquals(1, response.data?.childBeneficiaryIds?.size)
    assertTrue(response.data?.childBeneficiaryIds?.none { it == null || it.isBlank() } == true)
  }

  @Test
  fun `a stillbirth-only delivery (no live birth) omits the key -- key absence, not an empty array`() {
    val response = parse(
      """
      {"success": true, "message": null, "data": {"id": "sub-1"}}
      """.trimIndent(),
    )

    assertNull(
      "Absent key must deserialize to null so callers can branch on it explicitly " +
        "(null = skip child-registration step, not loop zero times)",
      response.data?.childBeneficiaryIds,
    )
  }

  @Test
  fun `an idempotent replay of the same submission returns the same ids`() {
    val json = """
      {"success": true, "message": null, "data": {
        "id": "sub-1", "childBeneficiaryIds": ["child-1", "child-2"]
      }}
    """.trimIndent()

    val first = parse(json)
    val replay = parse(json)

    assertEquals(first.data?.childBeneficiaryIds, replay.data?.childBeneficiaryIds)
  }

  @Test
  fun `existing fields are unaffected by the new nullable property`() {
    val response = parse(
      """{"success": true, "message": "ok", "data": {"id": "sub-1", "submittedByUserId": "user-9"}}""",
    )

    assertEquals("sub-1", response.data?.id)
    assertEquals("user-9", response.data?.submittedByUserId)
  }
}
