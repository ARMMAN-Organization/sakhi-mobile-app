package org.armman.sakhi.data.motherlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warms every registered mother's consent + socio-demographic details (CR-032) while online, so a
 * Sakhi who loses connectivity for the rest of the day already has them cached for every mother in
 * her list — not just the one she happened to select before going offline.
 *
 * Selecting a mother once while online to "warm" her record does not hold up in the field: a Sakhi
 * routinely has no connectivity for the whole day and may have ten registered mothers she has never
 * opened yet. This is the same reasoning as [org.armman.sakhi.data.lookup.LookupWarmer] — fetch
 * reference data ahead of time, at a moment that already has connectivity, instead of at the moment
 * a Sakhi is picking a mother in the field with none.
 *
 * Deliberately does not filter by [LinkedMother.deliveryNotRecorded] or any other property: every
 * mother in the picker is a candidate for enrollment, so every one is worth warming.
 */
@Singleton
class MotherDetailsWarmer @Inject constructor(
  private val motherLinkRepository: MotherLinkRepository,
) {
  // Process-lifetime scope so a fire-and-forget warm survives the screen/ViewModel that kicked it
  // off (e.g. login navigating away immediately after success) — same shape as LookupWarmer.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Fire-and-forget [warmAllMothers] on the warmer's own scope. */
  fun warmAllMothersAsync() {
    scope.launch { warmAllMothers() }
  }

  /**
   * Fetches (and, via [MotherLinkRepository]'s own disk cache, persists) consent + socio-demographic
   * details for every currently registered mother. Suspends until every attempt finishes; never
   * throws — one mother's failed fetch (offline mid-warm, a 404, a malformed body) must not stop the
   * rest from warming.
   *
   * Fetched concurrently, both calls per mother and across mothers: sequentially awaiting up to 50
   * mothers × 2 calls each would make this too slow to reliably finish inside a short connectivity
   * window (login, or a brief window of signal in the field).
   */
  suspend fun warmAllMothers() {
    val mothers = motherLinkRepository.getRegisteredMothers().orEmpty()
    coroutineScope {
      mothers.forEach { mother ->
        launch { runCatching { motherLinkRepository.getMotherConsent(mother.id) } }
        launch { runCatching { motherLinkRepository.getMotherSocioDemographics(mother.id) } }
      }
    }
  }
}
