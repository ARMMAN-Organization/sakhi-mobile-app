package org.armman.sakhi.data.previsithealth

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticPreVisitHealthHistoryRepositoryTest {
  private val repository = StaticPreVisitHealthHistoryRepository()

  @Test
  fun `mother record has at most 5 risk factors and matches the design board copy`() = runTest {
    val history = repository.getHealthHistory("b01", "v2")

    assertTrue(history.riskFactors.size <= 5)
    assertEquals(2, history.riskFactors.size)

    val anaemia = history.riskFactors.first { it.factorName == "Anaemia" }
    assertEquals(RiskLevel.HIGH, anaemia.riskLevel)
    assertEquals("6.5", anaemia.values.last().value)
    assertTrue(anaemia.values.last().abnormal)

    val hypertension = history.riskFactors.first { it.factorName == "Hypertension" }
    assertEquals(RiskLevel.HIGH, hypertension.riskLevel)
    assertEquals("80/140", hypertension.values.last().value)
  }

  @Test
  fun `non-risk vitals carry no risk level`() = runTest {
    val history = repository.getHealthHistory("b01", "v2")

    assertTrue(history.nonRiskVitals.isNotEmpty())
    assertTrue(history.nonRiskVitals.all { it.riskLevel == null })
  }

  @Test
  fun `child record uses child-appropriate factors`() = runTest {
    val history = repository.getHealthHistory("b07", "v2")

    assertTrue(history.riskFactors.size <= 5)
    assertTrue(history.riskFactors.all { it.factorName != "Hypertension" })
  }

  @Test(expected = NoSuchElementException::class)
  fun `throws for an unknown beneficiary id`() = runTest {
    repository.getHealthHistory("ghost", "v1")
  }
}
