package org.armman.sakhi.data.dashboard

import kotlinx.coroutines.delay
import org.armman.sakhi.data.auth.CurrentUserRepository
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the dashboard API, mirroring the Figma reference data (p21) — except
 * [sakhiName], which now comes from the real `/me` endpoint via [CurrentUserRepository]. Falls
 * back to the static placeholder name if `/me` hasn't succeeded yet (offline, first launch
 * before the token is ready, etc.). Delete the rest of the hardcoding once the real dashboard
 * endpoint exists — only DI references this class.
 */
@Singleton
class StaticDashboardRepository @Inject constructor(
  private val currentUserRepository: CurrentUserRepository,
) : DashboardRepository {

  override suspend fun getSummary(): DashboardSummary {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return DashboardSummary(
      sakhiName = currentUserRepository.getProfile()?.displayName ?: "Tarini Swaraj",
      lastUploadedOn = LocalDate.of(2026, 4, 14),
      activeVisits = ActiveVisits(
        month = YearMonth.of(2026, 1),
        openCount = 12,
        endingCount = 2,
        pendingReferralCount = 5,
      ),
      activeBeneficiaries = ActiveBeneficiaries(
        mothersTotal = 21,
        mothersHighRisk = 8,
        infantsTotal = 10,
        infantsHighRisk = 4,
      ),
    )
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L
  }
}
