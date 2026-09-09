package org.armman.sakhi.data.forms

/**
 * Every dynamic form schema code the app is known to request from [FormsRepository], gathered in
 * one place so [FormSchemaWarmer] has a single source of truth for what to pre-fetch.
 *
 * CR-Forms-01 ("Forms not loading when offline"): existing call sites (ViewModels,
 * [org.armman.sakhi.data.forms.VisitCodeFormResolver], [org.armman.sakhi.ui.adhocform
 * .AdHocFormViewModel.AD_HOC_FORM_TITLES]) each keep their own local `formCode` constant --
 * intentionally left as-is here to keep this change scoped to the warmer rather than touching
 * every screen. If a new form code is added to the app, add it here too so [FormSchemaWarmer]
 * keeps pre-fetching it; forgetting to only means that one form type isn't warmed ahead of time
 * (falls back to today's lazy-fetch-on-open behavior), never a runtime break.
 */
object KnownFormCodes {
  const val MOTHER_REGISTRATION = "MOTHER_REGISTRATION"
  const val CHILD_REGISTRATION = "CHILD_REGISTRATION"
  const val ANC_VISIT = "ANC_VISIT"
  const val INFANT_VISIT = "INFANT_VISIT"
  const val POSTPARTUM_VISIT = "POSTPARTUM_VISIT"
  const val NEONATAL_VISIT = "NEONATAL_VISIT"
  const val INC_VISIT = "INC_VISIT"
  const val CCV_VISIT = "CCV_VISIT"
  const val DELIVERY_VISIT = "DELIVERY_VISIT"
  const val REFERRAL_VISIT = "REFERRAL_VISIT"
  const val REFERRAL_FOLLOWUP_VISIT = "REFERRAL_FOLLOWUP_VISIT"
  const val ANC_CLOSURE_VISIT = "ANC_CLOSURE_VISIT"
  const val CHILD_CLOSURE_VISIT = "CHILD_CLOSURE_VISIT"
  const val BENEFICIARY_REOPEN_VISIT = "BENEFICIARY_REOPEN_VISIT"

  /** Every known form code, in the order [FormSchemaWarmer] warms them. */
  val ALL: List<String> = listOf(
    MOTHER_REGISTRATION,
    CHILD_REGISTRATION,
    ANC_VISIT,
    INFANT_VISIT,
    POSTPARTUM_VISIT,
    NEONATAL_VISIT,
    INC_VISIT,
    CCV_VISIT,
    DELIVERY_VISIT,
    REFERRAL_VISIT,
    REFERRAL_FOLLOWUP_VISIT,
    ANC_CLOSURE_VISIT,
    CHILD_CLOSURE_VISIT,
    BENEFICIARY_REOPEN_VISIT,
  )
}
