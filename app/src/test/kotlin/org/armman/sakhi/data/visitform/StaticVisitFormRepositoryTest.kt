package org.armman.sakhi.data.visitform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.beneficiaryprofile.StaticBeneficiaryProfileRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.lmpchange.LmpChangeCapture
import org.armman.sakhi.data.referral.ReferralCapture
import org.armman.sakhi.data.rules.RiskGradingResult
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StaticVisitFormRepositoryTest {
  private val repository = buildRepository(StaticBeneficiaryProfileRepository())

  /** A Sakhi's own enrolment: a generated-UUID id [RECORDS] has never heard of. */
  private class FakeLocalEnrolmentProfileRepository(
    private val profile: BeneficiaryProfile,
  ) : BeneficiaryProfileRepository {
    override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
      if (id != profile.id) throw NoSuchElementException("Unknown id: $id")
      return profile
    }
  }

  /** In-memory stand-in for [VisitScheduleRepository] — only [getForBeneficiary] is ever read by
   * [StaticVisitFormRepository]; every other member throws if a test somehow reaches it. */
  private class FakeVisitScheduleRepository(
    private val schedules: List<VisitScheduleEntity> = emptyList(),
  ) : VisitScheduleRepository {
    override suspend fun saveGenerated(schedules: List<VisitScheduleEntity>) = unsupported()
    override suspend fun getForBeneficiary(localBeneficiaryId: String) =
      schedules.filter { it.localBeneficiaryId == localBeneficiaryId }
    override suspend fun getByLocalScheduleUuid(localScheduleUuid: String) = unsupported()
    override suspend fun getActiveForBeneficiary(localBeneficiaryId: String) = unsupported()
    override fun observeActiveForBeneficiary(localBeneficiaryId: String) = unsupported()
    override suspend fun getOpenByType(localBeneficiaryId: String, visitType: VisitCodeType) = unsupported()
    override suspend fun hasSchedule(localBeneficiaryId: String) = unsupported()
    override suspend fun hasScheduleOfType(localBeneficiaryId: String, visitType: VisitCodeType) = unsupported()
    override suspend fun getUnsynced() = unsupported()
    override fun observeUnsyncedCount() = unsupported()
    override suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String) = unsupported()
    override suspend fun attachServerBeneficiaryId(localBeneficiaryId: String, serverBeneficiaryId: String) = unsupported()
    override suspend fun updateStatus(localScheduleUuid: String, status: VisitScheduleStatus, reasonCode: String?) = unsupported()
    override suspend fun lapseOpenAncVisits(localBeneficiaryId: String) = unsupported()
    override suspend fun lapseAllOpenVisits(localBeneficiaryId: String) = unsupported()
    override suspend fun supersedeOpenVisits(localBeneficiaryId: String) = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not needed by these tests")
  }

  /** In-memory stand-in for [VisitFormDraftRepository] — only [getAnswers] is ever read by
   * [StaticVisitFormRepository]; every other member throws if a test somehow reaches it. */
  private class FakeVisitFormDraftRepository(
    private val answersByScheduleUuid: Map<String, FormAnswers> = emptyMap(),
  ) : VisitFormDraftRepository {
    override suspend fun submitDraft(
      localScheduleUuid: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      visitDate: LocalDate,
      riskResult: RiskGradingResult?,
      referralCapture: ReferralCapture?,
      lmpChangeCapture: LmpChangeCapture?,
    ): VisitFormSubmitResult = unsupported()
    override suspend fun getUploadRecords(): List<FormUploadRecord> = unsupported()
    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = unsupported()
    override suspend fun getAnswers(localScheduleUuid: String): FormAnswers? = answersByScheduleUuid[localScheduleUuid]
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not needed by these tests")
  }

  private fun buildRepository(
    profileRepository: BeneficiaryProfileRepository,
    schedules: List<VisitScheduleEntity> = emptyList(),
    draftAnswers: Map<String, FormAnswers> = emptyMap(),
  ) = StaticVisitFormRepository(
    profileRepository,
    FakeVisitScheduleRepository(schedules),
    FakeVisitFormDraftRepository(draftAnswers),
  )

  private fun realEnrolmentProfile(
    id: String,
    lmp: String? = "1 Dec 2025",
    weight: String? = null,
    rchNumber: String? = null,
  ) = BeneficiaryProfile(
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
    rchNumber = rchNumber,
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
    val repo = buildRepository(FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)))

    assertTrue(repo.canStartVisit(realEnrolmentId))
  }

  @Test
  fun `canStartVisit is false for a blank id`() = runTest {
    assertFalse(repository.canStartVisit(""))
  }

  @Test
  fun `real enrolment gets a synthetic first-visit context with no carried-forward data`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000002"
    val repo = buildRepository(FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)))

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertEquals("ANC1", context.visitTypeLabel)
    assertEquals(LocalDate.of(2025, 12, 1), context.lmp)
    assertEquals("", context.rchNumber)
    assertNull(context.heightCm)
    assertNull(context.previousHb)
    assertNull(context.advisedDeliveryPlace)
    assertNull(context.sickleCell)
  }

  /**
   * Reported bug: RCH number entered at registration always showed blank on ANC1 because
   * [StaticVisitFormRepository.syntheticContext] hardcoded `rchNumber = ""` for every real
   * (non-seeded-demo) beneficiary instead of reading it off her profile.
   */
  @Test
  fun `real enrolment context carries the RCH number entered at registration`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000006"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, rchNumber = "RCH-2026-000123")),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertEquals("RCH-2026-000123", context.rchNumber)
  }

  /** No RCH card on file at registration -- context correctly stays blank/editable, not a crash. */
  @Test
  fun `real enrolment context leaves RCH number blank when the profile has none`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000007"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, rchNumber = null)),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertEquals("", context.rchNumber)
  }

  /**
   * Reported bug (2026-09-04): "Gestational weight gain" on ANC_VISIT always showed the
   * "Auto-calculated" placeholder because [StaticVisitFormRepository] sourced the baseline weight
   * from [BeneficiaryProfile.weight] -- itself read off MOTHER_REGISTRATION's answers, which per
   * the live spec (`Registration_PW_D`) never asks for the woman's weight at all. `weight` alone,
   * with no matching local visit history, must therefore no longer produce a baseline.
   */
  @Test
  fun `real enrolment context has no registration weight from profile weight alone`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000004"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, weight = "62.5 kg")),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertNull(context.registrationWeightKg)
  }

  @Test
  fun `real enrolment context has no registration weight when the profile has none`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000005"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId, weight = null)),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v1")

    assertNull(context.registrationWeightKg)
  }

  /**
   * Bug fix (2026-09-04): the real baseline is her own answered weight from her FIRST ANC visit,
   * carried forward -- read back via [VisitScheduleRepository.getForBeneficiary] (to find the
   * ANC1 schedule row) + [VisitFormDraftRepository.getAnswers] (to read that visit's own answers),
   * the same two stores the visit form itself writes to on submit.
   */
  @Test
  fun `later visit context carries forward the weight answered at the first ANC visit`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000008"
    val anc1ScheduleUuid = "anc1-schedule-uuid"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)),
      schedules = listOf(
        schedule(
          localScheduleUuid = anc1ScheduleUuid,
          localBeneficiaryId = realEnrolmentId,
          sequenceNo = 1,
          status = VisitScheduleStatus.COMPLETED,
        ),
      ),
      draftAnswers = mapOf(
        anc1ScheduleUuid to FormAnswers(
          singleValues = mapOf(VisitFormQuestionCodes.WEIGHT_KG to "54.0"),
        ),
      ),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v2")

    assertEquals(54.0, context.registrationWeightKg)
  }

  /** Opening ANC1 itself -- there is no prior visit yet, so no baseline exists to carry forward
   * (matches [org.armman.sakhi.data.visitform.VisitFormComputedFieldEvaluator]'s own "no sound
   * basis before a baseline exists" convention). Even though the schedule row happens to be the
   * one this visit itself will complete, it must not be read as its own baseline. */
  @Test
  fun `opening the first ANC visit itself has no registration weight yet`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000009"
    val anc1ScheduleUuid = "anc1-schedule-uuid"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)),
      schedules = listOf(
        schedule(
          localScheduleUuid = anc1ScheduleUuid,
          localBeneficiaryId = realEnrolmentId,
          sequenceNo = 1,
          status = VisitScheduleStatus.OPEN,
        ),
      ),
      draftAnswers = mapOf(
        anc1ScheduleUuid to FormAnswers(singleValues = mapOf(VisitFormQuestionCodes.WEIGHT_KG to "54.0")),
      ),
    )

    val context = repo.getVisitContext(realEnrolmentId, anc1ScheduleUuid)

    assertNull(context.registrationWeightKg)
  }

  /** The first ANC visit's schedule row exists (she has a care plan) but no local draft was ever
   * saved for it on THIS device -- e.g. it was recorded on a different device. Falls back to null
   * (the placeholder), rather than crashing or guessing. */
  @Test
  fun `no registration weight when the first ANC visit has no local draft on this device`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000010"
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(realEnrolmentProfile(realEnrolmentId)),
      schedules = listOf(
        schedule(
          localScheduleUuid = "anc1-schedule-uuid",
          localBeneficiaryId = realEnrolmentId,
          sequenceNo = 1,
          status = VisitScheduleStatus.COMPLETED,
        ),
      ),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v2")

    assertNull(context.registrationWeightKg)
  }

  /** A CHILD beneficiary's visit form never runs the mother-only weight-gain calc -- must not even
   * attempt the schedule/draft lookup for one. */
  @Test
  fun `no registration weight lookup for a child beneficiary`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000011"
    val childProfile = realEnrolmentProfile(realEnrolmentId).copy(type = BeneficiaryType.INFANT)
    val repo = buildRepository(
      FakeLocalEnrolmentProfileRepository(childProfile),
      schedules = listOf(
        schedule(
          localScheduleUuid = "anc1-schedule-uuid",
          localBeneficiaryId = realEnrolmentId,
          sequenceNo = 1,
          status = VisitScheduleStatus.COMPLETED,
        ),
      ),
      draftAnswers = mapOf(
        "anc1-schedule-uuid" to FormAnswers(singleValues = mapOf(VisitFormQuestionCodes.WEIGHT_KG to "54.0")),
      ),
    )

    val context = repo.getVisitContext(realEnrolmentId, "v2")

    assertNull(context.registrationWeightKg)
  }

  @Test
  fun `real enrolment context falls back to today when lmp is missing`() = runTest {
    val realEnrolmentId = "b6f1c6d2-91a2-4e3a-9c3e-000000000003"
    val repo = buildRepository(
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
