package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.NeonatalVisitQuestionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors [DeliveryToChildRegistrationPrefillTest]'s shape — same [FormAnswers] fixture pattern,
 * same "omitted, not defaulted" convention for unanswered fields. */
class DeliveryToNeonatalPrefillTest {

  private fun deliveryAnswers(
    singleValues: Map<String, String> = emptyMap(),
    multiValues: Map<String, List<String>> = emptyMap(),
  ) = FormAnswers(singleValues = singleValues, multiValues = multiValues)

  @Test
  fun `singleValueAnswersFor copies term_of_delivery verbatim`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(DeliveryQuestionCodes.TERM_OF_DELIVERY to "full_term"),
    )

    val values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers)

    assertEquals("full_term", values[NeonatalVisitQuestionCodes.TERM_OF_DELIVERY])
  }

  @Test
  fun `singleValueAnswersFor reads child1_birth_weight_kg into birth_weight_kg for the default childIndex`() {
    val answers = deliveryAnswers(
      singleValues = mapOf("child1_birth_weight_kg" to "2.9"),
    )

    val values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers)

    assertEquals("2.9", values[NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG])
  }

  @Test
  fun `singleValueAnswersFor reads the birth weight of the given childIndex, not always child1`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(
        "child1_birth_weight_kg" to "2.9",
        "child2_birth_weight_kg" to "3.1",
      ),
    )

    val child1Values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers, childIndex = 0)
    val child2Values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers, childIndex = 1)

    assertEquals("2.9", child1Values[NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG])
    assertEquals("3.1", child2Values[NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG])
  }

  @Test
  fun `singleValueAnswersFor on an empty delivery answers returns an empty map`() {
    val values = DeliveryToNeonatalPrefill.singleValueAnswersFor(deliveryAnswers())

    assertTrue(values.isEmpty())
  }

  @Test
  fun `singleValueAnswersFor omits birth_weight_kg entirely when the delivery form never answered it`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(DeliveryQuestionCodes.TERM_OF_DELIVERY to "pre_term"),
    )

    val values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers)

    assertEquals("pre_term", values[NeonatalVisitQuestionCodes.TERM_OF_DELIVERY])
    assertNull(values[NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG])
  }

  @Test
  fun `singleValueAnswersFor omits term_of_delivery entirely when the delivery form never answered it`() {
    val answers = deliveryAnswers(
      singleValues = mapOf("child1_birth_weight_kg" to "2.9"),
    )

    val values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers)

    assertEquals("2.9", values[NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG])
    assertNull(values[NeonatalVisitQuestionCodes.TERM_OF_DELIVERY])
  }

  @Test
  fun `singleValueAnswersFor returns both fields when both are answered`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(
        DeliveryQuestionCodes.TERM_OF_DELIVERY to "post_term",
        "child1_birth_weight_kg" to "3.4",
      ),
    )

    val values = DeliveryToNeonatalPrefill.singleValueAnswersFor(answers)

    assertEquals(
      mapOf(
        NeonatalVisitQuestionCodes.TERM_OF_DELIVERY to "post_term",
        NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG to "3.4",
      ),
      values,
    )
  }
}
