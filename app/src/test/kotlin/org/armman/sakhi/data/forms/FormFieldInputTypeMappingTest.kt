package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Test

class FormFieldInputTypeMappingTest {

  @Test
  fun `every known multiselect_date spelling maps to MULTISELECT_DATE`() {
    // Reported bug (2026-08-06): the live schema switched Q44's spelling to "multiselect,calendar"
    // without warning, which fell through to UNKNOWN and rendered "Unsupported field type ... —
    // app update needed." instead of the Td-dose checkboxes.
    assertEquals(FormFieldInputType.MULTISELECT_DATE, "multiselect_date".toFormFieldInputType())
    assertEquals(FormFieldInputType.MULTISELECT_DATE, "multiselect,calendar".toFormFieldInputType())
    assertEquals(FormFieldInputType.MULTISELECT_DATE, "multiselect, calendar".toFormFieldInputType())
  }

  @Test
  fun `plain multiselect is unaffected`() {
    assertEquals(FormFieldInputType.MULTISELECT, "multiselect".toFormFieldInputType())
  }

  @Test
  fun `every known dropdown spelling maps to SELECT`() {
    // Reported bug (2026-08-18): Referral form's "If No, state reasons" and the ANC/Infant
    // Closure forms' "closure_reason" and "maternal_death_place" fields all ship as
    // input_type "dropdown" (confirmed against a real GET /forms/.../active-version payload),
    // not "select" — this fell through to UNKNOWN and rendered "Unsupported field type ...
    // (dropdown) — app update needed." instead of the dropdown.
    assertEquals(FormFieldInputType.SELECT, "select".toFormFieldInputType())
    assertEquals(FormFieldInputType.SELECT, "dropdown".toFormFieldInputType())
    assertEquals(FormFieldInputType.SELECT, "Dropdown".toFormFieldInputType())
  }

  @Test
  fun `a genuinely unrecognized input_type still falls back to UNKNOWN`() {
    assertEquals(FormFieldInputType.UNKNOWN, "some_future_type".toFormFieldInputType())
  }
}
