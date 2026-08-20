package org.armman.sakhi.data.delivery

/**
 * Translates `value_code`s that are shared in *concept* between `DELIVERY_VISIT` and
 * `CHILD_REGISTRATION` but differ in the actual on-the-wire code between the two forms' schemas —
 * confirmed 2026-08-19 against both forms' live `GET /forms/{code}/active-version` payloads
 * (`DELIVERY_VISIT` v1, `CHILD_REGISTRATION` v6). A verbatim copy of either field from a
 * `DELIVERY_VISIT` submission straight into a `CHILD_REGISTRATION` draft would silently write an
 * invalid or mismatched option code.
 *
 * `type_of_delivery` (`caesarian`/`normal`/`assisted_forcep_vacuum`) and `term_of_delivery`
 * (`pre_term`/`post_term`/`full_term`) were checked too and are byte-identical between the two
 * schemas — safe to copy verbatim, so they have no entry (and no mapping function) here.
 */
object DeliveryOptionCodeMapper {

  /**
   * `place_of_delivery`: 2 of 8 options differ. `sub_district_hopital` (the CHILD_REGISTRATION
   * side) reproduces a real backend typo verbatim — "fixing" it here would silently stop this
   * mapping from matching the live option code. The other 6 options
   * (`rural_hospital`/`phc`/`sc`/`pvt_hospital`/`home`/`transit`) are identical and therefore not
   * listed — [placeOfDeliveryForChildRegistration] passes them through unchanged.
   */
  private val PLACE_OF_DELIVERY_TO_CHILD_REGISTRATION: Map<String, String> = mapOf(
    "district_hospital" to "district_hospital_civil_hospital",
    "sub_district_hospital" to "sub_district_hopital",
  )

  /** `who_conducted_the_delivery`: 1 of 7 options differs. The other 6
   * (`specialist`/`medical_officer`/`staffnurse`/`anm`/`lhv`/`dai`) are identical. */
  private val WHO_CONDUCTED_THE_DELIVERY_TO_CHILD_REGISTRATION: Map<String, String> = mapOf(
    "other" to "other_mention",
  )

  /**
   * `did_the_baby_have_any_complications_at_the_time_of_birth` (CHILD_REGISTRATION) vs
   * `childN_related_complications` (DELIVERY_VISIT): 1 of 6 options differs. The other 5
   * (`birth_asphyxia`/`fetal_distress`/`birth_trauma`/`neonatal_infections`/
   * `congenital_anomalies_detected`) are identical.
   */
  private val BIRTH_COMPLICATIONS_TO_CHILD_REGISTRATION: Map<String, String> = mapOf(
    "other" to "other_please_specify",
  )

  /** [deliveryVisitCode] (a `DELIVERY_VISIT` `place_of_delivery` answer) -> the equivalent
   * `CHILD_REGISTRATION` option code, or [deliveryVisitCode] itself unchanged when the two schemas
   * already agree on this value. */
  fun placeOfDeliveryForChildRegistration(deliveryVisitCode: String): String =
    PLACE_OF_DELIVERY_TO_CHILD_REGISTRATION[deliveryVisitCode] ?: deliveryVisitCode

  /** [deliveryVisitCode] (a `DELIVERY_VISIT` `who_conducted_the_delivery` answer) -> the
   * equivalent `CHILD_REGISTRATION` option code, or [deliveryVisitCode] itself unchanged when the
   * two schemas already agree on this value. */
  fun whoConductedTheDeliveryForChildRegistration(deliveryVisitCode: String): String =
    WHO_CONDUCTED_THE_DELIVERY_TO_CHILD_REGISTRATION[deliveryVisitCode] ?: deliveryVisitCode

  /** [deliveryVisitCodes] (a `DELIVERY_VISIT` `childN_related_complications` multiselect answer)
   * -> the equivalent `CHILD_REGISTRATION` option codes, mapping each element independently and
   * passing through any code the two schemas already agree on. */
  fun birthComplicationsForChildRegistration(deliveryVisitCodes: List<String>): List<String> =
    deliveryVisitCodes.map { BIRTH_COMPLICATIONS_TO_CHILD_REGISTRATION[it] ?: it }
}
