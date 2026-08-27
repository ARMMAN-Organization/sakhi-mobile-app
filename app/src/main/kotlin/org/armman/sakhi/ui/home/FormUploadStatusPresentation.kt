package org.armman.sakhi.ui.home

import androidx.compose.ui.graphics.Color
import org.armman.sakhi.R
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.StatusSuccess
import kotlin.math.roundToInt

/**
 * Pure (non-Composable) rules for the "Forms Uploaded" modal, matching the Figma reference's
 * actual structure: **one card per form category** (e.g. "Registration"), not one per
 * individual submission — each card shows an aggregate "synced/total" count, one progress bar,
 * and one status icon for the whole category.
 *
 * Pulled out of [FormsUploadedModal] so these rules are unit-testable with plain JUnit — this
 * repo has no Compose UI test harness (no `createComposeRule` usage exists anywhere).
 *
 * Mother Registration (CR-018) and Children Register (CR-020) drafts, merged by
 * [org.armman.sakhi.data.sync.UploadRecordsSource], are folded into a single "Registration"
 * category here via [displayCategoryCode] — they remain two distinct underlying form codes and
 * two distinct submission/sync queues, only this status view presents them as one card.
 * [categoryLabelRes] is a `when` specifically so adding a genuinely separate third category
 * (e.g. a Referral Form) is a one-line addition, not a rewrite.
 */
private const val MOTHER_REGISTRATION_FORM_CODE = "MOTHER_REGISTRATION"
private const val CHILD_REGISTRATION_FORM_CODE = "CHILD_REGISTRATION"

/** CR-026b Visit Form drafts — a genuinely separate category, not folded into Registration:
 * a Sakhi can have visit drafts with no registration drafts pending (or vice versa), so grouping
 * them together would misrepresent what's actually left to upload.
 *
 * CR-033/CR-034: every visit-stage form code [org.armman.sakhi.data.forms.VisitCodeFormResolver]
 * can resolve to, not just ANC_VISIT — before this set existed, PP/NN/DELIVERY/INC/CCV upload
 * records fell into this file's `else` branch and were mislabeled as "Registration" (see
 * [categoryLabelRes]'s old doc). INC_VISIT/CCV_VISIT are included even though their schema
 * content is still a backend placeholder (docs/test-cases/visit-form.md) — this modal only cares
 * about the form CODE, not whether its content is final. */
private val VISIT_FORM_CODES = setOf(
  "ANC_VISIT",
  "DELIVERY_VISIT",
  "POSTPARTUM_VISIT",
  "NEONATAL_VISIT",
  "INFANT_VISIT",
  "INC_VISIT",
  "CCV_VISIT",
)

/** Display category all registration-family form codes are folded into for this modal. */
private const val REGISTRATION_CATEGORY_CODE = "REGISTRATION"

/** Category code all [VISIT_FORM_CODES] entries display under. Deliberately kept as the literal
 * "ANC_VISIT" (not a new "VISIT" code) — existing tests and any other caller that already checks
 * for category code "ANC_VISIT" must keep working unchanged now that PP/NN/DELIVERY/INC/CCV fold
 * into the same card. */
private const val VISIT_CATEGORY_CODE = "ANC_VISIT"

/**
 * Maps a raw [FormUploadRecord.formCode] to the category code this modal groups and labels by.
 * Mother and child registration codes fold into one "Registration" card; any [VISIT_FORM_CODES]
 * entry folds into the "ANC_VISIT"-labelled visit card; any other code (e.g. a future Referral
 * Form) passes through unchanged and gets its own card.
 */
private fun displayCategoryCode(formCode: String): String = when {
  formCode == MOTHER_REGISTRATION_FORM_CODE || formCode == CHILD_REGISTRATION_FORM_CODE -> REGISTRATION_CATEGORY_CODE
  formCode in VISIT_FORM_CODES -> VISIT_CATEGORY_CODE
  else -> formCode
}

/** Which icon a category (or, before aggregation, a single record) renders. */
internal enum class UploadStatusIconKind {
  /** Green check-circle — the whole category is fully synced. */
  SYNCED,

  /** Muted grey sync-loop — nothing in the category has been attempted yet. */
  PENDING,

  /** Primary-tinted sync-loop — the category has made some real progress (at least one record
   * synced or syncing) but isn't fully done yet. */
  SYNCING,

  /** Red warning-circle — at least one record in the category is FAILED or DUPLICATE_CONFLICT.
   * Deliberately distinct from the sync-loop icons (not shared with PENDING/SYNCING) so a Sakhi
   * can tell "still working on it" apart from "needs your attention" at a glance, and this always
   * wins over a partial-success count — a failure anywhere means the category needs attention
   * regardless of how many other records in it succeeded. */
  NEEDS_ATTENTION,
}

/** A single record's icon kind, before it's folded into a category's aggregate. */
internal fun uploadStatusIconKind(status: EnrollmentSyncStatus): UploadStatusIconKind = when (status) {
  EnrollmentSyncStatus.SYNCED -> UploadStatusIconKind.SYNCED
  EnrollmentSyncStatus.PENDING -> UploadStatusIconKind.PENDING
  EnrollmentSyncStatus.SYNCING -> UploadStatusIconKind.SYNCING
  EnrollmentSyncStatus.FAILED, EnrollmentSyncStatus.DUPLICATE_CONFLICT -> UploadStatusIconKind.NEEDS_ATTENTION
}

/** One form category's aggregate state — the modal renders one card per entry of this type. */
internal data class FormCategorySummary(
  val formCode: String,
  val syncedCount: Int,
  val totalCount: Int,
  val iconKind: UploadStatusIconKind,
)

/**
 * Groups raw records into one [FormCategorySummary] per distinct display category (see
 * [displayCategoryCode]) — e.g. Mother and Children Register records fold into one
 * "Registration" card. The modal renders one card per entry in the returned list, not one per
 * input record and not necessarily one per raw form code.
 */
internal fun groupUploadRecordsByCategory(records: List<FormUploadRecord>): List<FormCategorySummary> =
  records
    .groupBy { displayCategoryCode(it.formCode) }
    .map { (categoryCode, group) ->
      FormCategorySummary(
        formCode = categoryCode,
        syncedCount = group.count { it.syncStatus == EnrollmentSyncStatus.SYNCED },
        totalCount = group.size,
        iconKind = categoryIconKind(group.map { it.syncStatus }),
      )
    }

/**
 * A category's aggregate icon, in priority order (approved rule):
 * 1. Any FAILED/DUPLICATE_CONFLICT anywhere in the category → [UploadStatusIconKind.NEEDS_ATTENTION],
 *    regardless of how many other records succeeded.
 * 2. All records SYNCED → [UploadStatusIconKind.SYNCED] (the fully-complete, terminal state).
 * 3. Otherwise, if anything has made progress (SYNCED or SYNCING) → [UploadStatusIconKind.SYNCING]
 *    — matches the Figma reference's "Consent Form" 50% purple-loop example: partially synced
 *    still counts as in-progress, not untouched.
 * 4. Otherwise (nothing attempted yet) → [UploadStatusIconKind.PENDING] — matches the reference's
 *    "FormName" 0% grey-loop example.
 */
internal fun categoryIconKind(statuses: List<EnrollmentSyncStatus>): UploadStatusIconKind {
  if (statuses.any { it == EnrollmentSyncStatus.FAILED || it == EnrollmentSyncStatus.DUPLICATE_CONFLICT }) {
    return UploadStatusIconKind.NEEDS_ATTENTION
  }
  if (statuses.all { it == EnrollmentSyncStatus.SYNCED }) {
    return UploadStatusIconKind.SYNCED
  }
  if (statuses.any { it == EnrollmentSyncStatus.SYNCED || it == EnrollmentSyncStatus.SYNCING }) {
    return UploadStatusIconKind.SYNCING
  }
  return UploadStatusIconKind.PENDING
}

/**
 * Display label for a form category. The `else` branch falls back to the merged Registration
 * label rather than crashing or showing a raw form code, since an unmapped code can only come
 * from a queue this screen doesn't know about yet — extend this `when` when a new category ships.
 */
internal fun categoryLabelRes(formCode: String): Int = when (formCode) {
  REGISTRATION_CATEGORY_CODE -> R.string.home_upload_modal_registration_form_label
  VISIT_CATEGORY_CODE -> R.string.home_upload_modal_visit_form_label
  else -> R.string.home_upload_modal_registration_form_label
}

/** Real synced/total percentage, rounded to the nearest whole number — always a genuine fraction
 * of the category's own records, never fabricated. */
internal fun categoryPercent(summary: FormCategorySummary): Int =
  if (summary.totalCount == 0) 0 else (summary.syncedCount * 100f / summary.totalCount).roundToInt()

/** Real synced/total fraction for the progress bar — same rationale as [categoryPercent]. Unlike
 * a single record's binary sync outcome, a category's completion fraction is always genuinely
 * known, so (unlike the old per-record design) no indeterminate state is needed here. */
internal fun categoryProgressFraction(summary: FormCategorySummary): Float =
  if (summary.totalCount == 0) 0f else summary.syncedCount.toFloat() / summary.totalCount

/** Icon tint for a category's aggregate status. */
internal fun categoryIconTint(iconKind: UploadStatusIconKind): Color = when (iconKind) {
  UploadStatusIconKind.SYNCED -> StatusSuccess
  UploadStatusIconKind.NEEDS_ATTENTION -> RiskHigh
  UploadStatusIconKind.SYNCING -> Primary
  UploadStatusIconKind.PENDING -> NeutralG100
}

/** Percentage-text color — slightly more readable than [categoryIconTint]'s muted PENDING tone,
 * matching the earlier per-record design's same distinction between icon and label color. */
internal fun categoryTextColor(iconKind: UploadStatusIconKind): Color = when (iconKind) {
  UploadStatusIconKind.SYNCED -> StatusSuccess
  UploadStatusIconKind.NEEDS_ATTENTION -> RiskHigh
  UploadStatusIconKind.SYNCING, UploadStatusIconKind.PENDING -> NeutralG200
}

/** Progress bar fill color. */
internal fun categoryBarColor(iconKind: UploadStatusIconKind): Color = when (iconKind) {
  UploadStatusIconKind.NEEDS_ATTENTION -> RiskHigh
  UploadStatusIconKind.SYNCED, UploadStatusIconKind.SYNCING, UploadStatusIconKind.PENDING -> Primary
}

/** Progress bar track (unfilled) color. */
internal fun categoryTrackColor(iconKind: UploadStatusIconKind): Color = when (iconKind) {
  UploadStatusIconKind.NEEDS_ATTENTION -> RiskHighSurface
  UploadStatusIconKind.PENDING -> NeutralG50
  UploadStatusIconKind.SYNCED, UploadStatusIconKind.SYNCING -> PrimarySurface
}
