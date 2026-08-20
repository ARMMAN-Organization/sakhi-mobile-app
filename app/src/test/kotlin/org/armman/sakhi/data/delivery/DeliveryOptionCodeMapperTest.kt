package org.armman.sakhi.data.delivery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Confirmed 2026-08-19 against both forms' live `GET /forms/{code}/active-version` payloads —
 * see [DeliveryOptionCodeMapper]'s own doc for the exact mismatch counts. */
class DeliveryOptionCodeMapperTest {

  @Test
  fun `place_of_delivery maps the 2 known mismatched codes`() {
    assertEquals(
      "district_hospital_civil_hospital",
      DeliveryOptionCodeMapper.placeOfDeliveryForChildRegistration("district_hospital"),
    )
    assertEquals(
      "sub_district_hopital",
      DeliveryOptionCodeMapper.placeOfDeliveryForChildRegistration("sub_district_hospital"),
    )
  }

  @Test
  fun `place_of_delivery passes through the 6 identical codes unchanged`() {
    listOf("rural_hospital", "phc", "sc", "pvt_hospital", "home", "transit").forEach { code ->
      assertEquals(code, DeliveryOptionCodeMapper.placeOfDeliveryForChildRegistration(code))
    }
  }

  @Test
  fun `who_conducted_the_delivery maps the 1 known mismatched code`() {
    assertEquals(
      "other_mention",
      DeliveryOptionCodeMapper.whoConductedTheDeliveryForChildRegistration("other"),
    )
  }

  @Test
  fun `who_conducted_the_delivery passes through the 6 identical codes unchanged`() {
    listOf("specialist", "medical_officer", "staffnurse", "anm", "lhv", "dai").forEach { code ->
      assertEquals(code, DeliveryOptionCodeMapper.whoConductedTheDeliveryForChildRegistration(code))
    }
  }

  @Test
  fun `an unrecognized code is passed through unchanged rather than dropped`() {
    assertEquals(
      "some_future_option",
      DeliveryOptionCodeMapper.placeOfDeliveryForChildRegistration("some_future_option"),
    )
    assertEquals(
      "some_future_option",
      DeliveryOptionCodeMapper.whoConductedTheDeliveryForChildRegistration("some_future_option"),
    )
  }

  @Test
  fun `birth complications maps the 1 known mismatched code and passes through the rest`() {
    assertEquals(
      listOf("birth_asphyxia", "other_please_specify"),
      DeliveryOptionCodeMapper.birthComplicationsForChildRegistration(listOf("birth_asphyxia", "other")),
    )
  }

  @Test
  fun `birth complications passes through the 5 identical codes unchanged`() {
    val identical = listOf(
      "birth_asphyxia",
      "fetal_distress",
      "birth_trauma",
      "neonatal_infections",
      "congenital_anomalies_detected",
    )
    assertEquals(identical, DeliveryOptionCodeMapper.birthComplicationsForChildRegistration(identical))
  }

  @Test
  fun `birth complications on an empty list returns an empty list`() {
    assertEquals(emptyList<String>(), DeliveryOptionCodeMapper.birthComplicationsForChildRegistration(emptyList()))
  }
}
