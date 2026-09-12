package org.armman.sakhi.data.schedule

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.LMP_DATE_QUESTION_CODE
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes as Q
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes.ValueCode as V
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 2026-09-11: enrolment-time baseline HR visit generation was removed from
 * [MotherEnrolmentScheduleTrigger] per explicit product decision — a baseline HIGH-risk finding
 * (Sickle Cell Disease, age, obstetric history, etc.) no longer generates an ANC-HR visit at
 * registration. It still sets the beneficiary's High Risk badge (tested separately, unaffected by
 * this class) and is re-detected — correctly, via
 * [org.armman.sakhi.data.visitform.AncRiskRegistrationResolver] merging the same registration
 * fields into every ANC1 GoRules evaluation — once an actual ANC visit is attended, at which point
 * the referral and (if still HIGH) the HR visit both originate from that visit instead. See
 * delivery-log.md 2026-09-11 for the full reasoning. This test class only covers the regular ANC
 * series generation this trigger still owns.
 */
class MotherEnrolmentScheduleTriggerTest {

  private lateinit var dao: FakeVisitScheduleDao
  private lateinit var repository: RoomVisitScheduleRepository
  private lateinit var coordinator: VisitScheduleCoordinator
  private lateinit var trigger: MotherEnrolmentScheduleTrigger

  private val registrationDate = LocalDate.of(2026, 8, 4)
  private val lmp = LocalDate.of(2026, 1, 1)

  @Before
  fun setUp() {
    val rules = HardcodedRuleSource()
    dao = FakeVisitScheduleDao()
    repository = RoomVisitScheduleRepository(dao)
    coordinator = VisitScheduleCoordinator(
      repository = repository,
      ancGenerator = AncScheduleGenerator(rules),
      ppGenerator = PpScheduleGenerator(rules),
      nnGenerator = NnScheduleGenerator(rules),
      incGenerator = IncScheduleGenerator(rules),
      ccvGenerator = CcvScheduleGenerator(rules),
    )
    trigger = MotherEnrolmentScheduleTrigger(coordinator = coordinator, ruleSource = rules)
  }

  @Test
  fun `Sickle Cell Disease at enrolment with no LMP generates no visits at all`() = runTest {
    // No LMP means the regular ANC series is (correctly) skipped, and - since 2026-09-11 - a
    // baseline HIGH-risk finding no longer generates an ANC-HR visit at enrolment either.
    val answers = FormAnswers(singleValues = mapOf(Q.SICKLE_CELL_STATUS to V.SICKLE_CELL_DISEASE))

    val generated = trigger.generateFor(BENEFICIARY, answers, registrationDate)

    assertEquals(0, generated)
    assertTrue(repository.getForBeneficiary(BENEFICIARY).isEmpty())
  }

  @Test
  fun `Sickle Cell Disease alongside a normal LMP generates the ANC series but no HR visit at enrolment`() = runTest {
    val answers = FormAnswers(
      singleValues = mapOf(
        Q.SICKLE_CELL_STATUS to V.SICKLE_CELL_DISEASE,
        LMP_DATE_QUESTION_CODE to lmp.toString(),
      ),
    )

    val generated = trigger.generateFor(BENEFICIARY, answers, registrationDate = lmp)

    assertEquals(10, generated)
    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(10, stored.count { it.visitType == VisitCodeType.ANC })
    assertTrue(stored.none { it.visitType == VisitCodeType.ANC_HR })
  }

  @Test
  fun `a Sickle Cell Trait finding (LOW risk by SRS design) does not generate an HR visit`() = runTest {
    // EnrollmentRiskAssessment deliberately returns SCT as a LOW/health-message-only finding, not
    // HIGH - see that class's own doc. Kept even post-removal: still confirms no HR visit appears
    // for a condition that was never HIGH to begin with.
    val answers = FormAnswers(singleValues = mapOf(Q.SICKLE_CELL_STATUS to V.SICKLE_CELL_TRAIT))

    trigger.generateFor(BENEFICIARY, answers, registrationDate)

    assertTrue(repository.getForBeneficiary(BENEFICIARY).none { it.visitType == VisitCodeType.ANC_HR })
  }

  @Test
  fun `no risky answers at all generates no HR visit`() = runTest {
    trigger.generateFor(BENEFICIARY, FormAnswers(), registrationDate)

    assertTrue(repository.getForBeneficiary(BENEFICIARY).none { it.visitType == VisitCodeType.ANC_HR })
  }

  @Test
  fun `a retried submission does not generate a duplicate ANC series`() = runTest {
    val answers = FormAnswers(
      singleValues = mapOf(
        Q.SICKLE_CELL_STATUS to V.SICKLE_CELL_DISEASE,
        LMP_DATE_QUESTION_CODE to lmp.toString(),
      ),
    )

    trigger.generateFor(BENEFICIARY, answers, registrationDate = lmp)
    trigger.generateFor(BENEFICIARY, answers, registrationDate = lmp)

    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(10, stored.count { it.visitType == VisitCodeType.ANC })
    assertTrue(stored.none { it.visitType == VisitCodeType.ANC_HR })
  }

  private companion object {
    const val BENEFICIARY = "ben-1"
  }
}
