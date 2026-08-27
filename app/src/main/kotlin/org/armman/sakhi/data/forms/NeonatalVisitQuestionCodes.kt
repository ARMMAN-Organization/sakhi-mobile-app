package org.armman.sakhi.data.forms

/**
 * Question codes [org.armman.sakhi.data.delivery.DeliveryToNeonatalPrefill] reads/writes on the
 * `NEONATAL_VISIT` form (NN1 and NN2 — both share this one schema, distinguished only by the
 * `visit_name` field's `nn1`/`nn2` value).
 *
 * **CONFIRMED 2026-08-19** against the real `GET /forms/NEONATAL_VISIT/active-version` payload
 * (`formVersionId 400da5dd-047f-4e80-a4f2-462c32377266`, 33 fields) — no longer a guess, same
 * standard this file's siblings ([DeliveryQuestionCodes], [ChildRegistrationQuestionCodes]) hold
 * themselves to.
 */
object NeonatalVisitQuestionCodes {
  /**
   * "Birth weight of the baby in kg (from the Delivery form, for the KMC eligibility check
   * below)" — explicitly labelled as carried forward from `DELIVERY_VISIT`, not re-measured here.
   * Also the field [IS_KMC_PRACTICED]'s own `visibleWhen` gates on (`< 2.5`), so a missing/wrong
   * prefill here would silently break that gate too, not just cost the Sakhi a re-entry.
   */
  const val BIRTH_WEIGHT_KG = "birth_weight_kg"

  /**
   * "Term of delivery (from the Delivery form, for the KMC eligibility check below)" — options are
   * `pre_term`/`post_term`/`full_term`, the same three-option vocabulary
   * [org.armman.sakhi.data.delivery.DeliveryToChildRegistrationPrefill] already copies verbatim
   * from `DELIVERY_VISIT` to `CHILD_REGISTRATION` (per [DeliveryQuestionCodes.TERM_OF_DELIVERY]'s
   * own "confirmed identical vocabulary" doc) — by transitivity the same vocabulary this field
   * uses, though not independently re-confirmed against `DELIVERY_VISIT`'s own raw payload.
   */
  const val TERM_OF_DELIVERY = "term_of_delivery"

  /** Governs [IS_KMC_PRACTICED]'s `visibleWhen` (`birth_weight_kg < 2.5`) — not written by the
   * prefill itself, kept here only so the KMC gate's dependency on [BIRTH_WEIGHT_KG] is documented
   * in one place rather than solely inside the schema's own JSON. */
  const val IS_KMC_PRACTICED = "is_kmc_practiced"
}
