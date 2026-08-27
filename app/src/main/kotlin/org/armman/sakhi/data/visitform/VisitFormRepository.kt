package org.armman.sakhi.data.visitform

/**
 * Visit Form data boundary. [getVisitContext] supplies the carried-forward
 * fields a Visit Data form auto-populates from registration/prior visits
 * (CR-016b); [saveVisit] persists a completed visit (CR-016d — not called
 * yet). Implementations throw [NoSuchElementException] for an unknown
 * beneficiary id.
 */
interface VisitFormRepository {
  suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext

  /**
   * Whether a Visit Form can be opened for this beneficiary at all.
   *
   * Exists because [getVisitContext] can still throw for a genuinely unknown id (neither seeded
   * nor a real enrolment) — the screen uses this to fail closed on a blank/missing id rather than
   * navigate into an error state. Since CR-026 interim, a Sakhi's own real enrolment (generated
   * UUID) resolves via a synthetic fallback context, so this no longer blocks her.
   *
   * Goes away entirely with CR-026, when the Visit Form is backed by real data.
   */
  suspend fun canStartVisit(beneficiaryId: String): Boolean
}
