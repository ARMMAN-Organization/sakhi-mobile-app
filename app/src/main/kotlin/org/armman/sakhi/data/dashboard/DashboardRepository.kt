package org.armman.sakhi.data.dashboard

/**
 * Dashboard data boundary. UI depends only on this interface; the backing
 * implementation (static today, dashboard API later) is bound in DI.
 */
interface DashboardRepository {
  suspend fun getSummary(): DashboardSummary
}
