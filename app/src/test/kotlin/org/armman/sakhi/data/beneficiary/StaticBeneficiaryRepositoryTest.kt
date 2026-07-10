package org.armman.sakhi.data.beneficiary

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticBeneficiaryRepositoryTest {
  private val repository = StaticBeneficiaryRepository()

  @Test
  fun `covers every status, risk level, visit state and multiple padas`() = runTest {
    val all = repository.getBeneficiaries()

    assertTrue(all.isNotEmpty())
    assertEquals(
      BeneficiaryStatus.entries.toSet(),
      all.map { it.status }.toSet(),
    )
    assertEquals(
      RiskLevel.entries.toSet(),
      all.map { it.riskLevel }.toSet(),
    )
    assertEquals(
      VisitState.entries.toSet(),
      all.mapNotNull { it.visitState }.toSet(),
    )
    assertTrue(all.map { it.pada }.distinct().size >= 2)
  }

  @Test
  fun `data invariants hold`() = runTest {
    val all = repository.getBeneficiaries()

    assertEquals(all.size, all.map { it.id }.distinct().size) // Unique ids.
    assertFalse(all.any { it.name.isBlank() })
    assertFalse(all.any { it.phoneNumber.isBlank() })
    assertFalse(all.any { it.daysRemaining < 0 })
    // Every ACTIVE beneficiary must have a visit state; others must not.
    assertTrue(
      all.filter { it.status == BeneficiaryStatus.ACTIVE }.all { it.visitState != null },
    )
    assertTrue(
      all.filter { it.status != BeneficiaryStatus.ACTIVE }.all { it.visitState == null },
    )
    // Journey Complete records carry a completion month; others must not.
    assertTrue(
      all.filter { it.status == BeneficiaryStatus.JOURNEY_COMPLETE }
        .all { it.journeyCompletedIn != null },
    )
    assertTrue(
      all.filter { it.status != BeneficiaryStatus.JOURNEY_COMPLETE }
        .all { it.journeyCompletedIn == null },
    )
  }
}
