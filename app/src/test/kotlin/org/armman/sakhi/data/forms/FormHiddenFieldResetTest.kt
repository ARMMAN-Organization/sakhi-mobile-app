package org.armman.sakhi.data.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormHiddenFieldResetTest {

  private fun numberField(questionCode: String, visibleWhen: FormVisibleWhen? = null) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "number",
    questionCode = questionCode,
    visibleWhen = visibleWhen,
  )

  private fun multiselectField(questionCode: String, visibleWhen: FormVisibleWhen? = null) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "multiselect",
    questionCode = questionCode,
    visibleWhen = visibleWhen,
  )

  /** The real schema gate for the prior-pregnancy block: visible only from Gravida 2 up, since a
   * first pregnancy has no prior outcome to report. `gte` is inclusive, so the threshold is "2" —
   * that is what makes dropping Gravida 2 → 1 hide the block, which is the reported bug. */
  private val gravidaGate = FormVisibleWhen(field = "gravida", value = "2", operator = "gte")

  @Test
  fun `a field going from visible to hidden resets - the reported Gravida bug`() {
    val fields = listOf(numberField(FormObstetricRuleset.STILL_BIRTHS, gravidaGate))
    val previous = FormAnswers(singleValues = mapOf("gravida" to "2", FormObstetricRuleset.STILL_BIRTHS to "1"))
    val updated = previous.withSingleValue("gravida", "1")

    val result = FormHiddenFieldReset.apply(fields, previous, updated)

    // still_births is a RESET_TO_ZERO field: cleared to "0", not left at the stale "1".
    assertEquals("0", result.valueOf(FormObstetricRuleset.STILL_BIRTHS))
  }

  @Test
  fun `all five obstetric count fields reset to zero, not blank`() {
    val codes = listOf(
      FormObstetricRuleset.PARA,
      FormObstetricRuleset.LIVING_CHILDREN,
      FormObstetricRuleset.ABORTIONS,
      FormObstetricRuleset.STILL_BIRTHS,
      FormObstetricRuleset.DEAD_CHILDREN,
    )
    val fields = codes.map { numberField(it, gravidaGate) }
    val previous = FormAnswers(
      singleValues = codes.associateWith { "3" } + ("gravida" to "2"),
    )
    val updated = previous.withSingleValue("gravida", "1")

    val result = FormHiddenFieldReset.apply(fields, previous, updated)

    codes.forEach { code -> assertEquals("0", result.valueOf(code)) }
  }

  @Test
  fun `a non-obstetric field that hides is cleared blank, not zeroed`() {
    val fields = listOf(numberField("some_other_field", gravidaGate))
    val previous = FormAnswers(singleValues = mapOf("gravida" to "2", "some_other_field" to "5"))
    val updated = previous.withSingleValue("gravida", "1")

    val result = FormHiddenFieldReset.apply(fields, previous, updated)

    assertNull(result.valueOf("some_other_field"))
  }

  @Test
  fun `a multiselect field that hides is cleared too`() {
    val fields = listOf(multiselectField(MotherRegistrationQuestionCodes.PREVIOUS_DELIVERY_COMPLICATIONS, gravidaGate))
    val previous = FormAnswers(
      singleValues = mapOf("gravida" to "2"),
      multiValues = mapOf(MotherRegistrationQuestionCodes.PREVIOUS_DELIVERY_COMPLICATIONS to listOf("yes_miscarriage")),
    )
    val updated = previous.withSingleValue("gravida", "1")

    val result = FormHiddenFieldReset.apply(fields, previous, updated)

    assertEquals(emptyList<String>(), result.multiValueOf(MotherRegistrationQuestionCodes.PREVIOUS_DELIVERY_COMPLICATIONS))
  }

  @Test
  fun `a field that stays visible is untouched`() {
    val fields = listOf(numberField(FormObstetricRuleset.STILL_BIRTHS, gravidaGate))
    val previous = FormAnswers(singleValues = mapOf("gravida" to "3", FormObstetricRuleset.STILL_BIRTHS to "1"))
    val updated = previous.withSingleValue("gravida", "2") // still >= 1, field stays visible

    val result = FormHiddenFieldReset.apply(fields, previous, updated)

    assertEquals("1", result.valueOf(FormObstetricRuleset.STILL_BIRTHS))
  }

  @Test
  fun `a field that was already hidden and stays hidden is untouched`() {
    val fields = listOf(numberField(FormObstetricRuleset.STILL_BIRTHS, gravidaGate))
    val previous = FormAnswers(singleValues = mapOf("gravida" to "1", FormObstetricRuleset.STILL_BIRTHS to "0"))
    val updated = previous.withSingleValue("some_unrelated_field", "x")

    val result = FormHiddenFieldReset.apply(fields, previous, updated)

    assertEquals("0", result.valueOf(FormObstetricRuleset.STILL_BIRTHS))
  }

  @Test
  fun `a field becoming visible again is one-directional - it is not restored, per bharath's call`() {
    // Field hides (resets to zero)...
    val fields = listOf(numberField(FormObstetricRuleset.STILL_BIRTHS, gravidaGate))
    val hidden = FormHiddenFieldReset.apply(
      fields,
      previousAnswers = FormAnswers(singleValues = mapOf("gravida" to "2", FormObstetricRuleset.STILL_BIRTHS to "1")),
      updatedAnswers = FormAnswers(singleValues = mapOf("gravida" to "1", FormObstetricRuleset.STILL_BIRTHS to "1")),
    )
    assertEquals("0", hidden.valueOf(FormObstetricRuleset.STILL_BIRTHS))

    // ...then reappears. apply() only acts on visible->hidden transitions, so a reappearing field
    // is left exactly as it already is (zero, from the reset above) - nothing "restores" it.
    val reappeared = FormHiddenFieldReset.apply(
      fields,
      previousAnswers = hidden.withSingleValue("gravida", "1"),
      updatedAnswers = hidden.withSingleValue("gravida", "2"),
    )
    assertEquals("0", reappeared.valueOf(FormObstetricRuleset.STILL_BIRTHS))
  }

  @Test
  fun `newlyHiddenQuestionCodes matches exactly what apply resets`() {
    val fields = listOf(
      numberField(FormObstetricRuleset.STILL_BIRTHS, gravidaGate),
      numberField("visible_field", null),
    )
    val previous = FormAnswers(singleValues = mapOf("gravida" to "2", FormObstetricRuleset.STILL_BIRTHS to "1"))
    val updated = previous.withSingleValue("gravida", "1")

    val hidden = FormHiddenFieldReset.newlyHiddenQuestionCodes(fields, previous, updated)

    assertEquals(setOf(FormObstetricRuleset.STILL_BIRTHS), hidden)
  }
}
