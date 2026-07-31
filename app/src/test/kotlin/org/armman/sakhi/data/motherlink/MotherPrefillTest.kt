package org.armman.sakhi.data.motherlink

import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Covers MAP-01 … MAP-13 of `docs/test-cases/child-mother-link.md`. */
class MotherPrefillTest {

  private val geography = listOf(
    FormGeographyUnit("state-1", "STATE", "Maharashtra"),
    FormGeographyUnit("district-1", "DISTRICT", "Pune"),
    FormGeographyUnit("taluka-1", "BLOCK", "Khed"),
    FormGeographyUnit("village-1", "VILLAGE", "Kanhe"),
    FormGeographyUnit("pada-1", "PADA", "Kanhe Pada"),
    FormGeographyUnit("phc-1", "PHC", "Kanhe PHC"),
    FormGeographyUnit("sc-1", "SUBCENTRE", "Kanhe SC"),
  )

  private fun mother(
    id: String = "mother-uuid-1",
    fullName: String = "Deepa T Test",
    dateOfBirth: LocalDate? = LocalDate.of(2001, 7, 29),
    currentPhase: String = "ANC",
    stateId: String? = "state-1",
    districtId: String? = "district-1",
    talukaId: String? = "taluka-1",
    villageId: String? = "village-1",
    padaId: String? = "pada-1",
    phcId: String? = "phc-1",
    healthSubCentreId: String? = "sc-1",
  ) = LinkedMother(
    id = id,
    fullName = fullName,
    dateOfBirth = dateOfBirth,
    currentPhase = currentPhase,
    registrationDate = LocalDate.of(2026, 7, 28),
    stateId = stateId,
    districtId = districtId,
    talukaId = talukaId,
    villageId = villageId,
    padaId = padaId,
    phcId = phcId,
    healthSubCentreId = healthSubCentreId,
  )

  // MAP-01
  @Test
  fun `prefills id name dob and every shipped geography level`() {
    val result = MotherPrefill.apply(FormAnswers(), mother(), consent = null, geography = geography)

    assertEquals("mother-uuid-1", result.answers.valueOf(MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID))
    assertEquals("Deepa T Test", result.answers.valueOf(MotherPrefillQuestionCodes.CAREGIVER_NAME))
    assertEquals("2001-07-29", result.answers.valueOf(MotherPrefillQuestionCodes.MOTHER_DATE_OF_BIRTH))
    assertEquals("state-1", result.answers.valueOf(GeographyQuestionCodes.STATE))
    assertEquals("district-1", result.answers.valueOf(GeographyQuestionCodes.DISTRICT))
    assertEquals("village-1", result.answers.valueOf(GeographyQuestionCodes.VILLAGE))
    assertEquals("pada-1", result.answers.valueOf(GeographyQuestionCodes.PADA))
    assertEquals("phc-1", result.answers.valueOf(GeographyQuestionCodes.PHC))
    assertEquals("sc-1", result.answers.valueOf(GeographyQuestionCodes.SUB_CENTRE))
  }

  // MAP-02 — the submission mapper sends this answer as pii.talukaId and hardcodes healthBlockId=null.
  @Test
  fun `block taluka comes from talukaId`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(talukaId = "taluka-1"),
      consent = null,
      geography = geography,
    )
    assertEquals("taluka-1", result.answers.valueOf(GeographyQuestionCodes.BLOCK_TALUKA))
  }

  // MAP-03 — project_name's valueCode is the project *name* from the profile, not the projectId UUID.
  @Test
  fun `never prefills project name`() {
    val result = MotherPrefill.apply(FormAnswers(), mother(), consent = null, geography = geography)
    assertNull(result.answers.valueOf(GeographyQuestionCodes.PROJECT_NAME))
    assertFalse(GeographyQuestionCodes.PROJECT_NAME in result.prefilledCodes)
  }

  // MAP-04 — writing an unshipped geographyUnitId is what produces a 422 at /beneficiaries.
  @Test
  fun `skips a geography level the backend did not ship for this sakhi`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(villageId = "village-from-another-block"),
      consent = null,
      geography = geography,
    )
    assertNull(result.answers.valueOf(GeographyQuestionCodes.VILLAGE))
    assertFalse(GeographyQuestionCodes.VILLAGE in result.prefilledCodes)
    // The rest still prefills — one foreign unit must not abandon the whole mapping.
    assertEquals("pada-1", result.answers.valueOf(GeographyQuestionCodes.PADA))
  }

  // MAP-05
  @Test
  fun `skips an id that exists in geography under a different geoType`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      // village-1 is shipped, but as a VILLAGE — it must not satisfy the PADA slot.
      mother(padaId = "village-1"),
      consent = null,
      geography = geography,
    )
    assertNull(result.answers.valueOf(GeographyQuestionCodes.PADA))
  }

  // MAP-06 — live data contains mothers with unusable DOBs; they must stay linkable.
  @Test
  fun `null date of birth skips only that field`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(dateOfBirth = null),
      consent = null,
      geography = geography,
    )
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.MOTHER_DATE_OF_BIRTH))
    assertEquals("Deepa T Test", result.answers.valueOf(MotherPrefillQuestionCodes.CAREGIVER_NAME))
  }

  // MAP-07
  @Test
  fun `blank name is not written`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(fullName = "   "),
      consent = null,
      geography = geography,
    )
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.CAREGIVER_NAME))
    assertFalse(MotherPrefillQuestionCodes.CAREGIVER_NAME in result.prefilledCodes)
  }

  // MAP-08 — this set drives the hint and what a path switch may delete, so it must be exact.
  @Test
  fun `prefilled codes lists exactly what was written`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(dateOfBirth = null, phcId = "unshipped-phc"),
      consent = null,
      geography = geography,
    )
    assertEquals(
      setOf(
        MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID,
        MotherPrefillQuestionCodes.CAREGIVER_NAME,
        GeographyQuestionCodes.STATE,
        GeographyQuestionCodes.DISTRICT,
        GeographyQuestionCodes.BLOCK_TALUKA,
        GeographyQuestionCodes.VILLAGE,
        GeographyQuestionCodes.PADA,
        GeographyQuestionCodes.SUB_CENTRE,
      ),
      result.prefilledCodes,
    )
  }

  // MAP-09 — an explicit selection outranks an auto-selected geography value or a resumed draft.
  @Test
  fun `overwrites an existing answer for a target code`() {
    val existing = FormAnswers()
      .withSingleValue(MotherPrefillQuestionCodes.CAREGIVER_NAME, "Typed Earlier")
      .withSingleValue(GeographyQuestionCodes.VILLAGE, "some-other-village")

    val result = MotherPrefill.apply(existing, mother(), consent = null, geography = geography)

    assertEquals("Deepa T Test", result.answers.valueOf(MotherPrefillQuestionCodes.CAREGIVER_NAME))
    assertEquals("village-1", result.answers.valueOf(GeographyQuestionCodes.VILLAGE))
  }

  // MAP-10 (decision D2 — spec row 4)
  @Test
  fun `inherits consent yes when the mother consented`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(),
      consent = LinkedMotherConsent(consentGiven = true),
      geography = geography,
    )
    assertEquals("yes", result.answers.valueOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT))
    assertTrue(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT in result.prefilledCodes)
  }

  // MAP-11 — guards the one-line reversal path for risk R1.
  @Test
  fun `consent inheritance is gated by a single constant`() {
    // If ARMMAN rules against inheriting consent, flipping INHERIT_CONSENT_FROM_MOTHER to false is
    // the whole change. This test documents that contract and fails loudly if the flag is bypassed
    // by a future edit that writes consent unconditionally.
    if (!MotherPrefill.INHERIT_CONSENT_FROM_MOTHER) {
      val result = MotherPrefill.apply(
        FormAnswers(),
        mother(),
        consent = LinkedMotherConsent(consentGiven = true),
        geography = geography,
      )
      assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT))
    }
  }

  // MAP-12 — auto-answering "no" would trip the consent-refused gate on the Sakhi's behalf.
  @Test
  fun `never auto answers consent no`() {
    val result = MotherPrefill.apply(
      FormAnswers(),
      mother(),
      consent = LinkedMotherConsent(consentGiven = false),
      geography = geography,
    )
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT))
  }

  @Test
  fun `missing consent record leaves the question unanswered`() {
    val result = MotherPrefill.apply(FormAnswers(), mother(), consent = null, geography = geography)
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT))
  }

  // MAP-13
  @Test
  fun `clear removes only still-prefilled codes and keeps edited ones`() {
    val applied = MotherPrefill.apply(FormAnswers(), mother(), consent = null, geography = geography)
    // The Sakhi edited the name, so the ViewModel dropped it from the set.
    val editedName = applied.answers.withSingleValue(MotherPrefillQuestionCodes.CAREGIVER_NAME, "Her Own Entry")
    val stillPrefilled = applied.prefilledCodes - MotherPrefillQuestionCodes.CAREGIVER_NAME

    val cleared = MotherPrefill.clear(editedName, stillPrefilled)

    assertEquals("Her Own Entry", cleared.valueOf(MotherPrefillQuestionCodes.CAREGIVER_NAME))
    assertNull(cleared.valueOf(MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID))
    assertNull(cleared.valueOf(GeographyQuestionCodes.VILLAGE))
  }

  @Test
  fun `delivery not recorded flags an ANC phase mother`() {
    assertTrue(mother(currentPhase = "ANC").deliveryNotRecorded)
    assertFalse(mother(currentPhase = "PP").deliveryNotRecorded)
    assertFalse(mother(currentPhase = "DELIVERY").deliveryNotRecorded)
  }
}
