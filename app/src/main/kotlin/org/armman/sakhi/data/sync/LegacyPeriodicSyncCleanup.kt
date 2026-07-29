package org.armman.sakhi.data.sync

import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unique work names of the periodic auto-sync jobs this build no longer schedules (SRS §3A.1 is
 * manual-trigger-only). Kept as literals rather than shared constants precisely *because* the
 * scheduling code that used to define them is gone — these strings must not change or the
 * already-enqueued work on an upgraded device becomes unreachable.
 */
private val LEGACY_PERIODIC_WORK_NAMES = listOf(
  "dynamic_form_sync_periodic",
  "child_form_sync_periodic",
  "enrollment_sync_periodic",
)

/**
 * Cancels the periodic auto-sync work that earlier builds enqueued.
 *
 * This is not cosmetic. `enqueueUniquePeriodicWork` persists in WorkManager's own database, which
 * **survives app upgrade** — so simply deleting the scheduling code would leave every device
 * already in the field auto-syncing on a 15-minute tick forever, silently violating the
 * manual-only requirement in exactly the installs that matter. The work has to be explicitly
 * cancelled once, from a build that still runs.
 *
 * No "has this run?" flag is stored: [WorkManager.cancelUniqueWork] is idempotent and a no-op for
 * a name that isn't enqueued, so running it on every process start is both correct and cheaper
 * than persisting and reading migration state. It is deliberately transitional — once every field
 * install has been through a build containing it, this class and its call in
 * [org.armman.sakhi.SakhiApplication] can be deleted.
 */
@Singleton
class LegacyPeriodicSyncCleanup @Inject constructor(
  private val workManager: WorkManager,
) {
  fun cancelLegacyPeriodicWork() {
    LEGACY_PERIODIC_WORK_NAMES.forEach(workManager::cancelUniqueWork)
  }
}
