package org.armman.sakhi.data.visit

/**
 * Today's-visits data boundary. UI depends only on this interface; the
 * backing implementation (static today, visit API later) is bound in DI.
 */
interface VisitRepository {
  suspend fun getTodaysVisits(): List<Visit>
}
