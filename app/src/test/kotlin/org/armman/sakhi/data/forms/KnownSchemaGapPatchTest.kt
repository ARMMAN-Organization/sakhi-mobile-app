package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Bug fix (2026-09-04): "USG follow-up question remains visible after selecting 'No'". The live
 * ANC_VISIT schema (v9) wires `if_yes_date_of_usg`/`type_of_usg`/`usg_finding` to the generic
 * "met the beneficiary" gate instead of `have_you_done_usg_since_last_visit` — see
 * [KnownSchemaGapPatch]'s own doc for the full root-cause writeup and the residual
 * backend-submission risk this patch does not close.
 */
class KnownSchemaGapPatchTest {

  private val wrongVisibleWhen = FormVisibleWhen(
    field = "have_you_been_able_to_meet_the_beneficiary_for_the_visit",
    value = "yes",
    operator = "eq",
  )

  private fun field(questionCode: String, visibleWhen: FormVisibleWhen?) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "text",
    questionCode = questionCode,
    visibleWhen = visibleWhen,
  )

  private fun version(fields: List<FormFieldSchema>) = FormVersion(
    id = "v1",
    formDefinitionId = "def-1",
    versionNo = "v9",
    schemaJson = fields,
    validationJson = emptyList(),
    effectiveFrom = "2026-08-08T05:17:18.425Z",
    effectiveTo = null,
    status = "PUBLISHED",
  )

  @Test
  fun `re-gates the three USG follow-up fields onto have_you_done_usg_since_last_visit`() {
    val original = version(
      listOf(
        field("have_you_done_usg_since_last_visit", wrongVisibleWhen),
        field("if_yes_date_of_usg", wrongVisibleWhen),
        field("type_of_usg", wrongVisibleWhen),
        field("usg_finding", wrongVisibleWhen),
        field("remarks", wrongVisibleWhen),
      ),
    )

    val patched = KnownSchemaGapPatch.apply("ANC_VISIT", original)

    val byCode = patched.schemaJson.associateBy { it.questionCode }
    val expected = FormVisibleWhen("have_you_done_usg_since_last_visit", "yes", "eq")
    assertEquals(expected, byCode.getValue("if_yes_date_of_usg").visibleWhen)
    assertEquals(expected, byCode.getValue("type_of_usg").visibleWhen)
    assertEquals(expected, byCode.getValue("usg_finding").visibleWhen)
  }

  @Test
  fun `leaves unrelated fields and the gate question itself untouched`() {
    val original = version(
      listOf(
        field("have_you_done_usg_since_last_visit", wrongVisibleWhen),
        field("if_yes_date_of_usg", wrongVisibleWhen),
        field("remarks", wrongVisibleWhen),
      ),
    )

    val patched = KnownSchemaGapPatch.apply("ANC_VISIT", original)
    val byCode = patched.schemaJson.associateBy { it.questionCode }

    assertEquals(wrongVisibleWhen, byCode.getValue("have_you_done_usg_since_last_visit").visibleWhen)
    assertEquals(wrongVisibleWhen, byCode.getValue("remarks").visibleWhen)
  }

  @Test
  fun `is a no-op once the backend schema is already correct`() {
    // Simulates ARMMAN republishing the schema with the real fix baked in -- the patch must not
    // fight a correct live condition or re-derive a stale one.
    val alreadyCorrect = FormVisibleWhen("have_you_done_usg_since_last_visit", "yes", "eq")
    val original = version(
      listOf(
        field("have_you_done_usg_since_last_visit", wrongVisibleWhen),
        field("if_yes_date_of_usg", alreadyCorrect),
        field("type_of_usg", alreadyCorrect),
        field("usg_finding", alreadyCorrect),
      ),
    )

    val patched = KnownSchemaGapPatch.apply("ANC_VISIT", original)

    assertSame(original, patched)
  }

  @Test
  fun `does not touch any other form code`() {
    val original = version(listOf(field("if_yes_date_of_usg", wrongVisibleWhen)))

    val patched = KnownSchemaGapPatch.apply("CHILD_REGISTRATION", original)

    assertSame(original, patched)
  }
}
