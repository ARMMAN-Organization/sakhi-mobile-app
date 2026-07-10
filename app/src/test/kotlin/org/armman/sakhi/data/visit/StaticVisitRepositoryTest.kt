package org.armman.sakhi.data.visit

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticVisitRepositoryTest {
  private val repository = StaticVisitRepository()

  @Test
  fun `covers both visit types, both beneficiary types, padas, ending and risks`() = runTest {
    val visits = repository.getTodaysVisits()

    assertTrue(visits.isNotEmpty())
    assertEquals(VisitType.entries.toSet(), visits.map { it.visitType }.toSet())
    assertEquals(BeneficiaryType.entries.toSet(), visits.map { it.beneficiaryType }.toSet())
    assertTrue(visits.map { it.pada }.distinct().size >= 3)
    assertTrue(visits.any { it.isEnding })
    assertEquals(RiskLevel.entries.toSet(), visits.map { it.riskLevel }.toSet())
  }

  @Test
  fun `data invariants hold`() = runTest {
    val visits = repository.getTodaysVisits()

    assertEquals(visits.size, visits.map { it.id }.distinct().size) // Unique ids.
    assertFalse(visits.any { it.beneficiaryName.isBlank() })
    assertFalse(visits.any { it.phoneNumber.isBlank() })
    assertFalse(visits.any { it.pada.isBlank() })
    assertFalse(visits.any { it.daysRemaining < 0 })
  }
}
