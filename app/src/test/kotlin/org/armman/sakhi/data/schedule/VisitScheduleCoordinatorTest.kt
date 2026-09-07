package org.armman.sakhi.data.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.rules.CachedRuleSet
import org.armman.sakhi.data.rules.RuleEvaluator
import org.armman.sakhi.data.rules.RuleSetRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * CR-022e cases TR-1 … TR-8 and SU-1 … SU-6.
 *
 * CR-032 (GoRules): [GoRulesScheduleFeatureFlag.ENABLED] is back to `false` (reverted 2026-08-27
 * after the R8/JNI release-build failure — see that flag's own doc). The three tests in this
 * file's "GoRules" section that depend on a wired adapter actually being consulted are therefore
 * `assumeTrue`-skipped while the flag is off, and reactivate on their own when it goes back on.
 * Every test above this file's "GoRules" section
 * still constructs [VisitScheduleCoordinator] without a [GoRulesScheduleAdapter] (`goRulesAdapter`
 * defaults to null), so those tests exercise the flag-on-but-no-adapter-wired path — which, per
 * [VisitScheduleCoordinator]'s own fallback design, behaves exactly like the old Hardcoded-only
 * path for every family. Coverage of [GoRulesScheduleAdapter]'s own request/response mapping lives
 * in [GoRulesScheduleAdapterMappingTest]; coverage of the fallback behavior itself — a wired
 * adapter's answer being used, [HrVisitOutcome.RuleUnavailable] falling back, and
 * [HrVisitOutcome.NoVisitNeeded] being trusted with no fallback — lives in this file's own
 * "GoRules" section below.
 */
class VisitScheduleCoordinatorTest {

  private lateinit var dao: FakeVisitScheduleDao
  private lateinit var repository: RoomVisitScheduleRepository
  private lateinit var coordinator: VisitScheduleCoordinator

  private val lmp = LocalDate.of(2026, 1, 1)
  private val edd = LocalDate.of(2026, 10, 8)
  private val dob = LocalDate.of(2026, 6, 1)

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
  }

  // ---- TR-1 … TR-3: the three trigger points ---------------------------------------------------

  @Test
  fun `TR-1 enrolling a mother generates the ANC schedule`() = runTest {
    val generated = coordinator.onMotherEnrolled(motherContext())

    assertEquals(10, generated)
    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(10, stored.size)
    assertTrue(stored.all { it.visitType == VisitCodeType.ANC })
  }

  @Test
  fun `TR-2 registering a child generates NN and INC`() = runTest {
    val generated = coordinator.onChildRegistered(childContext())

    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(generated, stored.size)
    assertEquals(2, stored.count { it.visitType == VisitCodeType.NN })
    assertTrue(stored.any { it.visitType == VisitCodeType.INC })
  }

  @Test
  fun `registering a child without delivery details generates INC only`() = runTest {
    val context = ScheduleContext(
      localBeneficiaryId = BENEFICIARY,
      registrationDate = dob.plusDays(9),
      dob = dob,
    )

    coordinator.onChildRegistered(context)

    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertTrue(stored.none { it.visitType == VisitCodeType.NN })
    assertEquals(11, stored.count { it.visitType == VisitCodeType.INC })
  }

  // ---- Baseline HR (enrolment-time), bug fix 2026-09-02 -----------------------------------------

  @Test
  fun `onEnrollmentHighRiskDetected generates a single ANC-HR visit anchored to registration`() = runTest {
    val registrationDate = LocalDate.of(2026, 8, 4)

    val visit = coordinator.onEnrollmentHighRiskDetected(
      ScheduleContext(localBeneficiaryId = BENEFICIARY, registrationDate = registrationDate),
    )

    assertNotNull(visit)
    assertEquals(VisitCodeType.ANC_HR, visit!!.visitType)
    assertEquals(registrationDate.plusDays(15), visit.scheduledDate)
    assertEquals(AnchorType.REGISTRATION, visit.anchorType)
    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(1, stored.size)
    assertEquals(visit.localScheduleUuid, stored.single().localScheduleUuid)
  }

  @Test
  fun `onEnrollmentHighRiskDetected is idempotent - a retried enrolment does not generate a second row`() = runTest {
    val context = ScheduleContext(localBeneficiaryId = BENEFICIARY, registrationDate = LocalDate.of(2026, 8, 4))

    val first = coordinator.onEnrollmentHighRiskDetected(context)
    val second = coordinator.onEnrollmentHighRiskDetected(context)

    assertNotNull(first)
    assertNull(second)
    assertEquals(1, repository.getForBeneficiary(BENEFICIARY).count { it.visitType == VisitCodeType.ANC_HR })
  }

  @Test
  fun `onEnrollmentHighRiskDetected does not disturb the regular ANC series`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    coordinator.onEnrollmentHighRiskDetected(
      ScheduleContext(localBeneficiaryId = BENEFICIARY, registrationDate = lmp),
    )

    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertEquals(10, stored.count { it.visitType == VisitCodeType.ANC })
    assertEquals(1, stored.count { it.visitType == VisitCodeType.ANC_HR })
  }

  /**
   * TR-3 — one submission, two effects, both applied. NN is deliberately NOT one of them (CR-042
   * defect fix, 2026-08-21): NN used to generate here, anchored to the MOTHER's own
   * localBeneficiaryId, which let NN1/NN2 complete with no child ever registered — violating the
   * SRS's "register child then NN1, NN2" precondition. NN now only ever generates from
   * [VisitScheduleCoordinator.onChildRegistered], anchored to the child's own id, once
   * DeliveryChildRegistrationSubmissionCoordinator actually registers that child — see
   * `NN generated at child registration is not generated again at delivery` below, and
   * DeliveryChildRegistrationSubmissionCoordinatorTest's own schedule-generation coverage.
   */
  @Test
  fun `TR-3 recording a delivery lapses open ANC visits and generates PP, but not NN`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    val result = coordinator.onDeliveryRecorded(deliveryContext())

    assertEquals(10, result.lapsedAncVisits)
    assertEquals(5, result.ppVisitsGenerated)
    assertEquals(0, result.nnVisitsGenerated)
    assertTrue(repository.getForBeneficiary(BENEFICIARY).none { it.visitType == VisitCodeType.NN })

    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertTrue(
      "No ANC visit may remain open after delivery",
      stored.filter { it.visitType == VisitCodeType.ANC }
        .all { it.status == VisitScheduleStatus.CANCELLED },
    )
  }

  @Test
  fun `a completed ANC visit survives the delivery lapse`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val anc1 = repository.getForBeneficiary(BENEFICIARY).first()
    repository.updateStatus(anc1.localScheduleUuid, VisitScheduleStatus.COMPLETED)

    coordinator.onDeliveryRecorded(deliveryContext())

    assertEquals(
      VisitScheduleStatus.COMPLETED,
      dao.getByLocalUuid(anc1.localScheduleUuid)!!.status,
    )
  }

  @Test
  fun `lapsing still runs when a delivery is recorded twice`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    coordinator.onDeliveryRecorded(deliveryContext())

    // Second submission generates nothing new, but must still leave no open ANC visits.
    val second = coordinator.onDeliveryRecorded(deliveryContext())

    assertEquals(0, second.ppVisitsGenerated)
    assertEquals(0, second.nnVisitsGenerated)
    assertTrue(
      repository.getForBeneficiary(BENEFICIARY)
        .filter { it.visitType == VisitCodeType.ANC }
        .none { it.status in setOf(VisitScheduleStatus.GENERATED, VisitScheduleStatus.OPEN) },
    )
  }

  // ---- TR-5: idempotency -----------------------------------------------------------------------

  @Test
  fun `TR-5 enrolling twice does not double the schedule`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val second = coordinator.onMotherEnrolled(motherContext())

    assertEquals(0, second)
    assertEquals(10, repository.getForBeneficiary(BENEFICIARY).size)
  }

  @Test
  fun `TR-5 registering a child twice does not double the schedule`() = runTest {
    val first = coordinator.onChildRegistered(childContext())
    val second = coordinator.onChildRegistered(childContext())

    assertEquals(0, second)
    assertEquals(first, repository.getForBeneficiary(BENEFICIARY).size)
  }

  /**
   * The reason the guard is per-family rather than global: a mother already has ANC rows when the
   * delivery form arrives, so a global "has any schedule" check would block PP entirely.
   */
  @Test
  fun `an existing ANC schedule does not block PP generation at delivery`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    val result = coordinator.onDeliveryRecorded(deliveryContext())

    assertEquals(5, result.ppVisitsGenerated)
  }

  /** A lapsed series still counts as generated — otherwise a re-run would stack a second one. */
  @Test
  fun `a lapsed ANC series still blocks regeneration through the enrolment trigger`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    coordinator.onDeliveryRecorded(deliveryContext())

    assertEquals(0, coordinator.onMotherEnrolled(motherContext()))
    assertEquals(10, repository.getForBeneficiary(BENEFICIARY).count { it.visitType == VisitCodeType.ANC })
  }

  @Test
  fun `NN generated at child registration is not generated again at delivery`() = runTest {
    coordinator.onChildRegistered(childContext())

    assertEquals(0, coordinator.onDeliveryRecorded(deliveryContext()).nnVisitsGenerated)
    assertEquals(2, repository.getForBeneficiary(BENEFICIARY).count { it.visitType == VisitCodeType.NN })
  }

  // ---- TR-4 / TR-7 / TR-8 ----------------------------------------------------------------------

  /**
   * TR-4. The core SRS requirement — nothing in the generation path touches the network. Proven
   * structurally: the coordinator's only collaborators are the repository and the generators, and
   * no API type appears in its constructor.
   */
  @Test
  fun `TR-4 generation completes with no network collaborator involved`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    assertEquals(10, repository.getForBeneficiary(BENEFICIARY).size)
  }

  /** TR-7 — schedules exist before anything is eligible for upload. */
  @Test
  fun `TR-7 generated rows are not upload-eligible until the beneficiary has synced`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    assertTrue(repository.getUnsynced().isEmpty())

    repository.attachServerBeneficiaryId(BENEFICIARY, "srv-ben")

    assertEquals(10, repository.getUnsynced().size)
  }

  @Test
  fun `every generated row carries a rule version and a unique id`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    coordinator.onDeliveryRecorded(deliveryContext())

    val stored = repository.getForBeneficiary(BENEFICIARY)
    assertTrue(stored.all { it.generatedByRuleVersion.isNotBlank() })
    assertEquals(stored.size, stored.map { it.localScheduleUuid }.distinct().size)
  }

  @Test
  fun `recording a delivery without a delivery date fails loudly`() = runTest {
    val result = runCatching { coordinator.onDeliveryRecorded(motherContext()) }
    assertTrue(result.isFailure)
  }

  // ---- High-risk follow-ups --------------------------------------------------------------------

  @Test
  fun `a high-risk finding on an ANC visit generates an ANC-HR follow-up`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val anc3 = repository.getForBeneficiary(BENEFICIARY)[2]

    val hr = coordinator.onHighRiskDetected(motherContext(), anc3, LocalDate.of(2026, 3, 6))

    assertNotNull(hr)
    assertEquals(VisitCodeType.ANC_HR, hr!!.visitType)
    assertEquals(LocalDate.of(2026, 3, 21), hr.scheduledDate)
    assertEquals(anc3.localScheduleUuid, hr.anchorVisitLocalUuid)
  }

  @Test
  fun `consecutive high-risk findings are numbered in sequence`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val visits = repository.getForBeneficiary(BENEFICIARY)

    coordinator.onHighRiskDetected(motherContext(), visits[2], LocalDate.of(2026, 3, 6))
    val second = coordinator.onHighRiskDetected(motherContext(), visits[4], LocalDate.of(2026, 5, 2))

    assertEquals(2, second!!.sequenceNo)
    assertEquals("ANC-HR2", second.visitCode)
  }

  /** SR-NN-01 — the neonatal phase routes to referral rather than generating an HR visit. */
  @Test
  fun `a high-risk finding on a neonatal visit generates no follow-up`() = runTest {
    coordinator.onChildRegistered(childContext())
    val nn = repository.getForBeneficiary(BENEFICIARY).first { it.visitType == VisitCodeType.NN }

    assertNull(coordinator.onHighRiskDetected(childContext(), nn, LocalDate.of(2026, 6, 10)))
  }

  @Test
  fun `an HR follow-up does not disturb the regular chain`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val before = repository.getForBeneficiary(BENEFICIARY)
      .filter { it.visitType == VisitCodeType.ANC }
      .map { it.scheduledDate }

    coordinator.onHighRiskDetected(
      motherContext(),
      repository.getForBeneficiary(BENEFICIARY)[2],
      LocalDate.of(2026, 3, 6),
    )

    val after = repository.getForBeneficiary(BENEFICIARY)
      .filter { it.visitType == VisitCodeType.ANC }
      .map { it.scheduledDate }
    assertEquals(before, after)
  }

  // ---- CCV transition --------------------------------------------------------------------------

  @Test
  fun `the CCV journey is generated at the end of the infant phase`() = runTest {
    coordinator.onChildRegistered(childContext())

    val generated = coordinator.onIncPhaseCompleted(childContext(), emptyList())

    assertEquals(6, generated)
    assertEquals(
      1,
      repository.getForBeneficiary(BENEFICIARY).count { it.visitType == VisitCodeType.CCV_HR },
    )
  }

  @Test
  fun `the CCV journey is generated only once`() = runTest {
    coordinator.onChildRegistered(childContext())
    coordinator.onIncPhaseCompleted(childContext(), emptyList())

    assertEquals(0, coordinator.onIncPhaseCompleted(childContext(), emptyList()))
  }

  @Test
  fun `a current high-risk state brings the CCV journey forward`() = runTest {
    coordinator.onChildRegistered(childContext())
    val outcomes = listOf(
      IncVisitOutcome("inc1", dob.plusDays(58), HrFinding.SAM),
    )

    coordinator.onIncPhaseCompleted(childContext(), outcomes)

    val ccvHr = repository.getForBeneficiary(BENEFICIARY).first { it.visitType == VisitCodeType.CCV_HR }
    val transition = repository.getForBeneficiary(BENEFICIARY)
      .filter { it.visitType == VisitCodeType.INC }
      .maxOf { it.scheduledDate }
    assertEquals(transition.plusDays(30), ccvHr.scheduledDate)
  }

  // ---- SU-1 … SU-6: supersession ---------------------------------------------------------------

  @Test
  fun `SU-1 an approved LMP change supersedes the open cohort and regenerates`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    val correctedEdd = edd.plusDays(20)
    val result = coordinator.onLmpOrEddApproved(motherContext(edd = correctedEdd))

    assertEquals(10, result.supersededVisits)
    assertTrue(result.generatedVisits > 0)
  }

  @Test
  fun `SU-2 completed visits are never superseded`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val anc1 = repository.getForBeneficiary(BENEFICIARY).first()
    repository.updateStatus(anc1.localScheduleUuid, VisitScheduleStatus.COMPLETED)

    coordinator.onLmpOrEddApproved(motherContext(edd = edd.plusDays(20)))

    assertEquals(VisitScheduleStatus.COMPLETED, dao.getByLocalUuid(anc1.localScheduleUuid)!!.status)
  }

  @Test
  fun `SU-3 supersession never deletes a row`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    val before = repository.getForBeneficiary(BENEFICIARY).size

    val result = coordinator.onLmpOrEddApproved(motherContext(edd = edd.plusDays(20)))

    assertEquals(before + result.generatedVisits, repository.getForBeneficiary(BENEFICIARY).size)
  }

  @Test
  fun `SU-6 superseded rows disappear from the active list`() = runTest {
    coordinator.onMotherEnrolled(motherContext())
    coordinator.onLmpOrEddApproved(motherContext(edd = edd.plusDays(20)))

    val active = repository.getActiveForBeneficiary(BENEFICIARY)
    assertTrue(active.none { it.status == VisitScheduleStatus.SUPERSEDED })
    assertEquals(
      repository.getForBeneficiary(BENEFICIARY).count { it.status == VisitScheduleStatus.GENERATED },
      active.size,
    )
  }

  @Test
  fun `the regenerated schedule reflects the corrected dates`() = runTest {
    coordinator.onMotherEnrolled(motherContext())

    // Pushing the EDD out by 60 days buys two more ANC visits.
    coordinator.onLmpOrEddApproved(motherContext(edd = edd.plusDays(60)))

    assertEquals(12, repository.getActiveForBeneficiary(BENEFICIARY).size)
  }

  @Test
  fun `regenerating without a corrected EDD fails loudly`() = runTest {
    val context = ScheduleContext(localBeneficiaryId = BENEFICIARY, registrationDate = lmp)

    assertTrue(runCatching { coordinator.onLmpOrEddApproved(context) }.isFailure)
  }

  // ---- GoRules (CR-032) — HR fallback safety net (fixed 2026-08-13) ------------------------------

  /**
   * With no [GoRulesScheduleAdapter] wired ([goRulesAdapter] null, same as this file's default
   * `setUp()`), [HrVisitOutcome.RuleUnavailable] must fall back to the Kotlin HR generator instead
   * of silently producing no visit — the bug this fix closes.
   */
  @Test
  fun `HR follow-up falls back to the Kotlin generator when no adapter is wired`() = runTest {
    assumeTrue(GoRulesScheduleFeatureFlag.ENABLED)
    val withNoAdapterWired = VisitScheduleCoordinator(
      repository = repository,
      ancGenerator = AncScheduleGenerator(HardcodedRuleSource()),
      ppGenerator = PpScheduleGenerator(HardcodedRuleSource()),
      nnGenerator = NnScheduleGenerator(HardcodedRuleSource()),
      incGenerator = IncScheduleGenerator(HardcodedRuleSource()),
      ccvGenerator = CcvScheduleGenerator(HardcodedRuleSource()),
    )
    withNoAdapterWired.onMotherEnrolled(motherContext())
    val anc3 = repository.getForBeneficiary(BENEFICIARY)[2]

    val hr = withNoAdapterWired.onHighRiskDetected(motherContext(), anc3, LocalDate.of(2026, 3, 6))

    // Same dates the plain Hardcoded-only test above expects — proves the fallback, not silence,
    // handled this case.
    assertNotNull(hr)
    assertEquals(VisitCodeType.ANC_HR, hr!!.visitType)
    assertEquals(LocalDate.of(2026, 3, 21), hr.scheduledDate)
  }

  /** Same as above, but the rule genuinely isn't cached yet (adapter wired, repository empty) —
   * still [HrVisitOutcome.RuleUnavailable], still must fall back. */
  @Test
  fun `HR follow-up falls back to the Kotlin generator when the rule isn't cached yet`() = runTest {
    val withUncachedAdapter = VisitScheduleCoordinator(
      repository = repository,
      ancGenerator = AncScheduleGenerator(HardcodedRuleSource()),
      ppGenerator = PpScheduleGenerator(HardcodedRuleSource()),
      nnGenerator = NnScheduleGenerator(HardcodedRuleSource()),
      incGenerator = IncScheduleGenerator(HardcodedRuleSource()),
      ccvGenerator = CcvScheduleGenerator(HardcodedRuleSource()),
      goRulesAdapter = GoRulesScheduleAdapter(NoRuleCachedRepository(), NeverCalledEvaluator()),
    )
    withUncachedAdapter.onMotherEnrolled(motherContext())
    val anc3 = repository.getForBeneficiary(BENEFICIARY)[2]

    val hr = withUncachedAdapter.onHighRiskDetected(motherContext(), anc3, LocalDate.of(2026, 3, 6))

    assertNotNull(hr)
    assertEquals(VisitCodeType.ANC_HR, hr!!.visitType)
    assertEquals(LocalDate.of(2026, 3, 21), hr.scheduledDate)
  }

  /** [HrVisitOutcome.NoVisitNeeded] is a real answer from the pack, not a missing-rule situation —
   * it must be trusted with no fallback, even though a fallback path exists now. */
  @Test
  fun `HR follow-up trusts a real 'no visit needed' answer with no fallback`() = runTest {
    // GoRules reverted to OFF (2026-08-27, R8/JNI release-build failure — see
    // GoRulesScheduleFeatureFlag's own doc). With the flag off the coordinator never
    // consults the adapter at all, so this test's premise cannot hold; skipped rather than
    // deleted so it reactivates automatically when the flag goes back on.
    assumeTrue(GoRulesScheduleFeatureFlag.ENABLED)
    val withGoRulesSayingNo = VisitScheduleCoordinator(
      repository = repository,
      ancGenerator = AncScheduleGenerator(HardcodedRuleSource()),
      ppGenerator = PpScheduleGenerator(HardcodedRuleSource()),
      nnGenerator = NnScheduleGenerator(HardcodedRuleSource()),
      incGenerator = IncScheduleGenerator(HardcodedRuleSource()),
      ccvGenerator = CcvScheduleGenerator(HardcodedRuleSource()),
      goRulesAdapter = GoRulesScheduleAdapter(
        FixedRuleSetRepository(),
        FixedRuleEvaluator(JsonObject().apply { addProperty("generateHrVisit", false) }),
      ),
    )
    withGoRulesSayingNo.onMotherEnrolled(motherContext())
    val anc3 = repository.getForBeneficiary(BENEFICIARY)[2]

    val hr = withGoRulesSayingNo.onHighRiskDetected(motherContext(), anc3, LocalDate.of(2026, 3, 6))

    assertNull("GoRules said no visit is needed — the coordinator must not fall back", hr)
  }

  /** A wired adapter's [HrVisitOutcome.Generated] answer is used as-is — proves the coordinator
   * doesn't ignore a real GoRules answer in favor of the Hardcoded fallback. */
  @Test
  fun `HR follow-up uses the wired adapter's Generated visit directly`() = runTest {
    // GoRules reverted to OFF (2026-08-27, R8/JNI release-build failure — see
    // GoRulesScheduleFeatureFlag's own doc). With the flag off the coordinator never
    // consults the adapter at all, so this test's premise cannot hold; skipped rather than
    // deleted so it reactivates automatically when the flag goes back on.
    assumeTrue(GoRulesScheduleFeatureFlag.ENABLED)
    val goRulesDate = "2026-04-01" // deliberately different from Hardcoded's 2026-03-21
    val withGoRulesAnswer = VisitScheduleCoordinator(
      repository = repository,
      ancGenerator = AncScheduleGenerator(HardcodedRuleSource()),
      ppGenerator = PpScheduleGenerator(HardcodedRuleSource()),
      nnGenerator = NnScheduleGenerator(HardcodedRuleSource()),
      incGenerator = IncScheduleGenerator(HardcodedRuleSource()),
      ccvGenerator = CcvScheduleGenerator(HardcodedRuleSource()),
      goRulesAdapter = GoRulesScheduleAdapter(
        FixedRuleSetRepository(),
        FixedRuleEvaluator(
          JsonObject().apply {
            addProperty("generateHrVisit", true)
            add(
              "hrVisit",
              JsonParser.parseString(
                """{ "visitName": "ANC-HR", "scheduledDate": "$goRulesDate", "windowOpen": "$goRulesDate", "windowClose": "$goRulesDate" }""",
              ),
            )
          },
        ),
      ),
    )
    withGoRulesAnswer.onMotherEnrolled(motherContext())
    val anc3 = repository.getForBeneficiary(BENEFICIARY)[2]

    val hr = withGoRulesAnswer.onHighRiskDetected(motherContext(), anc3, LocalDate.of(2026, 3, 6))

    assertNotNull(hr)
    assertEquals(LocalDate.parse(goRulesDate), hr!!.scheduledDate)
  }

  /** Never returns a cached rule — simulates a phone that hasn't fetched the HR pack yet. */
  private class NoRuleCachedRepository : RuleSetRepository {
    override suspend fun getPublishedRuleSet(ruleSetId: String): CachedRuleSet? = null
    override suspend fun prefetchRuleSets(ruleSetIds: List<String>) = Unit
  }

  /** Never actually called in the "rule unavailable" scenarios above — the adapter returns
   * [HrVisitOutcome.RuleUnavailable] as soon as [NoRuleCachedRepository] returns null, before
   * reaching the evaluator. Throws if that assumption is ever wrong. */
  private class NeverCalledEvaluator : RuleEvaluator {
    override suspend fun evaluate(rulesJson: JsonObject, context: JsonObject): JsonObject? =
      error("no rule was cached — evaluate should never be reached")
  }

  /** Always has a cached rule — pairs with [FixedRuleEvaluator] to control exactly what the
   * "pack" answers, without touching the real native engine. */
  private class FixedRuleSetRepository : RuleSetRepository {
    override suspend fun getPublishedRuleSet(ruleSetId: String) = CachedRuleSet(
      ruleSetId = ruleSetId,
      ruleVersionId = "test-hr-version",
      versionNo = "test",
      rulesJson = JsonObject(),
    )
    override suspend fun prefetchRuleSets(ruleSetIds: List<String>) = Unit
  }

  private class FixedRuleEvaluator(private val response: JsonObject) : RuleEvaluator {
    override suspend fun evaluate(rulesJson: JsonObject, context: JsonObject): JsonObject = response
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun motherContext(edd: LocalDate = this.edd) = ScheduleContext(
    localBeneficiaryId = BENEFICIARY,
    registrationDate = lmp,
    lmp = lmp,
    edd = edd,
  )

  private fun childContext() = ScheduleContext(
    localBeneficiaryId = BENEFICIARY,
    registrationDate = dob.plusDays(9),
    dob = dob,
    deliveryDate = dob,
    deliveryFormFilledOn = dob.plusDays(2),
  )

  private fun deliveryContext() = ScheduleContext(
    localBeneficiaryId = BENEFICIARY,
    registrationDate = lmp,
    lmp = lmp,
    edd = edd,
    deliveryDate = dob,
    deliveryFormFilledOn = dob.plusDays(2),
  )

  private companion object {
    const val BENEFICIARY = "ben-1"
  }
}
