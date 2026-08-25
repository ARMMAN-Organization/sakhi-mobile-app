package org.armman.sakhi.data.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.rules.CachedRuleSet
import org.armman.sakhi.data.rules.RuleEvaluator
import org.armman.sakhi.data.rules.RuleSetIds
import org.armman.sakhi.data.rules.RuleSetRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Verifies [GoRulesScheduleAdapter]'s response parsing against the backend team's own confirmed
 * example input/output pairs (shared 2026-08-13, computed by actually running their handler code,
 * not hand-calculated) — not a mock, not a guess. This runs as a plain JVM unit test because it
 * never touches [org.armman.sakhi.data.rules.ZenRuleEvaluator]/the real native engine — [FakeRuleEvaluator]
 * below stands in for it, returning exactly the JSON the backend team says their pack returns for
 * a given input. That native-engine-vs-real-device gap is tracked separately (this class's own
 * doc + [GoRulesScheduleFeatureFlag]'s checklist) — this test is scoped to "does this class parse
 * the real response shape correctly," which is fully verifiable without a device.
 */
class GoRulesScheduleAdapterMappingTest {

  private class FakeRuleEvaluator(private val response: JsonObject) : RuleEvaluator {
    override suspend fun evaluate(rulesJson: JsonObject, context: JsonObject): JsonObject = response
  }

  private fun adapterReturning(responseJson: String) = GoRulesScheduleAdapter(
    ruleSetRepository = object : RuleSetRepository {
      override suspend fun getPublishedRuleSet(ruleSetId: String) = CachedRuleSet(
        ruleSetId = ruleSetId,
        ruleVersionId = "test-version",
        versionNo = "test",
        rulesJson = JsonObject(),
      )

      override suspend fun prefetchRuleSets(ruleSetIds: List<String>) = Unit
    },
    ruleEvaluator = FakeRuleEvaluator(JsonParser.parseString(responseJson).asJsonObject),
  )

  @Test
  fun `ppSeries matches the backend team's confirmed PP example exactly`() = runTest {
    // Backend example: input {deliveryDate: "2026-01-01"}
    val adapter = adapterReturning(
      """
      { "visits": [
        { "visitName": "PP1", "scheduledDate": "2026-01-01", "windowOpen": "2026-01-01", "windowClose": "2026-01-15" },
        { "visitName": "PP2", "scheduledDate": "2026-01-16", "windowOpen": "2026-01-16", "windowClose": "2026-01-29" },
        { "visitName": "PP3", "scheduledDate": "2026-02-28", "windowOpen": "2026-02-23", "windowClose": "2026-03-05" },
        { "visitName": "PP4", "scheduledDate": "2026-03-30", "windowOpen": "2026-03-25", "windowClose": "2026-04-04" },
        { "visitName": "PP5", "scheduledDate": "2026-04-29", "windowOpen": "2026-04-24", "windowClose": "2026-05-04" }
      ]}
      """.trimIndent(),
    )
    val context = ScheduleContext(
      localBeneficiaryId = "b1",
      registrationDate = LocalDate.of(2025, 6, 1),
      deliveryDate = LocalDate.of(2026, 1, 1),
    )

    val visits = adapter.ppSeries(context)

    assertNotNull(visits)
    assertEquals(5, visits!!.size)
    val expected = listOf(
      Triple("PP1", "2026-01-01", "2026-01-01" to "2026-01-15"),
      Triple("PP2", "2026-01-16", "2026-01-16" to "2026-01-29"),
      Triple("PP3", "2026-02-28", "2026-02-23" to "2026-03-05"),
      Triple("PP4", "2026-03-30", "2026-03-25" to "2026-04-04"),
      Triple("PP5", "2026-04-29", "2026-04-24" to "2026-05-04"),
    )
    expected.forEachIndexed { index, (visitCode, scheduled, window) ->
      val visit = visits[index]
      assertEquals(visitCode, visit.visitCode)
      assertEquals(VisitCodeType.PP, visit.visitType)
      assertEquals(index + 1, visit.sequenceNo)
      assertEquals(LocalDate.parse(scheduled), visit.scheduledDate)
      assertEquals(LocalDate.parse(window.first), visit.windowStartDate)
      assertEquals(LocalDate.parse(window.second), visit.windowEndDate)
      assertEquals(AnchorType.DELIVERY_DATE, visit.anchorType)
    }
  }

  @Test
  fun `ancSeries maps the visits array AND the separate postEddVisit object`() = runTest {
    // Backend example: input {registrationDate: "2026-01-01", edd: "2026-09-01", deliveryFormFiledDate: null}
    val adapter = adapterReturning(
      """
      {
        "totalRegularVisits": 9,
        "visits": [
          { "visitName": "ANC1", "scheduledDate": "2026-01-01", "windowOpen": "2026-01-01", "windowClose": "2026-01-06" },
          { "visitName": "ANC2", "scheduledDate": "2026-01-31", "windowOpen": "2026-01-26", "windowClose": "2026-02-05" },
          { "visitName": "ANC3", "scheduledDate": "2026-03-02", "windowOpen": "2026-02-25", "windowClose": "2026-03-07" },
          { "visitName": "ANC4", "scheduledDate": "2026-04-01", "windowOpen": "2026-03-27", "windowClose": "2026-04-06" },
          { "visitName": "ANC5", "scheduledDate": "2026-05-01", "windowOpen": "2026-04-26", "windowClose": "2026-05-06" },
          { "visitName": "ANC6", "scheduledDate": "2026-05-31", "windowOpen": "2026-05-26", "windowClose": "2026-06-05" },
          { "visitName": "ANC7", "scheduledDate": "2026-06-30", "windowOpen": "2026-06-25", "windowClose": "2026-07-05" },
          { "visitName": "ANC8", "scheduledDate": "2026-07-30", "windowOpen": "2026-07-25", "windowClose": "2026-08-04" },
          { "visitName": "ANC9", "scheduledDate": "2026-08-29", "windowOpen": "2026-08-24", "windowClose": "2026-09-03" }
        ],
        "postEddVisit": { "visitName": "ANC10", "scheduledDate": "2026-09-09", "windowOpen": "2026-09-09", "windowClose": "2026-09-14" },
        "deliveryFormFiledByEddPlus7": false
      }
      """.trimIndent(),
    )
    val context = ScheduleContext(
      localBeneficiaryId = "b1",
      registrationDate = LocalDate.of(2026, 1, 1),
      edd = LocalDate.of(2026, 9, 1),
    )

    val visits = adapter.ancSeries(context)

    assertNotNull(visits)
    assertEquals("9 regular ANC visits + 1 post-EDD visit", 10, visits!!.size)
    assertEquals("ANC1", visits[0].visitCode)
    assertEquals(VisitCodeType.ANC, visits[0].visitType)
    assertEquals("ANC9", visits[8].visitCode)
    // The post-EDD row: same "ANC10" visitName the backend example uses, but mapped to the
    // distinct ANC_POST_EDD family so it's never confused with a 10th regular ANC visit.
    val postEdd = visits[9]
    assertEquals("ANC10", postEdd.visitCode)
    assertEquals(VisitCodeType.ANC_POST_EDD, postEdd.visitType)
    assertEquals(LocalDate.of(2026, 9, 9), postEdd.scheduledDate)
    assertEquals(AnchorType.EDD, postEdd.anchorType)
  }

  @Test
  fun `ancSeries omits the post-EDD visit when the pack returns postEddVisit null`() = runTest {
    val adapter = adapterReturning(
      """
      {
        "totalRegularVisits": 9,
        "visits": [
          { "visitName": "ANC1", "scheduledDate": "2026-01-01", "windowOpen": "2026-01-01", "windowClose": "2026-01-06" }
        ],
        "postEddVisit": null,
        "deliveryFormFiledByEddPlus7": true
      }
      """.trimIndent(),
    )
    val context = ScheduleContext(
      localBeneficiaryId = "b1",
      registrationDate = LocalDate.of(2026, 1, 1),
      edd = LocalDate.of(2026, 9, 1),
      deliveryFormFilledOn = LocalDate.of(2026, 9, 3),
    )

    val visits = adapter.ancSeries(context)

    assertNotNull(visits)
    assertEquals(1, visits!!.size)
    assertEquals("ANC1", visits[0].visitCode)
  }

  @Test
  fun `nnSeries reads nn1 and nn2 directly, not a visits array`() = runTest {
    // Backend confirmed shape for scenario DAY_0_TO_14: both nn1 and nn2 present.
    val adapter = adapterReturning(
      """
      {
        "scenario": "DAY_0_TO_14",
        "neonatalPhaseApplies": true,
        "nn1": { "visitName": "NN1", "scheduledDate": "2026-01-01", "windowOpen": "2026-01-01", "windowClose": "2026-01-15" },
        "nn2": { "visitName": "NN2", "scheduledDate": "2026-01-16", "windowOpen": "2026-01-16", "windowClose": "2026-01-29" }
      }
      """.trimIndent(),
    )
    val context = ScheduleContext(
      localBeneficiaryId = "b1",
      registrationDate = LocalDate.of(2025, 6, 1),
      deliveryDate = LocalDate.of(2026, 1, 1),
      deliveryFormFilledOn = LocalDate.of(2026, 1, 3),
    )

    val visits = adapter.nnSeries(context)

    assertNotNull(visits)
    assertEquals(2, visits!!.size)
    assertEquals("NN1", visits[0].visitCode)
    assertEquals("NN2", visits[1].visitCode)
    assertEquals(VisitCodeType.NN, visits[0].visitType)
  }

  @Test
  fun `nnSeries returns an empty list for scenario DAY_29_PLUS, not null`() = runTest {
    val adapter = adapterReturning(
      """{ "scenario": "DAY_29_PLUS", "neonatalPhaseApplies": false, "nn1": null, "nn2": null }""",
    )
    val context = ScheduleContext(
      localBeneficiaryId = "b1",
      registrationDate = LocalDate.of(2025, 6, 1),
      deliveryDate = LocalDate.of(2026, 1, 1),
      deliveryFormFilledOn = LocalDate.of(2026, 2, 5),
    )

    val visits = adapter.nnSeries(context)

    assertNotNull("an empty visits list is the real answer for DAY_29_PLUS, not a fallback null", visits)
    assertEquals(0, visits!!.size)
  }

  @Test
  fun `hrVisit reads the single hrVisit object, not a visits array, and respects generateHrVisit=false`() = runTest {
    val notGenerated = adapterReturning(
      """{ "generateHrVisit": false, "hrVisit": null, "cumulative": true }""",
    )
    val context = sampleAncContext()
    val triggeringVisit = sampleAncTriggeringVisit()

    // false → the pack decided against a visit — a real "no," never treated as "rule unavailable."
    assertEquals(
      HrVisitOutcome.NoVisitNeeded,
      notGenerated.hrVisit(context, triggeringVisit, LocalDate.of(2026, 3, 1), existingHrCount = 0),
    )

    val generated = adapterReturning(
      """
      { "generateHrVisit": true, "cumulative": true,
        "hrVisit": { "visitName": "ANC-HR", "scheduledDate": "2026-03-16", "windowOpen": "2026-03-14", "windowClose": "2026-03-18" } }
      """.trimIndent(),
    )
    val outcome = generated.hrVisit(context, triggeringVisit, LocalDate.of(2026, 3, 1), existingHrCount = 0)
    assertTrue("expected Generated, got $outcome", outcome is HrVisitOutcome.Generated)
    val hrRow = (outcome as HrVisitOutcome.Generated).visit
    assertEquals("ANC-HR", hrRow.visitCode)
    assertEquals(VisitCodeType.ANC_HR, hrRow.visitType)
    assertEquals("v1", hrRow.anchorVisitLocalUuid)
  }

  /** The other half of the 2026-08-13 HR fix: no cached rule at all must be distinguishable from
   * "the pack said no" — this is what lets [VisitScheduleCoordinator.generateHrVisit] fall back to
   * the Kotlin HR generator instead of silently producing no visit. */
  @Test
  fun `hrVisit returns RuleUnavailable when no rule is cached yet`() = runTest {
    val adapter = GoRulesScheduleAdapter(
      ruleSetRepository = object : RuleSetRepository {
        override suspend fun getPublishedRuleSet(ruleSetId: String): CachedRuleSet? = null // not fetched yet
        override suspend fun prefetchRuleSets(ruleSetIds: List<String>) = Unit
      },
      ruleEvaluator = FakeRuleEvaluator(JsonObject()),
    )
    val context = sampleAncContext()
    val triggeringVisit = sampleAncTriggeringVisit()

    assertEquals(
      HrVisitOutcome.RuleUnavailable,
      adapter.hrVisit(context, triggeringVisit, LocalDate.of(2026, 3, 1), existingHrCount = 0),
    )
  }

  private fun sampleAncContext() =
    ScheduleContext(localBeneficiaryId = "b1", registrationDate = LocalDate.of(2026, 1, 1))

  private fun sampleAncTriggeringVisit() = VisitScheduleEntity(
    localScheduleUuid = "v1",
    localBeneficiaryId = "b1",
    visitCode = "ANC3",
    visitType = VisitCodeType.ANC,
    sequenceNo = 3,
    scheduledDate = LocalDate.of(2026, 3, 1),
    windowStartDate = LocalDate.of(2026, 2, 24),
    windowEndDate = LocalDate.of(2026, 3, 6),
    anchorType = AnchorType.REGISTRATION,
    anchorDate = LocalDate.of(2026, 1, 1),
    generatedByRuleVersion = "v1",
    escalationPolicy = EscalationPolicy.AFTER_TWO_CONSECUTIVE,
    createdAtEpochMillis = 0L,
  )
}
