package org.armman.sakhi.data.motherlink

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [BeneficiaryListResponseDto.items] — the shape-tolerant accessor added after two
 * different `GET /beneficiaries` backends were observed live on 2026-08-07: production
 * (`api.armman.org`) sends `data` as a bare array, while a newer backend build (behind an ngrok
 * tunnel) sends `data: { "items": [...] }`. [BeneficiaryListResponseDto.data] is typed as a raw
 * `JsonElement` specifically so parsing never throws on either shape — these tests parse real
 * JSON strings end-to-end (not constructed DTOs) so they catch a regression in the actual Gson
 * wiring, not just the normalization logic in isolation.
 */
class BeneficiaryListResponseDtoTest {

  private val gson = Gson()

  private fun parse(json: String): BeneficiaryListResponseDto =
    gson.fromJson(json, BeneficiaryListResponseDto::class.java)

  @Test
  fun `bare array shape (production today) parses via items`() {
    val dto = parse(
      """
      {"success": true, "message": "OK", "data": [
        {"id": "row-1", "caseType": "MOTHER", "currentStatus": "ACTIVE", "currentPhase": "ANC", "registrationDate": "2026-07-01T00:00:00.000Z", "motherBeneficiaryId": null, "pii": null}
      ]}
      """.trimIndent(),
    )

    assertEquals(listOf("row-1"), dto.items.map { it.id })
  }

  @Test
  fun `items-wrapped shape (newer backend build) parses via items`() {
    val dto = parse(
      """
      {"success": true, "message": "OK", "data": {"items": [
        {"id": "row-1", "caseType": "MOTHER", "currentStatus": "ACTIVE", "currentPhase": "ANC", "registrationDate": "2026-08-08T00:00:00.000Z", "motherBeneficiaryId": null, "pii": null}
      ]}}
      """.trimIndent(),
    )

    assertEquals(listOf("row-1"), dto.items.map { it.id })
  }

  @Test
  fun `null data returns an empty list, not a crash`() {
    val dto = parse("""{"success": true, "message": "OK", "data": null}""")

    assertTrue(dto.items.isEmpty())
  }

  @Test
  fun `an unrecognised object shape (no items key) degrades to an empty list`() {
    val dto = parse("""{"success": true, "message": "OK", "data": {"total": 0}}""")

    assertTrue(dto.items.isEmpty())
  }

  @Test
  fun `both shapes produce identical BeneficiaryListItemDto fields`() {
    val bareArray = parse(
      """
      {"success": true, "message": "OK", "data": [
        {"id": "row-1", "caseType": "CHILD", "currentStatus": "CLOSED", "currentPhase": "NN", "registrationDate": "2026-07-01T00:00:00.000Z", "motherBeneficiaryId": "mother-1", "pii": {"id": "pii-1", "fullName": "Test Child", "villageId": null, "padaId": null, "healthSubCentreId": null, "phcId": null, "healthBlockId": null, "dateOfBirth": null, "sex": "MALE", "stateId": null, "districtId": null, "talukaId": null}}
      ]}
      """.trimIndent(),
    )
    val wrapped = parse(
      """
      {"success": true, "message": "OK", "data": {"items": [
        {"id": "row-1", "caseType": "CHILD", "currentStatus": "CLOSED", "currentPhase": "NN", "registrationDate": "2026-07-01T00:00:00.000Z", "motherBeneficiaryId": "mother-1", "pii": {"id": "pii-1", "fullName": "Test Child", "villageId": null, "padaId": null, "healthSubCentreId": null, "phcId": null, "healthBlockId": null, "dateOfBirth": null, "sex": "MALE", "stateId": null, "districtId": null, "talukaId": null}}
      ]}}
      """.trimIndent(),
    )

    assertEquals(bareArray.items.single(), wrapped.items.single())
  }
}
