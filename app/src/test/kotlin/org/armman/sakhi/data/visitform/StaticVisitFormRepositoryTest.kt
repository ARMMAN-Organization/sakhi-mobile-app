package org.armman.sakhi.data.visitform

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.beneficiaryprofile.StaticBeneficiaryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StaticVisitFormRepositoryTest {
  private val repository = StaticVisitFormRepository(StaticBeneficiaryProfileRepository())

  @Test
  fun `mother context carries height, previous hb and advised delivery place`() = runTest {
    val context = repository.getVisitContext("b01", "v2")

    assertEquals("RCH-2025-001234", context.rchNumber)
    assertEquals(152, context.heightCm)
    assertEquals(8.5, context.previousHb)
    assertEquals(5, context.advisedDeliveryPlace)
    assertEquals(1, context.sickleCell)
  }

  @Test
  fun `child context reuses the mother record with a different previous hb`() = runTest {
    val context = repository.getVisitContext("b07", "v2")

    assertEquals(10.5, context.previousHb)
    assertEquals(152, context.heightCm)
  }

  @Test
  fun `visit type label derives from the visit id`() = runTest {
    assertEquals("ANC1", repository.getVisitContext("b01", "v1").visitTypeLabel)
    assertEquals("ANC2", repository.getVisitContext("b01", "v2").visitTypeLabel)
    assertEquals("ANC3", repository.getVisitContext("b01", "v3").visitTypeLabel)
    assertEquals("ANC4", repository.getVisitContext("b01", "v4").visitTypeLabel)
  }

  @Test
  fun `unrecognized visit id falls back to the base label`() = runTest {
    val context = repository.getVisitContext("b01", "v99")
    assertNotNull(context.visitTypeLabel)
  }

  @Test(expected = NoSuchElementException::class)
  fun `throws for an unknown beneficiary id`() = runTest {
    repository.getVisitContext("ghost", "v1")
  }

  @Test
  fun `every documented beneficiary id resolves to a context`() = runTest {
    val ids = listOf(
      "b01", "b02", "b03", "b04", "b05", "b06", "b07", "b08",
      "b09", "b10", "b11", "b12", "b13", "b14",
    )
    ids.forEach { id ->
      val context = repository.getVisitContext(id, "v2")
      assertNotNull(context.lmp)
    }
  }
}
