package org.armman.sakhi.data.visitform

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via
 * [VisitFormSyncExecutor.runOne] — used by [RoomVisitFormDraftRepository.submitDraft] to report
 * the real backend result right after Submit. Mirrors
 * [org.armman.sakhi.data.forms.DynamicFormSyncItemResult]'s rationale exactly.
 */
sealed interface VisitFormSyncItemResult {
  /** CR-Closure-01 items #5/#6: [outcome] defaults to "no signal" — see [VisitSubmitOutcome]'s own
   * doc — so every existing caller that never reads it keeps working unchanged. */
  data class Synced(val outcome: VisitSubmitOutcome = VisitSubmitOutcome()) : VisitFormSyncItemResult

  data class Failed(val message: String) : VisitFormSyncItemResult

  /** Includes [VisitFormSubmissionException.NotYetSynced] — the beneficiary/schedule just hasn't
   * synced yet, not a permanent problem — as well as a transient `IOException`. */
  data class Retryable(val message: String?) : VisitFormSyncItemResult
}
