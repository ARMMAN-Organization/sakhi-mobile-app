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
   * Exists because [getVisitContext] throws for an id it does not recognise, and since CR-022g the
   * app has beneficiaries it will not recognise: a Sakhi's own enrolments carry generated UUIDs,
   * while the static implementation only knows its seeded ids. Without this check, tapping Start
   * Visit on a real enrolment drops her on an error screen.
   *
   * Goes away with CR-026, when the Visit Form is backed by real data and every beneficiary
   * qualifies.
   */
  suspend fun canStartVisit(beneficiaryId: String): Boolean
}
