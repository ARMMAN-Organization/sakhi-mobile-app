package org.armman.sakhi.data.motherlink

/**
 * Boundary for the child-enrollment mother link (CR-031): the list of registered mothers a Sakhi can
 * link a newborn to, that mother's inherited consent state, and her socio-demographic details
 * (CR-032).
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

  /**
   * Rows 21–34 socio-demographic details (CR-032), or null if they could not be read at all
   * (offline, 404, malformed body). A successful read still returns a value even when every
   * individual field inside it is null — see [MotherSocioDemographics] — so [MotherPrefill] can skip
   * fields independently rather than losing every row 21–34 field because one was missing.
   *
   * Deliberately a second call, mirroring [getMotherConsent]: allowed to fail without failing the
   * selection, so an offline Sakhi still gets the id/name/DOB/geography/consent prefill and only
   * loses these extra rows.
   */
  suspend fun getMotherSocioDemographics(motherId: String): MotherSocioDemographics?
}
