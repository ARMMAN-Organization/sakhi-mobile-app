package org.armman.sakhi.ui.visitform

/**
 * Shared Summary-tab review types for [DynamicVisitFormViewModel.buildFieldSummary] — the plain
 * "answered fields, grouped by schema section" review that POSTPARTUM_VISIT/NEONATAL_VISIT (and
 * any other form code with no bespoke Summary tab of its own) get on the visit form's Summary
 * outer tab. ANC_VISIT/INFANT_VISIT keep their own risk-banner Summary tabs (see
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.testsFindings]/
 * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.infantKnownRisks]) — this is the first
 * visit-form user of the plain row/section shape.
 *
 * Mirrors [org.armman.sakhi.ui.delivery.SummaryRow]/[org.armman.sakhi.ui.delivery.SummarySection]
 * field-for-field, but declared as its own local copy rather than imported from that package —
 * matching this codebase's existing convention of each feature area owning its own copy of this
 * shape (see that file's own doc for the file-private-vs-package-scoped reasoning) rather than
 * creating a cross-feature dependency between `ui.visitform` and `ui.delivery`.
 */

/** One label/value line in the visit form's Summary review. */
data class SummaryRow(val label: String, val value: String)

/** One section card in the visit form's Summary review — [title] is the schema section, [rows]
 * its answered fields (empty sections are dropped by [DynamicVisitFormViewModel.buildFieldSummary]). */
data class SummarySection(val title: String, val rows: List<SummaryRow>)
