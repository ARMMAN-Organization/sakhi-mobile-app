package org.armman.sakhi.data.dashboard

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticDashboardRepositoryTest {
  private val repository = StaticDashboardRepository()

  @Test
  fun `returns the expected static summary`() = runTest {
    val summary = repository.getSummary()

    assertEquals("Tarini Swaraj", summary.sakhiName)
    assertEquals(8, summary.pendingUploadCount)
    assertEquals(12, summary.activeVisits.openCount)
    assertEquals(2, summary.activeVisits.endingCount)
    assertEquals(5, summary.activeVisits.pendingReferralCount)
    assertEquals(21, summary.activeBeneficiaries.mothersTotal)
    assertEquals(10, summary.activeBeneficiaries.infantsTotal)
  }

  @Test
  fun `counts are non-negative and high-risk never exceeds totals`() = runTest {
    val summary = repository.getSummary()
    val b = summary.activeBeneficiaries

    assertTrue(summary.pendingUploadCount >= 0)
    assertTrue(summary.activeVisits.openCount >= 0)
    assertTrue(summary.activeVisits.endingCount >= 0)
    assertTrue(summary.activeVisits.pendingReferralCount >= 0)
    assertTrue(b.mothersHighRisk in 0..b.mothersTotal)
    assertTrue(b.infantsHighRisk in 0..b.infantsTotal)
  }
}
