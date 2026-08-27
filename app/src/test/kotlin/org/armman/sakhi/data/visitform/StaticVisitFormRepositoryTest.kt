package org.armman.sakhi.data.visitform

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.beneficiaryprofile.StaticBeneficiaryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StaticVisitFormRepositoryTest {
  private val repository = StaticVisitFormRepository(StaticBeneficiaryProfileRepository())

  /** A Sakhi's own enrolment: a generated-UUID id [RECORDS] has never heard of. */
  private class FakeLocalEnrolmentProfileRepository(
    private val profile: BeneficiaryProfile,
  ) : BeneficiaryProfileRepository {
    override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
      if (id != profile.id) throw NoSuchElementException("Unknown id: $id")
      return profile
    }
  }

  private fun realEnrolmentProfile(id: String, lmp: String? = "1 Dec 2025", weight: String? = null) = BeneficiaryProfile(
    id = id,
    name = "Test Beneficiary",
    type = BeneficiaryType.MOTHER,
    ageLabel = "26",
    village = "Sample Village",
    pada = "Sample Pada",
    husbandName = "",
    mobileNumber = "6978484849",
    status = BeneficiaryStatus.ACTIVE,
    riskLevel = RiskLevel.LOW,
    lmp = lmp,
    weight = weight,
  )

  @Test
  fun `mother context carries height, previous hb and advised delivery place`() = runTest {
    val context = repository.getVisitContext("b01", "v2")

    assertEquals("RCH-2025-001234", context.rchNumber)
    assertEquals(152, context.heightCm)
    assertEquals(54.0, context.registrationWeightKg)
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
  fun `canStartVisit is true for a real enrolment not in the seeded records`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000001"
    val repo = StaticVisitFormRepository(FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)))

    assertTrue(repo.canStartVisit(realEnrolmentId))
  }

  @Test
  fun `canStartVisit is false for a blank id`() = runTest {
    assertFalse(repository.canStartVisit(""))
  }

  @Test
  fun `real enrolment gets a synthetic first-visit context with no carried-forward data`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000002"
    val repo = StaticVisitFormRepository(FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)))

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertEquals("ANC1", context.visitTypeLabel)
    assertEquals(LocalDate.of(2025, 12, 1), context.lmp)
    assertNull(context.heightCm)
    assertNull(context.previousHb)
    assertNull(context.advisedDeliveryPlace)
    assertNull(context.sickleCell)
  }

  @Test
  fun `real enrolment context parses registration weight from the profile`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000004"
    val repo = StaticVisitFormRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, weight = "62.5 kg")),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertEquals(62.5, context.registrationWeightKg)
  }

  @Test
  fun `real enrolment context has no registration weight when the profile has none`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000005"
    val repo = StaticVisitFormRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, weight = null)),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertNull(context.registrationWeightKg)
  }

  @Test
  fun `real enrolment context falls back to today when lmp is missing`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000003"
    val repo = StaticVisitFormRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, lmp = null)),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertNotNull(context.lmp)
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
