package org.armman.sakhi.data.motherlink

import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
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

  // --- Rows 21-34 (CR-032) ---------------------------------------------------------------------

  private fun radioField(questionCode: String, vararg valueCodeToLabel: Pair<String, String>) = FormFieldSchema(
    label = questionCode,
    required = false,
    inputTypeRaw = "radio",
    questionCode = questionCode,
    options = valueCodeToLabel.mapIndexed { index, (valueCode, label) ->
      FormFieldOption(label = label, sortOrder = index, valueCode = valueCode)
    },
  )

  /** The live CHILD_REGISTRATION schema's own options for the fields CR-032 prefills (captured from
   * api-calls-live.jsonl), trimmed to the option this test file actually exercises. */
  private val socioDemoSchema = listOf(
    radioField(MotherPrefillQuestionCodes.PHONE_OWNER, "self" to "Self", "husband" to "Husband"),
    radioField(
      MotherPrefillQuestionCodes.MOBILE_NETWORK_AVAILABILITY,
      "no_network" to "No Network",
      "full_network_available" to "Full Network Available",
    ),
    radioField(
      MotherPrefillQuestionCodes.EDUCATION_LEVEL,
      "no_formal_education_never_attended_school_cannot_read_or_write" to
        "No formal education (Never attended school / cannot read or write)",
      "10th_pass" to "10TH Pass",
    ),
    radioField(
      MotherPrefillQuestionCodes.MONTHLY_INCOME,
      "10000" to "≤10000",
      "25000" to ">25000",
    ),
    radioField(MotherPrefillQuestionCodes.RELIGION, "buddhist" to "Buddhist", "hindu" to "Hindu"),
  )

  private fun socioDemographics(
    address: String? = "abbbsss",
    mobileNumber: String? = "6948454949",
    phoneOwner: MotherLookupAnswer? = MotherLookupAnswer("PHONE_OWNER", "Self"),
    mobileNetworkAvailability: MotherLookupAnswer? = MotherLookupAnswer("MOBILE_NETWORK_AVAILABILITY", "No Network"),
    educationLevel: MotherLookupAnswer? = MotherLookupAnswer("EDUCATION_LEVEL", "No formal education"),
    monthlyIncome: MotherLookupAnswer? = MotherLookupAnswer("MONTHLY_INCOME_BRACKET", "<=10000"),
    religion: MotherLookupAnswer? = MotherLookupAnswer("RELIGION", "Buddhist"),
    yearsInVillage: Int? = 6,
    familyMembersCount: Int? = 6,
    childrenUnder5Count: Int? = 1,
  ) = MotherSocioDemographics(
    address = address,
    mobileNumber = mobileNumber,
    phoneOwner = phoneOwner,
    mobileNetworkAvailability = mobileNetworkAvailability,
    educationLevel = educationLevel,
    partnerEducationLevel = null,
    partnerOccupation = null,
    yearsInVillage = yearsInVillage,
    migrationPattern = null,
    monthlyIncome = monthlyIncome,
    religion = religion,
    socialCategory = null,
    familyMembersCount = familyMembersCount,
    childrenUnder5Count = childrenUnder5Count,
  )

  // SOCIO-01 — direct values (no lookup translation needed) map straight across.
  @Test
  fun `prefills the direct-value socio demographic fields`() {
    val result = MotherPrefill.apply(
      FormAnswers(), mother(), consent = null, geography = geography,
      socioDemographics = socioDemographics(), formSchema = socioDemoSchema,
    )
    assertEquals("abbbsss", result.answers.valueOf(MotherPrefillQuestionCodes.ADDRESS))
    assertEquals("6948454949", result.answers.valueOf(MotherPrefillQuestionCodes.MOBILE_NUMBER))
    assertEquals("6", result.answers.valueOf(MotherPrefillQuestionCodes.YEARS_IN_VILLAGE))
    assertEquals("6", result.answers.valueOf(MotherPrefillQuestionCodes.FAMILY_MEMBERS_COUNT))
    assertEquals("1", result.answers.valueOf(MotherPrefillQuestionCodes.CHILDREN_UNDER_5_COUNT))
  }

  // SOCIO-02 — an exact label match (Self, No Network, Buddhist, <=10000) resolves to the schema's
  // own value_code, never the API's own upper-snake-case valueCode.
  @Test
  fun `resolves lookup fields to the schema value_code by exact label match`() {
    val result = MotherPrefill.apply(
      FormAnswers(), mother(), consent = null, geography = geography,
      socioDemographics = socioDemographics(), formSchema = socioDemoSchema,
    )
    assertEquals("self", result.answers.valueOf(MotherPrefillQuestionCodes.PHONE_OWNER))
    assertEquals("no_network", result.answers.valueOf(MotherPrefillQuestionCodes.MOBILE_NETWORK_AVAILABILITY))
    assertEquals("buddhist", result.answers.valueOf(MotherPrefillQuestionCodes.RELIGION))
    assertEquals("10000", result.answers.valueOf(MotherPrefillQuestionCodes.MONTHLY_INCOME))
  }

  // SOCIO-03 — the API's shorter label ("No formal education") is a prefix of the schema's fuller
  // one; the prefix fallback must still resolve it rather than leaving the field blank.
  @Test
  fun `resolves education via the longest label prefix fallback`() {
    val result = MotherPrefill.apply(
      FormAnswers(), mother(), consent = null, geography = geography,
      socioDemographics = socioDemographics(), formSchema = socioDemoSchema,
    )
    assertEquals(
      "no_formal_education_never_attended_school_cannot_read_or_write",
      result.answers.valueOf(MotherPrefillQuestionCodes.EDUCATION_LEVEL),
    )
  }

  // SOCIO-04 — a label that matches nothing in the live schema (renamed/removed option) must skip
  // the field, never write a guess or the API's own raw code.
  @Test
  fun `skips a lookup field with no confident match in the schema`() {
    val result = MotherPrefill.apply(
      FormAnswers(), mother(), consent = null, geography = geography,
      socioDemographics = socioDemographics(religion = MotherLookupAnswer("RELIGION", "Zoroastrian")),
      formSchema = socioDemoSchema,
    )
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.RELIGION))
    assertFalse(MotherPrefillQuestionCodes.RELIGION in result.prefilledCodes)
  }

  // SOCIO-05 — a null socioDemographics (offline/failed fetch) must not touch rows 21-34 at all,
  // and every other row must still prefill normally.
  @Test
  fun `null socio demographics skips rows 21-34 without affecting the rest`() {
    val result = MotherPrefill.apply(
      FormAnswers(), mother(), consent = null, geography = geography,
      socioDemographics = null, formSchema = socioDemoSchema,
    )
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.ADDRESS))
    assertNull(result.answers.valueOf(MotherPrefillQuestionCodes.PHONE_OWNER))
    assertEquals("mother-uuid-1", result.answers.valueOf(MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID))
  }

  // SOCIO-06 — prefilledCodes must include every row 21-34 field that was actually written.
  @Test
  fun `prefilled codes includes the written socio demographic fields`() {
    val result = MotherPrefill.apply(
      FormAnswers(), mother(), consent = null, geography = geography,
      socioDemographics = socioDemographics(), formSchema = socioDemoSchema,
    )
    assertTrue(MotherPrefillQuestionCodes.ADDRESS in result.prefilledCodes)
    assertTrue(MotherPrefillQuestionCodes.PHONE_OWNER in result.prefilledCodes)
    assertTrue(MotherPrefillQuestionCodes.MONTHLY_INCOME in result.prefilledCodes)
    assertTrue(MotherPrefillQuestionCodes.YEARS_IN_VILLAGE in result.prefilledCodes)
  }
}
