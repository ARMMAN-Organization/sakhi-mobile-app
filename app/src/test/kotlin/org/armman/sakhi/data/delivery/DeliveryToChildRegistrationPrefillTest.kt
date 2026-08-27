package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.forms.BeneficiaryNameQuestionCodes
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.DOB_QUESTION_CODE
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.motherlink.MotherPrefillQuestionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliveryToChildRegistrationPrefillTest {

  private fun deliveryAnswers(
    singleValues: Map<String, String> = emptyMap(),
    multiValues: Map<String, List<String>> = emptyMap(),
  ) = FormAnswers(singleValues = singleValues, multiValues = multiValues)

  @Test
  fun `singleValueAnswersFor always hardcodes WHO_ARE_YOU_REGISTERING to the registered-mother path`() {
    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(deliveryAnswers(), childIndex = 0)

    assertEquals(
      ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
      values[ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING],
    )
  }

  @Test
  fun `singleValueAnswersFor on an empty delivery answers still returns just the two hardcoded fields`() {
    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(deliveryAnswers(), childIndex = 0)

    assertEquals(
      mapOf(
        ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING to ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
        MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID to "1",
      ),
      values,
    )
  }

  @Test
  fun `singleValueAnswersFor always seeds mother_beneficiary_id with a placeholder, never blank`() {
    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(deliveryAnswers(), childIndex = 0)

    assertEquals("1", values[MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID])
  }

  @Test
  fun `singleValueAnswersFor copies a shipped mother geography id onto the matching question code`() {
    val motherAnswers = FormAnswers(
      singleValues = mapOf(GeographyQuestionCodes.STATE to "state-1", GeographyQuestionCodes.PADA to "pada-1"),
    )
    val geography = listOf(
      FormGeographyUnit(geographyUnitId = "state-1", geoType = "STATE", name = "Maharashtra"),
      FormGeographyUnit(geographyUnitId = "pada-1", geoType = "PADA", name = "Toranmal"),
    )

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
      motherGeography = geography,
    )

    assertEquals("state-1", values[GeographyQuestionCodes.STATE])
    assertEquals("pada-1", values[GeographyQuestionCodes.PADA])
  }

  @Test
  fun `singleValueAnswersFor skips a mother geography id CHILD_REGISTRATION's active version never shipped`() {
    val motherAnswers = FormAnswers(singleValues = mapOf(GeographyQuestionCodes.STATE to "state-not-shipped"))

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
      motherGeography = emptyList(),
    )

    assertNull(values[GeographyQuestionCodes.STATE])
  }

  @Test
  fun `singleValueAnswersFor skips a mother geography id whose geoType doesn't match the question code's level`() {
    // Same id string, wrong geoType (a village id in the state slot) — must not match.
    val motherAnswers = FormAnswers(singleValues = mapOf(GeographyQuestionCodes.STATE to "unit-1"))
    val geography = listOf(FormGeographyUnit(geographyUnitId = "unit-1", geoType = "VILLAGE", name = "Somewhere"))

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
      motherGeography = geography,
    )

    assertNull(values[GeographyQuestionCodes.STATE])
  }

  @Test
  fun `singleValueAnswersFor prefills no geography at all when motherAnswers is null`() {
    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = null,
      motherGeography = listOf(FormGeographyUnit(geographyUnitId = "state-1", geoType = "STATE", name = "Maharashtra")),
    )

    assertNull(values[GeographyQuestionCodes.STATE])
  }

  @Test
  fun `singleValueAnswersFor copies shared delivery-date and delivery-type fields verbatim`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(
        DeliveryQuestionCodes.DATE_OF_DELIVERY to "2026-08-01",
        DeliveryQuestionCodes.TERM_OF_DELIVERY to "full_term",
        DeliveryQuestionCodes.TYPE_OF_DELIVERY to "normal",
      ),
    )

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(answers, childIndex = 0)

    assertEquals("2026-08-01", values[ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT])
    assertEquals("full_term", values[DeliveryQuestionCodes.TERM_OF_DELIVERY])
    assertEquals("normal", values[DeliveryQuestionCodes.TYPE_OF_DELIVERY])
  }

  @Test
  fun `singleValueAnswersFor maps place_of_delivery and who_conducted_the_delivery through DeliveryOptionCodeMapper`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(
        DeliveryQuestionCodes.PLACE_OF_DELIVERY to "district_hospital",
        DeliveryQuestionCodes.WHO_CONDUCTED_THE_DELIVERY to "other",
      ),
    )

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(answers, childIndex = 0)

    assertEquals("district_hospital_civil_hospital", values[DeliveryQuestionCodes.PLACE_OF_DELIVERY])
    assertEquals("other_mention", values[DeliveryQuestionCodes.WHO_CONDUCTED_THE_DELIVERY])
  }

  @Test
  fun `singleValueAnswersFor reads the per-child fields at the given childIndex, not always child1`() {
    val answers = deliveryAnswers(
      singleValues = mapOf(
        "child1_sex_of_baby" to "male",
        "child1_birth_length_cm" to "48",
        "child1_birth_weight_kg" to "2.9",
        "child2_sex_of_baby" to "female",
        "child2_birth_length_cm" to "47",
        "child2_birth_weight_kg" to "2.7",
      ),
    )

    val child1Values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(answers, childIndex = 0)
    val child2Values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(answers, childIndex = 1)

    assertEquals("male", child1Values["sex_of_child"])
    assertEquals("48", child1Values["child_length_at_birth_in_cm"])
    assertEquals("2.9", child1Values[ChildRegistrationQuestionCodes.CHILD_WEIGHT_AT_BIRTH_KG])

    assertEquals("female", child2Values["sex_of_child"])
    assertEquals("47", child2Values["child_length_at_birth_in_cm"])
    assertEquals("2.7", child2Values[ChildRegistrationQuestionCodes.CHILD_WEIGHT_AT_BIRTH_KG])
  }

  @Test
  fun `singleValueAnswersFor omits a field entirely when the delivery form never answered it`() {
    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(deliveryAnswers(), childIndex = 0)

    assertNull(values[ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT])
    assertNull(values["sex_of_child"])
  }

  @Test
  fun `multiValueAnswersFor maps childN_related_complications through the birth-complications mapper`() {
    val answers = deliveryAnswers(
      multiValues = mapOf("child1_related_complications" to listOf("birth_asphyxia", "other")),
    )

    val values = DeliveryToChildRegistrationPrefill.multiValueAnswersFor(answers, childIndex = 0)

    assertEquals(
      listOf("birth_asphyxia", "other_please_specify"),
      values["did_the_baby_have_any_complications_at_the_time_of_birth"],
    )
  }

  @Test
  fun `multiValueAnswersFor is empty when the delivery form recorded no complications for that child`() {
    val values = DeliveryToChildRegistrationPrefill.multiValueAnswersFor(deliveryAnswers(), childIndex = 0)

    assertTrue(values.isEmpty())
  }

  @Test
  fun `multiValueAnswersFor reads the correct child's own complications, not another child's`() {
    val answers = deliveryAnswers(
      multiValues = mapOf(
        "child1_related_complications" to listOf("birth_asphyxia"),
        "child2_related_complications" to listOf("fetal_distress"),
      ),
    )

    val child1Values = DeliveryToChildRegistrationPrefill.multiValueAnswersFor(answers, childIndex = 0)
    val child2Values = DeliveryToChildRegistrationPrefill.multiValueAnswersFor(answers, childIndex = 1)

    assertEquals(listOf("birth_asphyxia"), child1Values["did_the_baby_have_any_complications_at_the_time_of_birth"])
    assertEquals(listOf("fetal_distress"), child2Values["did_the_baby_have_any_complications_at_the_time_of_birth"])
  }

  @Test
  fun `singleValueAnswersFor copies the mother's combined name onto caregiver_name`() {
    val motherAnswers = FormAnswers(singleValues = mapOf(BeneficiaryNameQuestionCodes.CURRENT to "Asha Patil"))

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
    )

    assertEquals("Asha Patil", values[MotherPrefillQuestionCodes.CAREGIVER_NAME])
  }

  @Test
  fun `singleValueAnswersFor falls back to the split first-middle-last name when no combined answer exists`() {
    val motherAnswers = FormAnswers(
      singleValues = mapOf("first_name" to "Asha", "middle_name" to "", "last_name" to "Patil"),
    )

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
    )

    assertEquals("Asha Patil", values[MotherPrefillQuestionCodes.CAREGIVER_NAME])
  }

  @Test
  fun `singleValueAnswersFor copies the mother's DOB and derives mother_age from it`() {
    val motherAnswers = FormAnswers(singleValues = mapOf(DOB_QUESTION_CODE to "1996-01-01"))

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
      registrationDate = LocalDate.of(2026, 8, 19),
    )

    assertEquals("1996-01-01", values[MotherPrefillQuestionCodes.MOTHER_DATE_OF_BIRTH])
    assertEquals("30", values[MotherPrefillQuestionCodes.MOTHER_AGE])
  }

  @Test
  fun `singleValueAnswersFor copies the mother's own consent only when she answered yes`() {
    val yesAnswers = FormAnswers(singleValues = mapOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT to "yes"))
    val noAnswers = FormAnswers(singleValues = mapOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT to "no"))

    val yesValues = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = yesAnswers,
    )
    val noValues = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = noAnswers,
    )

    assertEquals("yes", yesValues[MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT])
    assertNull("a 'no' consent must never be auto-copied onto the child's registration", noValues[MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT])
  }

  @Test
  fun `singleValueAnswersFor copies mobile number, address and household socio-demographics verbatim`() {
    val motherAnswers = FormAnswers(
      singleValues = mapOf(
        MotherPrefillQuestionCodes.MOBILE_NUMBER to "9876543210",
        MotherPrefillQuestionCodes.ADDRESS to "House 12, Toranmal",
        MotherPrefillQuestionCodes.RELIGION to "hindu",
        MotherPrefillQuestionCodes.EDUCATION_LEVEL to "graduate",
        MotherPrefillQuestionCodes.FAMILY_MEMBERS_COUNT to "5",
      ),
    )

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
    )

    assertEquals("9876543210", values[MotherPrefillQuestionCodes.MOBILE_NUMBER])
    assertEquals("House 12, Toranmal", values[MotherPrefillQuestionCodes.ADDRESS])
    assertEquals("hindu", values[MotherPrefillQuestionCodes.RELIGION])
    assertEquals("graduate", values[MotherPrefillQuestionCodes.EDUCATION_LEVEL])
    assertEquals("5", values[MotherPrefillQuestionCodes.FAMILY_MEMBERS_COUNT])
  }

  @Test
  fun `singleValueAnswersFor prefills none of the mother-detail fields when motherAnswers is null`() {
    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(deliveryAnswers(), childIndex = 0)

    assertNull(values[MotherPrefillQuestionCodes.CAREGIVER_NAME])
    assertNull(values[MotherPrefillQuestionCodes.MOTHER_DATE_OF_BIRTH])
    assertNull(values[MotherPrefillQuestionCodes.MOTHER_AGE])
    assertNull(values[MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT])
    assertNull(values[MotherPrefillQuestionCodes.MOBILE_NUMBER])
    assertNull(values[MotherPrefillQuestionCodes.ADDRESS])
  }

  @Test
  fun `singleValueAnswersFor skips a mother-detail field the mother's own answers never carry`() {
    val motherAnswers = FormAnswers(singleValues = mapOf(GeographyQuestionCodes.STATE to "state-1"))

    val values = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
      deliveryAnswers(),
      childIndex = 0,
      motherAnswers = motherAnswers,
    )

    assertNull(values[MotherPrefillQuestionCodes.RELIGION])
    assertNull(values[MotherPrefillQuestionCodes.CAREGIVER_NAME])
  }
}
