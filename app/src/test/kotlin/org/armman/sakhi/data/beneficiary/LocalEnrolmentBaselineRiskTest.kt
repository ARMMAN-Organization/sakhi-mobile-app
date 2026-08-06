package org.armman.sakhi.data.beneficiary

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes
import org.armman.sakhi.data.forms.dynamicFormDraftGson
import org.armman.sakhi.data.forms.dynamicFormDraftPayloadKey
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * CR-034 section H — the badge a Sakhi actually sees on My Beneficiaries. Until CR-034 this source
 * hardcoded [RiskLevel.LOW], so a woman enrolled with two prior pregnancy losses was indistinguishable
 * from a first-time low-risk mother on the list. These tests pin the wiring, not the rules — the rule
 * thresholds live in `EnrollmentRiskAssessmentTest`.
 */
class LocalEnrolmentBaselineRiskTest {

  private lateinit var draftDao: FakeDynamicFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var source: LocalEnrolmentBeneficiarySource

  @Before
  fun setUp() {
    draftDao = FakeDynamicFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    source = LocalEnrolmentBeneficiarySource(
      draftDao,
      secureStore,
      RoomVisitScheduleRepository(FakeVisitScheduleDao()),
      FakeFormsRepository(geography = emptyList()),
    )
  }

  @Test
  fun `H47 an all-low answer set still reads as LOW`() = runTest {
    save("local-1", FormAnswers(singleValues = baseAnswers()))

    assertEquals(RiskLevel.LOW, source.getLocalBeneficiaries(TODAY).single().riskLevel)
  }

  @Test
  fun `H48 two abortions read as HIGH`() = runTest {
    save(
      "local-1",
      FormAnswers(singleValues = baseAnswers() + (FormObstetricRuleset.ABORTIONS to "2")),
    )

    assertEquals(RiskLevel.HIGH, source.getLocalBeneficiaries(TODAY).single().riskLevel)
  }

  @Test
  fun `H49 gravida 4 alone reads as MODERATE`() = runTest {
    save(
      "local-1",
      FormAnswers(singleValues = baseAnswers() + (FormObstetricRuleset.GRAVIDA to "4")),
    )

    assertEquals(RiskLevel.MODERATE, source.getLocalBeneficiaries(TODAY).single().riskLevel)
  }

  /** A draft whose payload never made it to the store is skipped, exactly as before CR-034 —
   * the evaluator must not turn a missing payload into a crash or a phantom LOW-risk card. */
  @Test
  fun `H50 a draft with no stored payload is skipped rather than crashing`() = runTest {
    saveDraftRowOnly("local-1")

    assertEquals(emptyList<Beneficiary>(), source.getLocalBeneficiaries(TODAY))
  }

  @Test
  fun `H51 each beneficiary is graded from her own answers`() = runTest {
    save("local-1", FormAnswers(singleValues = baseAnswers()))
    save(
      "local-2",
      FormAnswers(
        singleValues = baseAnswers() + (MotherRegistrationQuestionCodes.SICKLE_CELL_STATUS to
          MotherRegistrationQuestionCodes.ValueCode.SICKLE_CELL_DISEASE),
      ),
    )

    val byId = source.getLocalBeneficiaries(TODAY).associate { it.id to it.riskLevel }
    assertEquals(RiskLevel.LOW, byId["local-1"])
    assertEquals(RiskLevel.HIGH, byId["local-2"])
  }

  @Test
  fun `H52 child registration drafts are still filtered out`() = runTest {
    save("local-1", FormAnswers(singleValues = baseAnswers()), formCode = "CHILD_REGISTRATION")

    assertTrue(source.getLocalBeneficiaries(TODAY).isEmpty())
  }

  /**
   * The age rule is graded against the stored registration date, not the clock: this woman was 34 on
   * the day she was registered and turns 35 later, which must not silently promote her to high risk
   * without a new assessment.
   */
  @Test
  fun `H-age the stored registration date is the age reference, not today`() = runTest {
    val registeredOn = LocalDate.of(2026, 2, 1)
    save(
      "local-1",
      FormAnswers(
        singleValues = baseAnswers() + ("date_of_birth" to registeredOn.minusYears(34).toString()),
      ),
      registrationDate = registeredOn,
    )

    // Read 8 months later, by when she is 35.
    assertEquals(RiskLevel.LOW, source.getLocalBeneficiaries(LocalDate.of(2026, 10, 1)).single().riskLevel)
  }

  private fun baseAnswers(): Map<String, String> = mapOf(
    "first_name" to "Sunita",
    "last_name" to "Pawar",
  )

  private suspend fun save(
    localBeneficiaryId: String,
    answers: FormAnswers,
    formCode: String = "MOTHER_REGISTRATION",
    registrationDate: LocalDate = REGISTERED_ON,
  ) {
    saveDraftRowOnly(localBeneficiaryId, formCode)
    secureStore.putString(
      dynamicFormDraftPayloadKey(localBeneficiaryId),
      dynamicFormDraftGson.toJson(
        DynamicFormDraftPayload(answers = answers, registrationDateIso = registrationDate.toString()),
      ),
    )
  }

  private suspend fun saveDraftRowOnly(
    localBeneficiaryId: String,
    formCode: String = "MOTHER_REGISTRATION",
  ) {
    draftDao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = formCode,
        formVersionId = "version-1",
        localSubmissionUuid = "submission-$localBeneficiaryId",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 1_754_265_600_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
  }

  private companion object {
    val TODAY: LocalDate = LocalDate.of(2026, 8, 5)
    val REGISTERED_ON: LocalDate = LocalDate.of(2026, 8, 1)
  }
}
