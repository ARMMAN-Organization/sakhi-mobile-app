package org.armman.sakhi.data.forms

import org.armman.sakhi.data.forms.FormObstetricRuleset.ABORTIONS
import org.armman.sakhi.data.forms.FormObstetricRuleset.DEAD_CHILDREN
import org.armman.sakhi.data.forms.FormObstetricRuleset.GRAVIDA
import org.armman.sakhi.data.forms.FormObstetricRuleset.LIVING_CHILDREN
import org.armman.sakhi.data.forms.FormObstetricRuleset.PARA
import org.armman.sakhi.data.forms.FormObstetricRuleset.STILL_BIRTHS
import org.armman.sakhi.data.forms.FormObstetricRuleset.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules must stay in lock-step with [DynamicFormSubmissionMapper]'s own cross-field check,
 * which throws before the request is sent. A UI rule looser than that one lets a form reach a submit
 * it cannot pass; a stricter one blocks a form the API would accept. `agrees with the submission
 * mapper` guards exactly that.
 */
class FormObstetricRulesetTest {

  private fun answers(vararg pairs: Pair<String, String>): FormAnswers =
    pairs.fold(FormAnswers()) { acc, (code, value) -> acc.withSingleValue(code, value) }

  private fun numberField(questionCode: String) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "number",
    questionCode = questionCode,
  )

  private val allFields =
    listOf(GRAVIDA, PARA, LIVING_CHILDREN, ABORTIONS, STILL_BIRTHS, DEAD_CHILDREN).map(::numberField)

  // --- Gravida total: living children + still births + abortions == gravida ---------------------

  @Test
  fun `the reported case is flagged on gravida`() {
    // QA's screenshot: Gravida 6 with every other figure 0, which totals 0.
    val entered = answers(
      GRAVIDA to "6",
      PARA to "0",
      LIVING_CHILDREN to "0",
      ABORTIONS to "0",
      STILL_BIRTHS to "0",
      DEAD_CHILDREN to "0",
    )

    assertEquals(Violation.GRAVIDA_TOTAL, FormObstetricRuleset.violationFor(GRAVIDA, entered))
    assertEquals(0, FormObstetricRuleset.expectedGravida(entered))
    assertFalse(FormObstetricRuleset.allValid(allFields, entered))
  }

  @Test
  fun `a consistent history passes`() {
    val entered = answers(
      GRAVIDA to "4",
      PARA to "3",
      LIVING_CHILDREN to "2",
      ABORTIONS to "1",
      STILL_BIRTHS to "1",
      DEAD_CHILDREN to "1",
    )

    assertTrue(FormObstetricRuleset.allValid(allFields, entered))
    assertEquals(4, FormObstetricRuleset.expectedGravida(entered))
  }

  @Test
  fun `a first pregnancy with everything else zero is valid`() {
    // Gravida counts the current pregnancy in the spec but not in the API's sum rule, so a first
    // pregnancy is recorded as Gravida 0 here. Flagged in FormObstetricRuleset's doc as the open
    // spec conflict; this test pins today's enforced behaviour.
    val entered = answers(
      GRAVIDA to "0",
      PARA to "0",
      LIVING_CHILDREN to "0",
      ABORTIONS to "0",
      STILL_BIRTHS to "0",
      DEAD_CHILDREN to "0",
    )

    assertTrue(FormObstetricRuleset.allValid(allFields, entered))
  }

  @Test
  fun `agrees with the submission mapper on the gravida total`() {
    // Same arithmetic the mapper applies: living + stillbirths + abortions != gravida.
    listOf(
      Triple("2", "1", "1") to 4,
      Triple("0", "0", "0") to 0,
      Triple("5", "2", "3") to 10,
    ).forEach { (parts, expectedGravida) ->
      val (living, still, abortions) = parts
      val consistent = answers(
        GRAVIDA to expectedGravida.toString(),
        LIVING_CHILDREN to living,
        STILL_BIRTHS to still,
        ABORTIONS to abortions,
      )
      assertNull(FormObstetricRuleset.violationFor(GRAVIDA, consistent))

      val inconsistent = consistent.withSingleValue(GRAVIDA, (expectedGravida + 1).toString())
      assertEquals(Violation.GRAVIDA_TOTAL, FormObstetricRuleset.violationFor(GRAVIDA, inconsistent))
    }
  }

  // --- Para and abortions cannot exceed Gravida --------------------------------------------------

  @Test
  fun `para above gravida is flagged on para`() {
    val entered = answers(GRAVIDA to "2", PARA to "3")

    assertEquals(Violation.PARA_EXCEEDS_GRAVIDA, FormObstetricRuleset.violationFor(PARA, entered))
  }

  @Test
  fun `abortions above gravida is flagged on abortions`() {
    val entered = answers(GRAVIDA to "2", ABORTIONS to "5")

    assertEquals(Violation.ABORTIONS_EXCEED_GRAVIDA, FormObstetricRuleset.violationFor(ABORTIONS, entered))
  }

  @Test
  fun `para equal to gravida is allowed`() {
    assertNull(FormObstetricRuleset.violationFor(PARA, answers(GRAVIDA to "3", PARA to "3")))
  }

  // --- Dead children cannot exceed living children -----------------------------------------------

  @Test
  fun `dead children above living children is flagged on dead children`() {
    val entered = answers(LIVING_CHILDREN to "2", DEAD_CHILDREN to "3")

    assertEquals(
      Violation.DEAD_CHILDREN_EXCEED_LIVING,
      FormObstetricRuleset.violationFor(DEAD_CHILDREN, entered),
    )
  }

  @Test
  fun `dead children equal to living children is allowed`() {
    assertNull(FormObstetricRuleset.violationFor(DEAD_CHILDREN, answers(LIVING_CHILDREN to "2", DEAD_CHILDREN to "2")))
  }

  // --- Partially filled forms stay quiet -------------------------------------------------------

  @Test
  fun `a rule stays quiet until every figure it needs is entered`() {
    // Mid-entry: Gravida typed, the parts still blank. Firing here would train the Sakhi to ignore
    // errors.
    val gravidaOnly = answers(GRAVIDA to "6")
    assertNull(FormObstetricRuleset.violationFor(GRAVIDA, gravidaOnly))
    assertNull(FormObstetricRuleset.expectedGravida(gravidaOnly))
    assertTrue(FormObstetricRuleset.allValid(allFields, gravidaOnly))

    val partBlank = answers(GRAVIDA to "6", LIVING_CHILDREN to "", STILL_BIRTHS to "0", ABORTIONS to "0")
    assertNull(FormObstetricRuleset.violationFor(GRAVIDA, partBlank))
  }

  @Test
  fun `an unparseable figure is not treated as a rule violation`() {
    val entered = answers(GRAVIDA to "6", LIVING_CHILDREN to "two", STILL_BIRTHS to "0", ABORTIONS to "0")

    assertNull(FormObstetricRuleset.violationFor(GRAVIDA, entered))
  }

  // --- Out of scope -----------------------------------------------------------------------------

  @Test
  fun `fields outside the obstetric set are never flagged`() {
    val entered = answers(GRAVIDA to "6", LIVING_CHILDREN to "0", STILL_BIRTHS to "0", ABORTIONS to "0")

    assertNull(FormObstetricRuleset.violationFor("height_cm", entered))
    assertNull(FormObstetricRuleset.violationFor("mobile_number", entered))
  }

  @Test
  fun `living children below para is not an error`() {
    // Spec row 47 classes "L < P" as high RISK, not a validation failure — flagging it would block
    // registering exactly the pregnancy the risk logic exists to escalate.
    val entered = answers(
      GRAVIDA to "2",
      PARA to "2",
      LIVING_CHILDREN to "0",
      STILL_BIRTHS to "2",
      ABORTIONS to "0",
      DEAD_CHILDREN to "0",
    )

    assertTrue(FormObstetricRuleset.allValid(allFields, entered))
  }

  @Test
  fun `two or more abortions is not an error`() {
    // Spec row 48 classes ">= 2" as high risk, again not a validation failure.
    val entered = answers(
      GRAVIDA to "3",
      PARA to "1",
      LIVING_CHILDREN to "1",
      STILL_BIRTHS to "0",
      ABORTIONS to "2",
      DEAD_CHILDREN to "0",
    )

    assertTrue(FormObstetricRuleset.allValid(allFields, entered))
  }
}
