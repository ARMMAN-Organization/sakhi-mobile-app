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
  fun `a genuinely unrecognized input_type still falls back to UNKNOWN`() {
    assertEquals(FormFieldInputType.UNKNOWN, "some_future_type".toFormFieldInputType())
  }
}
