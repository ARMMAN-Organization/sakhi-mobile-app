package org.armman.sakhi.data.beneficiaryprofile

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticBeneficiaryProfileRepositoryTest {
  private val repository = StaticBeneficiaryProfileRepository()

  @Test
  fun `returns a mother record with lmp and edd populated`() = runTest {
    val profile = repository.getBeneficiary("b01")

    assertEquals(BeneficiaryType.MOTHER, profile.type)
    assertEquals("Sunita Sharma", profile.name)
    assertTrue(!profile.lmp.isNullOrBlank())
    assertTrue(!profile.edd.isNullOrBlank())
    // DOB/Weight now populated for mothers too — the strip shows them for all types.
    assertTrue(!profile.dob.isNullOrBlank())
    assertTrue(!profile.weight.isNullOrBlank())
    assertTrue(profile.lastVisitStats.isNotEmpty())
  }

  @Test
  fun `mother last visit stats match the design board copy`() = runTest {
    val stats = repository.getBeneficiary("b01").lastVisitStats

    assertEquals(4, stats.size)
    assertEquals(VitalStat(value = "9 (12)", caption = "Low Hb.", abnormal = true), stats[0])
    assertEquals(VitalStat(value = "80 (60)- 140 (120)", caption = "High BP.", abnormal = true), stats[1])
    assertEquals(VitalStat(value = "101 (98.7)", caption = "High Temp.", abnormal = true), stats[2])
    assertEquals(VitalStat(value = "45 (60)", caption = "Low Weight", abnormal = true), stats[3])
  }

  @Test
  fun `child last visit stats keep weight and hb`() = runTest {
    val stats = repository.getBeneficiary("b07").lastVisitStats

    assertEquals(2, stats.size)
    assertEquals(VitalStat(value = "2.1 (3.2)", caption = "Low Weight", abnormal = true), stats[0])
    assertEquals(VitalStat(value = "11 (12)", caption = "Hb.", abnormal = false), stats[1])
  }

  @Test
  fun `returns a child record with dob and weight populated`() = runTest {
    val profile = repository.getBeneficiary("b07")

    assertEquals(BeneficiaryType.INFANT, profile.type)
    assertTrue(!profile.dob.isNullOrBlank())
    assertTrue(!profile.weight.isNullOrBlank())
    assertNull(profile.lmp)
    assertNull(profile.edd)
  }

  @Test(expected = NoSuchElementException::class)
  fun `throws for an unknown id`() = runTest {
    repository.getBeneficiary("does-not-exist")
  }

  @Test
  fun `includes a visit history with an open lead visit and completed history`() = runTest {
    val visits = repository.getBeneficiary("b01").visits

    assertTrue(visits.isNotEmpty())
    val lead = visits.first()
    assertEquals(ProfileVisitState.OPEN, lead.state)
    assertEquals(ProfileVisitAction.START_VISIT, lead.action)
    // Not-yet-due open visit: Start Visit disabled per design.
    assertFalse(lead.startable)
    // The rest of the history is completed and ends with the Enrollment row.
    assertTrue(visits.drop(1).all { it.state == ProfileVisitState.COMPLETED })
    assertEquals("Enrollment", visits.last().label)
  }
}
