package org.armman.sakhi.data.motherlink

import org.armman.sakhi.data.connectivity.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warms every registered mother's consent + socio-demographic details ([MotherDetailsWarmer])
 * whenever the device (re)gains connectivity — same reasoning and shape as
 * [org.armman.sakhi.data.forms.ReconnectLookupWarmer]: this reads reference data about mothers
 * already known to the app and moves no beneficiary data to or from the server, so it stays
 * event-driven rather than routed through the manual data-sync trigger (SRS §3A.1).
 *
 * The upstream [ConnectivityObserver] is already distinct-until-changed; the extra
 * [distinctUntilChanged] here makes this correct regardless of the source's contract. The first
 * emission (current state) also warms when already online, which is the desired app-start
 * behaviour — matching a Sakhi who opens the app already connected, not just one who reconnects
 * mid-session.
 */
@Singleton
class ReconnectMotherDetailsWarmer @Inject constructor(
  private val connectivityObserver: ConnectivityObserver,
  private val motherDetailsWarmer: MotherDetailsWarmer,
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
        .collect { motherDetailsWarmer.warmAllMothers() }
    }
  }
}
