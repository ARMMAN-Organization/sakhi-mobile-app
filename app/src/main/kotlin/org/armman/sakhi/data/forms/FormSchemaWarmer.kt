package org.armman.sakhi.data.forms

import org.armman.sakhi.data.connectivity.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warms every known dynamic form schema ([KnownFormCodes.ALL]) whenever the device (re)gains
 * connectivity, so a Sakhi who has never opened a given form type while online on this device
 * still has its schema cached before she loses signal in the field.
 *
 * CR-Forms-01 ("Forms not loading when offline", Asana task 1218225138592262):
 * [RemoteFormsRepository.getActiveVersion] only falls back to a cached schema for a `formCode` it
 * has fetched successfully at least once on this device -- see that class's own doc. Nothing
 * previously fetched every known form code proactively, so a Sakhi opening a visit or ad-hoc form
 * type for the very first time with no signal hit a hard "couldn't load" error with no way to
 * recover offline (Retry just repeats the same doomed fetch). This closes that gap for the common
 * case by pre-fetching every known schema ahead of time.
 *
 * Exact same shape as [ReconnectLookupWarmer]: connectivity-driven and event-only (no periodic
 * timer -- SRS §3A.1 is manual-trigger-only for data sync/uploads; this is a read of reference
 * data, not a sync, so like the lookup warmer it stays event-driven), and each formCode warmed
 * independently so one failure doesn't skip the rest.
 */
@Singleton
class FormSchemaWarmer @Inject constructor(
  private val connectivityObserver: ConnectivityObserver,
  private val formsRepository: FormsRepository,
) {
  /**
   * Begins observing connectivity on [scope] (an application-lifetime scope). Idempotent per
   * connectivity transition; the collection lives as long as [scope] does, so pass a scope tied to
   * the process, not a short-lived one.
   */
  fun start(scope: CoroutineScope) {
    scope.launch {
      connectivityObserver.observe()
        .distinctUntilChanged()
        .filter { online -> online }
        .collect { warmAll() }
    }
  }

  /** Fetches every [KnownFormCodes.ALL] entry (populating [FormsRepository]'s cache). Suspends
   * until all attempts finish; never throws -- [FormsRepository.getActiveVersion] already
   * swallows its own failures, and [runCatching] here is extra insurance so one unexpected
   * exception can never stop the remaining form codes from being warmed. */
  suspend fun warmAll() {
    KnownFormCodes.ALL.forEach { formCode ->
      runCatching { formsRepository.getActiveVersion(formCode) }
    }
  }
}
