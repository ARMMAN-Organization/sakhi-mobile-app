package org.armman.sakhi.data.sync

import org.armman.sakhi.data.childregistration.ChildFormSyncScheduler
import org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
import org.armman.sakhi.data.schedule.VisitScheduleSyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for starting a data upload, per SRS §3A.1 — *"Data Sync — Manual trigger.
 * Deferred with retry."*
 *
 * The app maintains four independent offline queues, each with its own WorkManager unique work
 * name so they are scheduled and de-duplicated separately:
 *  - `dynamic_form_drafts` — Mother Registration (CR-018), the live flow
 *  - `child_registration_drafts` — Children Register (CR-020)
 *  - `enrollment_drafts` — the legacy static enrollment flow, unreachable from navigation today
 *    but still drained here so rows left behind by an older build can reach the server
 *  - `visit_schedules` — device-generated visit schedules (CR-022). Unlike the other three this is
 *    not a form draft but real domain data the device authored, so it has no PENDING/FAILED
 *    lifecycle: a row is unsynced until the server returns its ID.
 *
 * Since nothing else syncs any more (no periodic tick, no app-start schedule, no
 * connectivity-reconnect trigger), a manual trigger that covered only one queue would strand the
 * others on-device permanently. So this fans out to all four.
 *
 * **Ordering between queues is not guaranteed** — each is a separate WorkManager item with its own
 * backoff. The schedule queue therefore skips rows whose beneficiary has not synced yet rather than
 * failing them; see [org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor].
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
  private val visitScheduleSyncScheduler: VisitScheduleSyncScheduler,
) {
  /** Starts one upload attempt across every offline queue. */
  fun syncAllQueues() {
    dynamicFormSyncScheduler.syncNow()
    childFormSyncScheduler.syncNow()
    enrollmentSyncScheduler.syncNow()
    visitScheduleSyncScheduler.syncNow()
  }
}
