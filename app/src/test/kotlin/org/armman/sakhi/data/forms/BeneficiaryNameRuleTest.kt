package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeneficiaryNameRuleTest {

  @Test
  fun `letters and spaces pass through unchanged`() {
    assertEquals("Reema Devi", BeneficiaryNameRule.sanitize("Reema Devi"))
    assertTrue(BeneficiaryNameRule.isValid("Reema Devi"))
  }

  @Test
  fun `Devanagari names pass through unchanged`() {
    assertEquals("रीमा देवी", BeneficiaryNameRule.sanitize("रीमा देवी"))
    assertTrue(BeneficiaryNameRule.isValid("रीमा देवी"))
  }

  @Test
  fun `symbols and digits are stripped and reported invalid`() {
    assertEquals("Reema", BeneficiaryNameRule.sanitize("Reema@#1"))
    assertFalse(BeneficiaryNameRule.isValid("Reema@#1"))
  }

  @Test
  fun `digits embedded in a name are stripped`() {
    assertEquals("Rma", BeneficiaryNameRule.sanitize("R33ma!"))
    assertFalse(BeneficiaryNameRule.isValid("R33ma!"))
  }

  @Test
  fun `apostrophe hyphen and dot are not allowed`() {
    // Decision (2026-07-29): letters and spaces only, matching the static enrollment flow's
    // PersonalInfoState.nameError(). Revisit together with the static flow if the field ever needs
    // to carry punctuation.
    assertEquals("OBrien", BeneficiaryNameRule.sanitize("O'Brien"))
    assertEquals("AnneMarie", BeneficiaryNameRule.sanitize("Anne-Marie"))
    assertEquals("Dr", BeneficiaryNameRule.sanitize("Dr."))
    assertFalse(BeneficiaryNameRule.isValid("O'Brien"))
  }

  @Test
  fun `blank and null are valid - the required gate owns emptiness`() {
    assertTrue(BeneficiaryNameRule.isValid(""))
    assertTrue(BeneficiaryNameRule.isValid(null))
    assertEquals("", BeneficiaryNameRule.sanitize(""))
  }

  @Test
  fun `whitespace-only is character-valid`() {
    // Not this rule's problem: a whitespace-only name is blank to the required-field gate, and the
    // submission mapper trims it away.
    assertTrue(BeneficiaryNameRule.isValid("   "))
    assertEquals("   ", BeneficiaryNameRule.sanitize("   "))
  }

  @Test
  fun `applies to the name questions including the combined child name`() {
    assertTrue(BeneficiaryNameRule.appliesTo("first_name"))
    assertTrue(BeneficiaryNameRule.appliesTo("middle_name"))
    assertTrue(BeneficiaryNameRule.appliesTo("last_name"))
    // Child Registration's single combined name field (PR #31 review).
    assertTrue(BeneficiaryNameRule.appliesTo("name_of_the_child"))
    assertFalse(BeneficiaryNameRule.appliesTo("enter_the_beneficiary_address"))
    assertFalse(BeneficiaryNameRule.appliesTo("input_rch_number"))
  }

  @Test
  fun `also applies to both combined-shape codes the mother beneficiary-name field has used`() {
    // 2026-08-06: the live MOTHER_REGISTRATION schema went back to ONE combined field
    // (BeneficiaryNameQuestionCodes.CURRENT) after the 2026-07-22 split — without this, character
    // sanitization would silently stop working the moment the schema flipped.
    assertTrue(BeneficiaryNameRule.appliesTo(BeneficiaryNameQuestionCodes.CURRENT))
    assertTrue(BeneficiaryNameRule.appliesTo(BeneficiaryNameQuestionCodes.LEGACY_TYPO))
  }
}
