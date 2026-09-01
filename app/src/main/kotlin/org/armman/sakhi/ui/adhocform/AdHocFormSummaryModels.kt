package org.armman.sakhi.ui.adhocform

/**
 * CR-Referral-01 (2026-09-01): Summary-tab review types for [AdHocFormScreen]/[AdHocFormViewModel]
 * — one ViewModel/screen serves all five ad-hoc forms (`REFERRAL_VISIT`/`REFERRAL_FOLLOWUP_VISIT`/
 * `ANC_CLOSURE_VISIT`/`CHILD_CLOSURE_VISIT`/`BENEFICIARY_REOPEN_VISIT`), so one shared declaration
 * here is enough — unlike the mother/child registration flows, which live in separate packages and
 * each declare their own identically-named copy (see [org.armman.sakhi.ui.delivery
 * .DeliverySummaryModels]'s doc for why that split exists elsewhere).
 */

/** One label/value line in the Summary tab's review. */
data class SummaryRow(val label: String, val value: String)

/** One section card in the Summary tab — [title] is the schema's own `section` value for that
 * field group (e.g. "Type"/"Visit Info"/"Data Upload" for `REFERRAL_FOLLOWUP_VISIT`), [rows] its
 * answered fields. Empty sections are dropped by [AdHocFormViewModel.buildSummary]. */
data class SummarySection(val title: String, val rows: List<SummaryRow>)

/** Tab/section label for any visible field whose schema `section` is missing — a catch-all so a
 * field never silently disappears from either the tab bar or the Summary review. Same fallback
 * text every other dynamic form in the app already uses (mother/child registration, delivery). */
const val FALLBACK_SECTION = "Additional Information"
