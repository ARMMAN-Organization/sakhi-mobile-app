package org.armman.sakhi.data.lookup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warms the lookup categories that are resolved at submission time but never rendered as form
 * fields — [SUBMIT_CRITICAL_CATEGORIES] (CASE_TYPE, BENEFICIARY_TYPE). Because they are only touched
 * when the Sakhi hits Submit, their first-ever fetch would otherwise happen mid-submit; on a weak or
 * dropped network that fetch fails and `caseTypeLookupId`/`beneficiaryTypeLookupId` can't be
 * resolved, blocking the submission ("Lookup value MOTHER not found in category CASE_TYPE").
 *
 * Fetching them ahead of time (right after login, and again when connectivity returns) persists them
 * via [LookupRepository]/[RemoteLookupRepository]'s cache, so they're available at submit time even
 * offline. Each category is warmed independently so one failure doesn't skip the others; a failed
 * warm is a no-op that a later warm retries (the repository no longer negative-caches empties).
 */
@Singleton
class LookupWarmer @Inject constructor(
  private val lookupRepository: LookupRepository,
) {
  // Process-lifetime scope so a fire-and-forget warm survives the screen/ViewModel that kicked it
  // off (e.g. login navigating away immediately after success).
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Fire-and-forget [warmSubmitCriticalCategories] on the warmer's own scope. */
  fun warmSubmitCriticalCategoriesAsync() {
    scope.launch { warmSubmitCriticalCategories() }
  }

  /** Fetches each submit-critical category (populating the repository cache). Suspends until all
   * attempts finish; never throws. */
  suspend fun warmSubmitCriticalCategories() {
    SUBMIT_CRITICAL_CATEGORIES.forEach { category ->
      runCatching { lookupRepository.getValues(category) }
    }
  }

  companion object {
    val SUBMIT_CRITICAL_CATEGORIES = listOf("CASE_TYPE", "BENEFICIARY_TYPE")
  }
}
