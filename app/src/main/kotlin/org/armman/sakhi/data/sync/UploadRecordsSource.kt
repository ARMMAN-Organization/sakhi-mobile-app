package org.armman.sakhi.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.armman.sakhi.data.adhocform.AdHocFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftRepository
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.visitform.VisitFormDraftRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single read model behind the Home screen's Data Upload badge and "Forms Uploaded" modal.
 *
 * Home previously observed only the Mother Registration queue. That was survivable while a
 * 15-minute periodic job swept everything in the background, but under manual-only sync (SRS
 * §3A.1) the modal is the Sakhi's only window onto what still needs uploading — a queue missing
 * from it is a queue she has no way to know about.
 *
 * Merged here:
 *  - Mother Registration drafts (CR-018)
 *  - Children Register drafts (CR-020)
 *  - ANC Visit Form drafts (CR-026b)
 *  - Ad-hoc form drafts -- Referral, Referral Follow-up, ANC/Child Closure, Beneficiary Reopen
 *    (CR-026b). Added 2026-09-10 ("Data Upload pill shows no indication after a Referral
 *    Follow-up submitted offline"): this queue was syncing correctly the whole time
 *    ([org.armman.sakhi.data.sync.ManualSyncTrigger] always kicked it off) — it simply had no row
 *    in this read model, so a Sakhi watching the "Forms Uploaded" modal for a submitted follow-up
 *    saw nothing move, with no way to tell it was even queued.
 *  - Delivery Form drafts (CR-042). Added 2026-09-11, alongside the fix that finally wires this
 *    queue's WorkManager retry up at all (see [org.armman.sakhi.data.delivery.DeliverySyncScheduler]'s
 *    doc) — folds into the shared "Visit Form" card
 *    ([org.armman.sakhi.ui.home.FormUploadStatusPresentation]'s `VISIT_FORM_CODES`), since its own
 *    formCode is `DELIVERY_VISIT`, already a member of that set.
 *  - Delivery Child Registration drafts (CR-042) — the CHILD_REGISTRATION submission that follows
 *    a delivery. Folds into the shared "Registration" card, same category a standalone Children
 *    Register draft already displays under.
 *
 * Deliberately **not** merged: the legacy `enrollment_drafts` queue. Its rows carry no `formCode`
 * (see `EnrollmentDraftEntity`), so surfacing them would mean inventing a category label for a
 * flow that is unreachable from navigation and being deprecated. Those rows are still *uploaded* —
 * [ManualSyncTrigger] drains that queue too — they're just not given a card of their own. Revisit
 * only if the static flow is ever revived.
 */
interface UploadRecordsSource {
  /** Every draft across the surfaced queues, re-emitting as sync statuses advance. */
  fun observeAll(): Flow<List<FormUploadRecord>>
}

@Singleton
class CombinedUploadRecordsSource @Inject constructor(
  private val dynamicFormDraftRepository: DynamicFormDraftRepository,
  private val childFormDraftRepository: ChildFormDraftRepository,
  private val visitFormDraftRepository: VisitFormDraftRepository,
  private val adHocFormDraftRepository: AdHocFormDraftRepository,
  private val deliveryFormDraftRepository: DeliveryFormDraftRepository,
  private val deliveryChildRegistrationDraftRepository: DeliveryChildRegistrationDraftRepository,
) : UploadRecordsSource {

  /**
   * [combine] rather than [kotlinx.coroutines.flow.merge]: the UI needs the *union* of all six
   * queues on every emission, not whichever one changed most recently. Every upstream is
   * Room-backed and emits its current contents immediately on collection, so combine produces its
   * first value without waiting for a write on any side.
   *
   * The vararg/array [combine] overload, not the fixed-arity one — six flows exceeds the typed
   * `combine(flow1..flow5) { }` overloads' limit.
   *
   * Sorted newest-first to match each repository's own contract, since combining independently
   * ordered lists doesn't preserve it.
   */
  override fun observeAll(): Flow<List<FormUploadRecord>> =
    combine(
      dynamicFormDraftRepository.observeUploadRecords(),
      childFormDraftRepository.observeUploadRecords(),
      visitFormDraftRepository.observeUploadRecords(),
      adHocFormDraftRepository.observeUploadRecords(),
      deliveryFormDraftRepository.observeUploadRecords(),
      deliveryChildRegistrationDraftRepository.observeUploadRecords(),
    ) { recordLists ->
      recordLists.toList().flatten().sortedByDescending { it.createdAtEpochMillis }
    }
}
