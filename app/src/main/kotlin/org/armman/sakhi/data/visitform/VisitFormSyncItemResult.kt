package org.armman.sakhi.data.visitform

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via
 * [VisitFormSyncExecutor.runOne] — used by [RoomVisitFormDraftRepository.submitDraft] to report
 * the real backend result right after Submit. Mirrors
 * [org.armman.sakhi.data.forms.DynamicFormSyncItemResult]'s rationale exactly.
 */
sealed interface VisitFormSyncItemResult {
  data object Synced : VisitFormSyncItemResult

  data class Failed(val message: String) : VisitFormSyncItemResult

  /** Includes [VisitFormSubmissionException.NotYetSynced] — the beneficiary/schedule just hasn't
   * synced yet, not a permanent problem — as well as a transient `IOException`. */
  data class Retryable(val message: String?) : VisitFormSyncItemResult
}
