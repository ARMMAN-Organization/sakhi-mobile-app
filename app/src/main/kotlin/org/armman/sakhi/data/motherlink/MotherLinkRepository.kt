package org.armman.sakhi.data.motherlink

/**
 * Boundary for the child-enrollment mother link (CR-031): the list of registered mothers a Sakhi can
 * link a newborn to, and that mother's inherited consent state.
 */
interface MotherLinkRepository {

  /**
   * Registered, active mothers selectable in the picker.
   *
   * Three distinct outcomes, and callers must treat them differently:
   * - non-empty list — render the picker
   * - `emptyList()` — the Sakhi genuinely has no registered mothers ("register the mother first")
   * - `null` — nothing could be fetched and nothing was ever cached ("connect once to link a
   *   mother"). Collapsing this into `emptyList()` would tell an offline Sakhi she has no
   *   beneficiaries, which is both wrong and alarming.
   */
  suspend fun getRegisteredMothers(): List<LinkedMother>?

  /**
   * The mother's latest consent record, or null if it could not be read (offline, 404, no records).
   *
   * Null is not an error: selecting a mother must still succeed and prefill everything else. Consent
   * inheritance is simply skipped.
   */
  suspend fun getMotherConsent(motherId: String): LinkedMotherConsent?
}
