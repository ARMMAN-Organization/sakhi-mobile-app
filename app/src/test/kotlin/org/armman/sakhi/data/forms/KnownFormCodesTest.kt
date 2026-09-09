package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity checks on the static list [FormSchemaWarmer] iterates -- catches an accidental
 * duplicate or an empty list before it ships. */
class KnownFormCodesTest {

  @Test
  fun `ALL has no duplicate form codes`() {
    assertEquals(KnownFormCodes.ALL.size, KnownFormCodes.ALL.distinct().size)
  }

  @Test
  fun `ALL is not empty`() {
    assertTrue(KnownFormCodes.ALL.isNotEmpty())
  }

  @Test
  fun `ALL contains every declared constant`() {
    val declaredConstants = setOf(
      KnownFormCodes.MOTHER_REGISTRATION,
      KnownFormCodes.CHILD_REGISTRATION,
      KnownFormCodes.ANC_VISIT,
      KnownFormCodes.INFANT_VISIT,
      KnownFormCodes.POSTPARTUM_VISIT,
      KnownFormCodes.NEONATAL_VISIT,
      KnownFormCodes.INC_VISIT,
      KnownFormCodes.CCV_VISIT,
      KnownFormCodes.DELIVERY_VISIT,
      KnownFormCodes.REFERRAL_VISIT,
      KnownFormCodes.REFERRAL_FOLLOWUP_VISIT,
      KnownFormCodes.ANC_CLOSURE_VISIT,
      KnownFormCodes.CHILD_CLOSURE_VISIT,
      KnownFormCodes.BENEFICIARY_REOPEN_VISIT,
    )
    assertEquals(declaredConstants, KnownFormCodes.ALL.toSet())
  }
}
