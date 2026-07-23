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
 * actual structure: **one card per form category** (e.g. "Mother Registration"), not one per
 * individual submission — each card shows an aggregate "synced/total" count, one progress bar,
 * and one status icon for the whole category.
 *
 * Pulled out of [FormsUploadedModal] so these rules are unit-testable with plain JUnit — this
 * repo has no Compose UI test harness (no `createComposeRule` usage exists anywhere).
 *
 * Only one category exists today ([MOTHER_REGISTRATION_FORM_CODE]), since the static enrollment
 * flow is deprecated in favor of this dynamic one; [categoryLabelRes] is a `when` specifically so
 * adding a second category later (e.g. a Referral Form) is a one-line addition, not a rewrite.
 */
private const val MOTHER_REGISTRATION_FORM_CODE = "MOTHER_REGISTRATION"

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
 * Groups raw records into one [FormCategorySummary] per distinct form code. The modal renders one
 * card per entry in the returned list, not one per input record.
 */
internal fun groupUploadRecordsByCategory(records: List<FormUploadRecord>): List<FormCategorySummary> =
  records
    .groupBy { it.formCode }
    .map { (formCode, group) ->
      FormCategorySummary(
        formCode = formCode,
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

/** Display label for a form category. Only one category exists today; extend this `when` when a
 * second one ships. */
internal fun categoryLabelRes(formCode: String): Int = when (formCode) {
  MOTHER_REGISTRATION_FORM_CODE -> R.string.home_upload_modal_form_label
  else -> R.string.home_upload_modal_form_label
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
