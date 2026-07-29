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
 * Warms the submit-critical lookups (CASE_TYPE, BENEFICIARY_TYPE) whenever the device (re)gains
 * connectivity, so a submission attempted right after coming back online can resolve the reference
 * data it needs instead of failing with a 422.
 *
 * **This class used to also auto-sync pending drafts on reconnect; it no longer does.** SRS §3A.1
 * specifies *"Data Sync — Manual trigger"*, so the only thing that starts an upload is the Sakhi
 * tapping Data Upload on Home ([org.armman.sakhi.data.sync.ManualSyncTrigger]). The sync scheduler
 * is intentionally **not** a dependency here — that's what makes it impossible for a later edit to
 * quietly reintroduce a connectivity-driven sync through this class.
 *
 * Warming lookups is not a data sync: it is a read of reference data that moves no beneficiary
 * data to or from the server, so it stays event-driven. The upstream [ConnectivityObserver] is
 * already distinct-until-changed; the extra [distinctUntilChanged] here makes this correct
 * regardless of the source's contract. The first emission (current state) also warms when already
 * online, which is the desired app-start behaviour.
 */
@Singleton
class ReconnectLookupWarmer @Inject constructor(
  private val connectivityObserver: ConnectivityObserver,
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
        .collect { lookupWarmer.warmSubmitCriticalCategories() }
    }
  }
}
