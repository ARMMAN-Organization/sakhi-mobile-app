package org.armman.sakhi.data.adhocform

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via [AdHocFormSyncExecutor.runOne] —
 * used by [RoomAdHocFormDraftRepository.submitDraft] to report the real backend result right after
 * Submit. Mirrors [org.armman.sakhi.data.visitform.VisitFormSyncItemResult]'s rationale exactly.
 */
sealed interface AdHocFormSyncItemResult {
  data object Synced : AdHocFormSyncItemResult

  data class Failed(val message: String) : AdHocFormSyncItemResult

  /** Includes [AdHocFormSubmissionException.NotYetSynced] — the beneficiary hasn't finished
   * syncing yet, not a permanent problem — as well as a transient `IOException`. */
  data class Retryable(val message: String?) : AdHocFormSyncItemResult
}
