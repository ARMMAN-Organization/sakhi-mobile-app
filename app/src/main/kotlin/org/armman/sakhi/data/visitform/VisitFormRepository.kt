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
}
