package org.armman.sakhi.data.forms

import org.armman.sakhi.data.connectivity.ConnectivityObserver
import org.armman.sakhi.data.lookup.LookupWarmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-syncs pending offline MOTHER_REGISTRATION drafts the moment the device (re)gains
 * connectivity — the missing piece that previously forced the Sakhi to reopen the app before a
 * submission queued while offline would upload.
 *
 * Every transition to "online" nudges [DynamicFormSyncScheduler.syncNow], which enqueues the
 * WorkManager sync job; because that job carries a `NetworkType.CONNECTED` constraint it runs
 * essentially immediately. The upstream [ConnectivityObserver] is already distinct-until-changed;
 * the extra [distinctUntilChanged] here makes the trigger correct regardless of the source's
 * contract. The first emission (current state) also fires a sync when already online — a harmless
 * safety net equivalent to the app-start periodic schedule.
 *
 * On each reconnect it also warms the submit-critical lookups ([LookupWarmer]) so a queued draft
 * that failed because reference data hadn't loaded can resolve it on the retry.
 *
 * Scope-only (mother/dynamic form): child and static-enrollment queues are intentionally not
 * touched here, per the fix's agreed scope.
 */
@Singleton
class ReconnectSyncTrigger @Inject constructor(
  private val connectivityObserver: ConnectivityObserver,
  private val syncScheduler: DynamicFormSyncScheduler,
  private val lookupWarmer: LookupWarmer,
) {
  /**
   * Begins observing connectivity on [scope] (an application-lifetime scope). Idempotent per
   * connectivity transition. The collection lives as long as [scope] does, so pass a scope tied to
   * the process, not a short-lived one.
   */
  fun start(scope: CoroutineScope) {
    scope.launch {
      connectivityObserver.observe()
        .distinctUntilChanged()
        .filter { online -> online }
        .collect {
          lookupWarmer.warmSubmitCriticalCategories()
          syncScheduler.syncNow()
        }
    }
  }
}
