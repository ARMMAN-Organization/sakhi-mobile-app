package org.armman.sakhi.data.forms

/**
 * Question codes [org.armman.sakhi.ui.delivery.DeliverySessionViewModel] reads off a submitted
 * `DELIVERY_VISIT` form to build the [org.armman.sakhi.data.schedule.ScheduleContext] that drives
 * PP/NN generation (`deliveryDate`, `deliveryFormFilledOn`).
 *
 * **CONFIRMED 2026-08-19** against the real `GET /forms/DELIVERY_VISIT/active-version` payload
 * (`formVersionId 872e49fc-ed5d-4488-94a2-1e0947a7d988`, 44 fields) — no longer a guess. Both
 * codes below match the live `schemaJson` exactly. [DELIVERY_FORM_FILLED_ON]'s value was corrected
 * from an earlier unconfirmed guess (`delivery_form_filled_on`) to the real field code
 * (`delivery_form_filled_date`) as part of this confirmation — the earlier guess would have
 * silently never matched any submitted answer, always falling back to [DATE_OF_DELIVERY].
 */
object DeliveryQuestionCodes {
  /** The actual date of delivery — anchors the PP series
   * ([org.armman.sakhi.data.schedule.ScheduleContext.deliveryDate]). */
  const val DATE_OF_DELIVERY = "date_of_delivery"

  /**
   * The date the Sakhi is filling this form — drives the NN scenario A/B/C split
   * ([org.armman.sakhi.data.schedule.ScheduleContext.deliveryFormFilledOn]); distinct from
   * [DATE_OF_DELIVERY] only when the form is filled late. If a schema republish ever drops this
   * field, [org.armman.sakhi.ui.delivery.DeliverySessionViewModel] falls back to [DATE_OF_DELIVERY]
   * — same-day fill is exactly what "no separate field" would mean in practice.
   */
  const val DELIVERY_FORM_FILLED_ON = "delivery_form_filled_date"

  /** Confirmed the same live payload: identical `value_code` vocabulary on both `DELIVERY_VISIT`
   * and `CHILD_REGISTRATION` — see [org.armman.sakhi.data.delivery.DeliveryOptionCodeMapper]'s own
   * doc for the two fields that are NOT safe to copy verbatim (kept out of this shared list on
   * purpose so nothing copies them without going through that mapper). */
  const val TERM_OF_DELIVERY = "term_of_delivery"
  const val TYPE_OF_DELIVERY = "type_of_delivery"

  /** These two DO need [org.armman.sakhi.data.delivery.DeliveryOptionCodeMapper] before copying to
   * `CHILD_REGISTRATION` — declared here anyway (rather than only as private literals in the
   * mapper) since [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmissionCoordinator]'s
   * prefill also needs the `DELIVERY_VISIT`-side question code, not just the mapping function. */
  const val PLACE_OF_DELIVERY = "place_of_delivery"
  const val WHO_CONDUCTED_THE_DELIVERY = "who_conducted_the_delivery"

  /**
   * "Date of discharge" — spec: must be strictly after the mother's ANC registration date and her
   * LMP, on or after [DATE_OF_DELIVERY], and not in the future. See [FormDateRuleset.boundsFor]
   * and [FormDateRuleset.violationFor].
   *
   * **UNCONFIRMED** — unlike every other code in this file, this one is NOT verified against a
   * live `GET /forms/DELIVERY_VISIT/active-version` payload; it's a snake-cased guess from the
   * spec label, following this file's own naming convention. If the real schema uses a different
   * code, [FormDateRuleset]'s bounds/violation checks for this field will silently never fire —
   * see [DELIVERY_FORM_FILLED_ON]'s doc for the exact failure this already happened once. Verify
   * and correct before relying on this in production.
   */
  const val DATE_OF_DISCHARGE = "date_of_discharge"

  /**
   * "Date of death" — spec: must be strictly after [DATE_OF_DELIVERY]; otherwise accepts any past
   * date or today (no lower bound from registration/LMP, unlike [DATE_OF_DISCHARGE]). See
   * [FormDateRuleset.boundsFor] and [FormDateRuleset.violationFor].
   *
   * **UNCONFIRMED** — same caveat as [DATE_OF_DISCHARGE]: a snake-cased guess, not yet verified
   * against a live schema payload.
   */
  const val DATE_OF_DEATH = "date_of_death"

  /**
   * Per-child fields (`childN_*`) — [childIndex] is 0-based (0 -> `child1_*`, 1 -> `child2_*`,
   * 2 -> `child3_*`), matching [org.armman.sakhi.data.delivery.DeliverySessionEntity
   * .nextChildIndexToRegister]'s own indexing. The schema caps at 3 children per delivery
   * (`number_of_babies_born`'s `numericRange` is 1-3), confirmed against the live payload.
   */
  fun childSexOfBaby(childIndex: Int): String = "child${childIndex + 1}_sex_of_baby"
  fun childBirthLengthCm(childIndex: Int): String = "child${childIndex + 1}_birth_length_cm"
  fun childBirthWeightKg(childIndex: Int): String = "child${childIndex + 1}_birth_weight_kg"
  fun childRelatedComplications(childIndex: Int): String = "child${childIndex + 1}_related_complications"
}
