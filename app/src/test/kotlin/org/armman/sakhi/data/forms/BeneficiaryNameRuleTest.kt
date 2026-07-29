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
  fun `applies only to the three name questions`() {
    assertTrue(BeneficiaryNameRule.appliesTo("first_name"))
    assertTrue(BeneficiaryNameRule.appliesTo("middle_name"))
    assertTrue(BeneficiaryNameRule.appliesTo("last_name"))
    assertFalse(BeneficiaryNameRule.appliesTo("enter_the_beneficiary_address"))
    assertFalse(BeneficiaryNameRule.appliesTo("input_rch_number"))
  }
}
