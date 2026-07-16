package org.armman.sakhi.data.previsithealth

/**
 * Pre-Visit Health History data boundary (FR-S-4.6). UI depends only on this
 * interface; the backing implementation (static today, offline-cached visit
 * history later) is bound in DI. Implementations throw [NoSuchElementException]
 * if no trend data exists for the given beneficiary+visit — callers must only
 * reach this screen when [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit.hasPreVisitHistory]
 * is true, so that case is a caller bug, not a normal "first visit" outcome.
 */
interface PreVisitHealthHistoryRepository {
  suspend fun getHealthHistory(beneficiaryId: String, visitId: String): PreVisitHealthHistory
}
