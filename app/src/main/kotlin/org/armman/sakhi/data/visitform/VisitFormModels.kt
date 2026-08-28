package org.armman.sakhi.data.visitform

import java.time.LocalDate

/**
 * Carried-forward context for this visit (Excel Q2/Q5/Q6/Q12/Q26/Q42/Q43 —
 * "Auto populate based on ... registration/previous visit"). Sourced from the
 * beneficiary's registration + most recent prior visit; the mother
 * (ANC_VISIT) [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel] prefills
 * `rch_number`/`lmp` from this on load — see its `prefillFromVisitContext`.
 */
data class VisitContext(
  /** Q2 — e.g. "ANC2", auto-derived from the visit's position in the schedule. */
  val visitTypeLabel: String,
  /** Q5 — RCH number captured at registration (editable here if missing). */
  val rchNumber: String,
  /** Q6 — most recent LMP on file. */
  val lmp: LocalDate,
  /** Q12 — only present (and thus read-only) once a prior visit captured height. */
  val heightCm: Int?,
  /** Q26 — last visit's Hb, for the ±2 g/dl stale-value confirmation prompt. */
  val previousHb: Double?,
  /** Q42 — advised place of delivery from the previous visit, if any. */
  val advisedDeliveryPlace: Int?,
  /** Q43 — sickle cell status from the previous visit/registration, if any. */
  val sickleCell: Int?,
  /**
   * The mother's weight (kg) captured at MOTHER_REGISTRATION (`weight_kg`), read back via
   * [org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile.weight] — the baseline
   * [org.armman.sakhi.data.visitform.VisitFormComputedFieldEvaluator]'s gestational-weight-gain
   * calculation diffs this visit's own recorded weight against. Null if registration didn't
   * capture a weight (e.g. a beneficiary enrolled before that question existed) or the profile
   * couldn't be parsed — the evaluator leaves the field "Auto-calculated" rather than guess a
   * baseline in that case.
   */
  val registrationWeightKg: Double?,
  /**
   * CR-016c Summary banner chips — the beneficiary's recorded diagnoses
   * (`BeneficiaryProfile.diagnoses`), shown verbatim, never relabeled onto a
   * fixed chip set (avoids mis-tagging real health data via keyword guessing).
   * Not currently surfaced by the dynamic Visit Form (fetch+render pass) —
   * kept on the model for a future Summary-style display.
   */
  val comorbidities: List<String> = emptyList(),
)

/**
 * A mid-form critical danger sign (FR-S-4.4). [messageRes] is the string
 * resource shown on the Immediate Urgency banner. Detected for the mother
 * (ANC_VISIT) flow by [VisitCriticalConditionEvaluator] and reported via
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel]'s
 * `recheckCriticalCondition`/`dismissCritical`.
 */
data class CriticalCondition(val messageRes: Int)

/**
 * The 3-tab shell every Visit Form (mother or infant) renders into, matching the retired
 * hand-coded flow's `VisitFormStep` one-for-one for [VISIT_DATA]/[SUMMARY]/[HEALTH_INFO] (same
 * [org.armman.sakhi.R.string.visit_form_tab_visit_data]/`visit_form_tab_summary`
 * /`visit_form_tab_health_info` labels). Unlike the retired flow, the *content* under
 * [VISIT_DATA] is no longer hand-coded — it's every one of the active schema's sections, in
 * order, shown as pill sub-tabs (ANC_VISIT: "Visit data"/"Health info"/"Referrals"; INFANT_VISIT:
 * "Tests"/"Symptoms"/"History") — see
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.subSections].
 *
 * [SUMMARY] and [HEALTH_INFO] have no schema mapping for either beneficiary type this pass
 * (bharath, 2026-08-07: confirmed explicitly — ANC_VISIT's "Health info"/"Referrals" section
 * names happen to match these tab labels, but that's coincidental; their fields still live under
 * VISIT_DATA, not here) and always render a placeholder.
 *
 * [REFERRAL] still exists as an enum value (referenced by legacy label-lookup `when` branches in
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen]) but is no longer part of the tab order
 * itself — CR-Referral-01 Pass 4 (2026-08-27) moved referral capture out of a persistent tab
 * into a conditional post-visit step, gated by the on-device risk result rather than always
 * shown; see [org.armman.sakhi.ui.visitform.DynamicVisitFormUiState.showReferralCaptureStep]'s
 * doc for why (matches the PRD's "Visit completed — Risk assessment — Referral decision" tree,
 * and works identically online or offline).
 */
enum class VisitFormOuterTab { VISIT_DATA, SUMMARY, HEALTH_INFO, REFERRAL }
