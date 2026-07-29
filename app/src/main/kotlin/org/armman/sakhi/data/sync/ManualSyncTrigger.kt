package org.armman.sakhi.data.sync

import org.armman.sakhi.data.childregistration.ChildFormSyncScheduler
import org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for starting a data upload, per SRS §3A.1 — *"Data Sync — Manual trigger.
 * Deferred with retry."*
 *
 * The app maintains three independent offline queues, each with its own WorkManager unique work
 * name so they are scheduled and de-duplicated separately:
 *  - `dynamic_form_drafts` — Mother Registration (CR-018), the live flow
 *  - `child_registration_drafts` — Children Register (CR-020)
 *  - `enrollment_drafts` — the legacy static enrollment flow, unreachable from navigation today
 *    but still drained here so rows left behind by an older build can reach the server
 *
 * Since nothing else syncs any more (no periodic tick, no app-start schedule, no
 * connectivity-reconnect trigger), a manual trigger that covered only one queue would strand the
 * other two on-device permanently. So this fans out to all three.
 *
 * Each scheduler enqueues unique work with `ExistingWorkPolicy.REPLACE`, so every tap really does
 * start an attempt now. It must not be `KEEP`: after a failed run WorkManager holds the work in
 * ENQUEUED through an exponential backoff delay, and `KEEP` would silently discard any tap made
 * during that window — leaving the Sakhi tapping a button that does nothing.
 *
 * Deliberately *not* suspend and deliberately fire-and-forget: enqueueing WorkManager work is
 * non-blocking, and the caller (Home) reflects outcomes through the live draft-status stream
 * ([UploadRecordsSource]) rather than a return value.
 */
@Singleton
class ManualSyncTrigger @Inject constructor(
  private val dynamicFormSyncScheduler: DynamicFormSyncScheduler,
  private val childFormSyncScheduler: ChildFormSyncScheduler,
  private val enrollmentSyncScheduler: EnrollmentSyncScheduler,
) {
  /** Starts one upload attempt across every offline queue. */
  fun syncAllQueues() {
    dynamicFormSyncScheduler.syncNow()
    childFormSyncScheduler.syncNow()
    enrollmentSyncScheduler.syncNow()
  }
}
