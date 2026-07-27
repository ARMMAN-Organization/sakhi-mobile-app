package org.armman.sakhi.data.dashboard

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.CurrentUserProfile
import org.armman.sakhi.data.auth.FakeCurrentUserRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticDashboardRepositoryTest {
  private val currentUserRepository = FakeCurrentUserRepository(displayName = null)
  private val repository = StaticDashboardRepository(currentUserRepository)

  @Test
  fun `falls back to the static name when the me endpoint has not returned one`() = runTest {
    val summary = repository.getSummary()

    assertEquals("Tarini Swaraj", summary.sakhiName)
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

    assertTrue(summary.activeVisits.openCount >= 0)
    assertTrue(summary.activeVisits.endingCount >= 0)
    assertTrue(summary.activeVisits.pendingReferralCount >= 0)
    assertTrue(b.mothersHighRisk in 0..b.mothersTotal)
    assertTrue(b.infantsHighRisk in 0..b.infantsTotal)
  }

  @Test
  fun `uses the me endpoint's display name when available`() = runTest {
    currentUserRepository.profile = CurrentUserProfile(
      username = "jane.sakhi",
      displayName = "Jane Sakhi",
      mobileNumber = null,
      projectName = null,
      cardNumber = null,
      maskedBankAccount = null,
    )

    val summary = repository.getSummary()

    assertEquals("Jane Sakhi", summary.sakhiName)
  }
}
