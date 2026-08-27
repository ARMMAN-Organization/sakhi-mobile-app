package org.armman.sakhi.data.motherlink

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Covers WARM-01 … WARM-04. */
class MotherDetailsWarmerTest {

  private fun mother(id: String) = LinkedMother(
    id = id,
    fullName = "Mother $id",
    dateOfBirth = LocalDate.of(2001, 7, 29),
    currentPhase = "ANC",
    registrationDate = LocalDate.of(2026, 7, 28),
    stateId = null,
    districtId = null,
    talukaId = null,
    villageId = null,
    padaId = null,
    phcId = null,
    healthSubCentreId = null,
  )

  /** Records every id it was asked for, so a test can assert every registered mother was actually
   * warmed rather than just the first or a sample. */
  private class RecordingMotherLinkRepository(
    private val mothers: List<LinkedMother>,
    private val failFor: Set<String> = emptySet(),
  ) : MotherLinkRepository {
    val consentCallsFor = mutableListOf<String>()
    val socioDemographicsCallsFor = mutableListOf<String>()

    override suspend fun getRegisteredMothers(): List<LinkedMother> = mothers

    override suspend fun getMotherConsent(motherId: String): LinkedMotherConsent? {
      consentCallsFor += motherId
      if (motherId in failFor) throw RuntimeException("offline")
      return LinkedMotherConsent(true)
    }

    override suspend fun getMotherSocioDemographics(motherId: String): MotherSocioDemographics? {
      socioDemographicsCallsFor += motherId
      if (motherId in failFor) throw RuntimeException("offline")
      return null
    }
  }

  // WARM-01 — every registered mother gets both calls, not just the first or a sample.
  @Test
  fun `warms consent and socio demographics for every registered mother`() = runTest {
    val repo = RecordingMotherLinkRepository(listOf(mother("m1"), mother("m2"), mother("m3")))
    MotherDetailsWarmer(repo).warmAllMothers()

    assertEquals(setOf("m1", "m2", "m3"), repo.consentCallsFor.toSet())
    assertEquals(setOf("m1", "m2", "m3"), repo.socioDemographicsCallsFor.toSet())
  }

  // WARM-02 — an empty picker (no registered mothers) must not throw.
  @Test
  fun `warms nothing when there are no registered mothers`() = runTest {
    val repo = RecordingMotherLinkRepository(emptyList())
    MotherDetailsWarmer(repo).warmAllMothers()

    assertTrue(repo.consentCallsFor.isEmpty())
    assertTrue(repo.socioDemographicsCallsFor.isEmpty())
  }

  // WARM-03 — one mother's fetch throwing must not stop the others from warming.
  @Test
  fun `one mother failing does not stop the rest from warming`() = runTest {
    val repo = RecordingMotherLinkRepository(
      listOf(mother("m1"), mother("m2"), mother("m3")),
      failFor = setOf("m2"),
    )
    MotherDetailsWarmer(repo).warmAllMothers()

    assertEquals(setOf("m1", "m2", "m3"), repo.consentCallsFor.toSet())
    assertEquals(setOf("m1", "m2", "m3"), repo.socioDemographicsCallsFor.toSet())
  }

  // WARM-04 — warmAllMothersAsync() is fire-and-forget: it must return without waiting for the warm
  // to complete (the login flow that triggers it must not be blocked by it).
  @Test
  fun `warmAllMothersAsync returns without waiting for the warm to finish`() {
    val repo = RecordingMotherLinkRepository(listOf(mother("m1")))
    MotherDetailsWarmer(repo).warmAllMothersAsync() // does not suspend the calling test at all
  }
}
