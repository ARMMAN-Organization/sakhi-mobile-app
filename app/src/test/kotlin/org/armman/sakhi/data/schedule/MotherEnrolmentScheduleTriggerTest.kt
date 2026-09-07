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
 * Bug fix (2026-09-02): a baseline HIGH-risk enrolment finding (Sickle Cell Disease, reported
 * live — see [org.armman.sakhi.data.schedule.VisitScheduleCoordinator.onEnrollmentHighRiskDetected]'s
 * own doc for the full gap this closes) never generated an ANC-HR follow-up visit. This is the new
 * caller that wires [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment]'s baseline check to
 * the schedule.
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
  fun `Sickle Cell Disease at enrolment generates an ANC-HR visit, even with no LMP`() = runTest {
    val answers = FormAnswers(singleValues = mapOf(Q.SICKLE_CELL_STATUS to V.SICKLE_CELL_DISEASE))

    val generated = trigger.generateFor(BENEFICIARY, answers, registrationDate)

    // No LMP in the answers, so the regular ANC series is (correctly) skipped - the return value
    // stays 0 for that reason alone - but the baseline HR visit must still be generated.
    assertEquals(0, generated)
    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(1, stored.size)
    assertEquals(VisitCodeType.ANC_HR, stored.single().visitType)
    assertEquals(registrationDate.plusDays(15), stored.single().scheduledDate)
  }

  @Test
  fun `Sickle Cell Disease alongside a normal LMP generates both the ANC series and the HR visit`() = runTest {
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
    assertEquals(1, stored.count { it.visitType == VisitCodeType.ANC_HR })
  }

  @Test
  fun `a Sickle Cell Trait finding (LOW risk by SRS design) does not generate an HR visit`() = runTest {
    // EnrollmentRiskAssessment deliberately returns SCT as a LOW/health-message-only finding, not
    // HIGH - see that class's own doc. Only SCD (HIGH) should reach the schedule.
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
  fun `a retried submission does not generate a second HR visit`() = runTest {
    val answers = FormAnswers(singleValues = mapOf(Q.SICKLE_CELL_STATUS to V.SICKLE_CELL_DISEASE))

    trigger.generateFor(BENEFICIARY, answers, registrationDate)
    trigger.generateFor(BENEFICIARY, answers, registrationDate)

    assertEquals(1, repository.getForBeneficiary(BENEFICIARY).count { it.visitType == VisitCodeType.ANC_HR })
  }

  private companion object {
    const val BENEFICIARY = "ben-1"
  }
}
