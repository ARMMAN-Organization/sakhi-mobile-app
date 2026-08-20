package org.armman.sakhi.data.schedule

import android.util.Log
import com.google.gson.JsonObject
import org.armman.sakhi.data.rules.RuleEvaluator
import org.armman.sakhi.data.rules.RuleSetIds
import org.armman.sakhi.data.rules.RuleSetRepository
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"

/**
 * Calls a GoRules pack once per family and maps its response into [VisitScheduleEntity] rows —
 * the local-execution counterpart of what rules-service's `evaluate-schedule`-style endpoints do
 * server-side.
 *
 * Rewritten 2026-08-13 against the REAL seeded packs, after the backend team (Dharanish) shared
 * the actual `functionNode` JS handler source for all 7 rule sets. The previous version of this
 * class was written from an API reference before that confirmation and got several things wrong
 * that this rewrite corrects:
 *  - Response shape is **not** a uniform `{visits: [...]}` per family. PP, INC, CCV do return a
 *    `visits` array; ANC's array is joined by a separate nullable `postEddVisit` object; CCV's is
 *    joined by a separate nullable `extensionVisit`; NN returns two individual nullable objects
 *    (`nn1`/`nn2`) with **no array at all**; HR returns a single nullable `hrVisit` object, also
 *    with no array.
 *  - Row objects use `visitName`/`windowOpen`/`windowClose` — not
 *    `visitCode`/`visitType`/`sequenceNo`/`windowStartDate`/`windowEndDate`. `visitName` (e.g.
 *    `"ANC3"`, `"PP1"`, `"ANC-HR"`) is used directly as [VisitScheduleEntity.visitCode]; the
 *    family (`visitType`) is supplied by the caller, not read from the row.
 *  - Several families' *request* field names were wrong — see each method's doc below for the
 *    confirmed shape.
 *
 * [ScheduleRuleSource]/[HardcodedRuleSource] are otherwise unchanged and remain the M2 fallback —
 * this class never touches them, except reading [HardcodedRuleSource.escalationPolicy] in
 * [mapRow] (escalation now has its own confirmed rule pack, [RuleSetIds.ESCALATION], but nothing
 * in this app calls it yet — wiring that in is separate follow-up work, not part of CR-032
 * scheduling).
 *
 * ### Known gap — CCV's HR-extension visit is unreachable through this class today
 * The CCV pack's response includes an `extensionVisit`, computed from a `hrDetectedAtLastCcvVisit`
 * input — but [ccvSeries] is only called once, at the INC→CCV transition, before any CCV visit has
 * happened yet. There is no call wired in to re-evaluate after each *subsequent* CCV visit
 * completes (the way [hrVisit] does for ANC/INC), so `hrDetectedAtLastCcvVisit` is always `false`
 * here. Needs a product/architecture decision (a CCV analogue of [hrVisit]) — flagging rather
 * than guessing at one.
 *
 * ### Known gap — CCV's risk-state fallback branch
 * Per the backend team, the CCV pack's own code has a commented, explicitly-provisional fallback
 * for a risk-state combination the SRS's 5-state table doesn't define (HR ever detected, most
 * recent visit not HR, but the last-3 visits aren't all normal either) — referenced in their
 * PR #129 review as pending SRS/product clarification, not validated clinical behavior. Nothing
 * to fix here; flagging so a test case isn't accidentally written to assert that fallback as
 * correct.
 */
@Singleton
class GoRulesScheduleAdapter @Inject constructor(
  private val ruleSetRepository: RuleSetRepository,
  private val ruleEvaluator: RuleEvaluator,
) {

  /**
   * ANC series at enrolment — the [VisitScheduleCoordinator.onMotherEnrolled] path only.
   * Includes the post-EDD visit (as a [VisitCodeType.ANC_POST_EDD] row) when the pack returns one.
   *
   * `deliveryFormFiledDate` (confirmed spelling — "Filed", not "Filled") is the third, previously
   * missing, request field: omitting it makes the pack treat the delivery form as never filed,
   * which silently forces the post-EDD branch every time.
   */
  suspend fun ancSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val edd = context.edd ?: return null
    val answers = JsonObject().apply {
      addProperty("registrationDate", context.registrationDate.toString())
      addProperty("edd", edd.toString())
      addProperty("deliveryFormFiledDate", context.deliveryFormFilledOn?.toString())
    }
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.ANC) ?: return null
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.ANC) ?: return null
    val rows = response.getAsJsonArray("visits") ?: run {
      Log.w(TAG, "GoRulesScheduleAdapter: ANC response had no 'visits' array")
      return null
    }
    val mapped = rows.mapNotNull { element ->
      mapRow(element.asJsonObject, context, VisitCodeType.ANC, cached.ruleVersionId, context.registrationDate, AnchorType.REGISTRATION)
    }.toMutableList()
    response.objectOrNull("postEddVisit")?.let { row ->
      mapRow(row, context, VisitCodeType.ANC_POST_EDD, cached.ruleVersionId, edd, AnchorType.EDD)?.let(mapped::add)
    }
    return mapped
  }

  /** The five PP visits at delivery form submission. Request and response both match as originally written. */
  suspend fun ppSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val deliveryDate = context.deliveryDate ?: return null
    val answers = JsonObject().apply {
      addProperty("deliveryDate", deliveryDate.toString())
    }
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.PP) ?: return null
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.PP) ?: return null
    val rows = response.getAsJsonArray("visits") ?: run {
      Log.w(TAG, "GoRulesScheduleAdapter: PP response had no 'visits' array")
      return null
    }
    return rows.mapNotNull { element ->
      mapRow(element.asJsonObject, context, VisitCodeType.PP, cached.ruleVersionId, deliveryDate, AnchorType.DELIVERY_DATE)
    }
  }

  /**
   * The NN series. Confirmed: the pack returns 0–2 individual visit objects (`nn1`/`nn2`,
   * either can be `null`) — never a `visits` array. `deliveryFormFiledDate` (not
   * `deliveryFormFilledDay`) is a date, not a day-count; the pack computes the day gap itself.
   */
  suspend fun nnSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val deliveryDate = context.deliveryDate ?: return null
    val filledOn = context.deliveryFormFilledOn ?: return null
    val answers = JsonObject().apply {
      addProperty("deliveryDate", deliveryDate.toString())
      addProperty("deliveryFormFiledDate", filledOn.toString())
    }
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.NN) ?: return null
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.NN) ?: return null
    val mapped = mutableListOf<VisitScheduleEntity>()
    response.objectOrNull("nn1")?.let { row ->
      mapRow(row, context, VisitCodeType.NN, cached.ruleVersionId, deliveryDate, AnchorType.DELIVERY_DATE)?.let(mapped::add)
    }
    response.objectOrNull("nn2")?.let { row ->
      mapRow(row, context, VisitCodeType.NN, cached.ruleVersionId, deliveryDate, AnchorType.DELIVERY_DATE)?.let(mapped::add)
    }
    // An empty list is scenario DAY_29_PLUS's real answer (neonatalPhaseApplies=false), not a
    // parse failure.
    return mapped
  }

  /**
   * The INC series (0–12 months), at child registration. `registrationDaysFromDob` dropped —
   * confirmed there is no third request field; the pack derives the day count itself from
   * [dob]/[registrationDate].
   */
  suspend fun incSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val dob = context.dob ?: return null
    val answers = JsonObject().apply {
      addProperty("dob", dob.toString())
      addProperty("registrationDate", context.registrationDate.toString())
    }
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.INC) ?: return null
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.INC) ?: return null
    val rows = response.getAsJsonArray("visits") ?: run {
      Log.w(TAG, "GoRulesScheduleAdapter: INC response had no 'visits' array")
      return null
    }
    val anchorType = if (context.registrationDate.isAfter(dob)) AnchorType.DOB else AnchorType.REGISTRATION
    val anchorDate = if (anchorType == AnchorType.DOB) dob else context.registrationDate
    return rows.mapNotNull { element ->
      mapRow(element.asJsonObject, context, VisitCodeType.INC, cached.ruleVersionId, anchorDate, anchorType)
    }
  }

  /**
   * The CCV series at the INC-to-CCV transition. Confirmed request shape is completely different
   * from the original: [dob] plus four fields describing the child's INC-phase HR history —
   * `mostRecentIncVisitHrType` is a single enum (`SAM_DANGER`/`OTHER`/`NONE`), not two booleans;
   * [HrFinding.SAM] and [HrFinding.DANGER_SIGN] both collapse to `SAM_DANGER` per the pack's own
   * grouping (same 30-day response as each other, same as the original design's intent).
   * `last3IncVisitsNormal` is derived from the most recent (up to) three [incOutcomes] all having
   * a null [IncVisitOutcome.hrFinding]. `hrDetectedAtLastCcvVisit` is always `false` here — see
   * this class's doc for the known gap.
   */
  suspend fun ccvSeries(
    context: ScheduleContext,
    transitionDate: LocalDate,
    incOutcomes: List<IncVisitOutcome>,
  ): List<VisitScheduleEntity>? {
    val dob = context.dob ?: return null
    val mostRecent = incOutcomes.maxByOrNull { it.completedOn }
    val lastThree = incOutcomes.sortedByDescending { it.completedOn }.take(3)
    val answers = JsonObject().apply {
      addProperty("dob", dob.toString())
      addProperty("hrEverDetectedIn0to12m", incOutcomes.any { it.hrFinding != null })
      addProperty(
        "mostRecentIncVisitHrType",
        when (mostRecent?.hrFinding) {
          HrFinding.SAM, HrFinding.DANGER_SIGN -> "SAM_DANGER"
          HrFinding.OTHER -> "OTHER"
          null -> "NONE"
        },
      )
      addProperty("last3IncVisitsNormal", lastThree.isNotEmpty() && lastThree.all { it.hrFinding == null })
      // Known gap — see this class's doc. Never true through this call path today.
      addProperty("hrDetectedAtLastCcvVisit", false)
    }
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.CCV) ?: return null
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.CCV) ?: return null
    val rows = response.getAsJsonArray("visits") ?: run {
      Log.w(TAG, "GoRulesScheduleAdapter: CCV response had no 'visits' array")
      return null
    }
    val mapped = rows.mapNotNull { element ->
      mapRow(element.asJsonObject, context, VisitCodeType.CCV, cached.ruleVersionId, transitionDate, AnchorType.CCV_TRANSITION)
    }.toMutableList()
    response.objectOrNull("extensionVisit")?.let { row ->
      mapRow(row, context, VisitCodeType.CCV_HR, cached.ruleVersionId, transitionDate, AnchorType.CCV_TRANSITION)?.let(mapped::add)
    }
    return mapped
  }

  /**
   * An on-demand HR follow-up for [VisitScheduleCoordinator.onHighRiskDetected] (ANC/INC only —
   * CCV's is embedded in [ccvSeries]'s own response; the pack itself throws if asked for a phase
   * other than `ANC`/`INC`/`CCV`, which is how it enforces "no HR visits in the neonatal phase").
   *
   * Confirmed request is `phase` (`"ANC"`/`"INC"`/`"CCV"`, not `triggeringVisitType`) plus
   * `hrDetectedThisVisit` (always `true` on this call path — it only runs because a high-risk
   * condition was just detected) and `actualCompletionDate`. There is no `sequenceNo` or
   * `triggeringVisitLocalUuid` request field, and no response array — [existingHrCount] (from the
   * caller, unchanged) is still what numbers this row locally.
   *
   * Returns [HrVisitOutcome] rather than a plain nullable (fixed 2026-08-13) — see that sealed
   * class's own doc for why "no cached rule yet" and "the pack decided against a visit" must be
   * distinguishable to the caller.
   */
  suspend fun hrVisit(
    context: ScheduleContext,
    triggeringVisit: VisitScheduleEntity,
    actualCompletionDate: LocalDate,
    existingHrCount: Int,
  ): HrVisitOutcome {
    val hrType = when (triggeringVisit.visitType) {
      VisitCodeType.ANC -> VisitCodeType.ANC_HR
      VisitCodeType.INC -> VisitCodeType.INC_HR
      // Not a family HR applies to at all — the coordinator already filters to ANC/INC before
      // calling this, so this branch is defensive, not reachable today. A structural "doesn't
      // apply" is a real "no," not a missing-rule situation.
      else -> return HrVisitOutcome.NoVisitNeeded
    }
    val phase = if (triggeringVisit.visitType == VisitCodeType.ANC) "ANC" else "INC"
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.HR)
      ?: return HrVisitOutcome.RuleUnavailable
    val answers = JsonObject().apply {
      addProperty("phase", phase)
      addProperty("hrDetectedThisVisit", true)
      addProperty("actualCompletionDate", actualCompletionDate.toString())
    }
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.HR)
      ?: return HrVisitOutcome.RuleUnavailable
    if (response.get("generateHrVisit")?.asBoolean != true) return HrVisitOutcome.NoVisitNeeded
    val row = response.objectOrNull("hrVisit") ?: return HrVisitOutcome.RuleUnavailable
    val mapped = mapRow(row, context, hrType, cached.ruleVersionId, actualCompletionDate, AnchorType.ACTUAL_VISIT)
      ?: return HrVisitOutcome.RuleUnavailable
    return HrVisitOutcome.Generated(mapped.copy(anchorVisitLocalUuid = triggeringVisit.localScheduleUuid))
  }

  // -----------------------------------------------------------------------------------------------
  // Shared plumbing
  // -----------------------------------------------------------------------------------------------

  /**
   * Maps one `{visitName, scheduledDate, windowOpen, windowClose}` row — the shape every pack
   * actually uses — into a [VisitScheduleEntity]. [visitType] is supplied by the caller: the row
   * itself carries no separate visitType/sequenceNo field, only [visitName] (e.g. `"ANC3"`,
   * `"PP1"`, `"ANC-HR"`), which becomes [VisitScheduleEntity.visitCode] verbatim, with the
   * sequence number parsed off its trailing digits (HR rows have none, so default to 1 — callers
   * needing a real HR sequence number use [existingHrCount] instead, not this).
   */
  private fun mapRow(
    row: JsonObject,
    context: ScheduleContext,
    visitType: VisitCodeType,
    ruleVersionId: String,
    anchorDate: LocalDate,
    anchorType: AnchorType,
  ): VisitScheduleEntity? {
    val visitCode = row.get("visitName")?.takeUnless { it.isJsonNull }?.asString ?: return null
    val scheduledDate = row.get("scheduledDate")?.takeUnless { it.isJsonNull }?.asString?.let(::parseDateOrNull)
      ?: return null
    val windowStart = row.get("windowOpen")?.takeUnless { it.isJsonNull }?.asString?.let(::parseDateOrNull)
      ?: scheduledDate
    val windowEnd = row.get("windowClose")?.takeUnless { it.isJsonNull }?.asString?.let(::parseDateOrNull)
      ?: scheduledDate
    val sequenceNo = Regex("(\\d+)$").find(visitCode)?.value?.toIntOrNull() ?: 1

    return VisitScheduleEntity(
      localScheduleUuid = UUID.randomUUID().toString(),
      localBeneficiaryId = context.localBeneficiaryId,
      visitCode = visitCode,
      visitType = visitType,
      sequenceNo = sequenceNo,
      scheduledDate = scheduledDate,
      windowStartDate = windowStart,
      windowEndDate = windowEnd,
      anchorType = anchorType,
      anchorDate = anchorDate,
      generatedByRuleVersion = ruleVersionId,
      // Not answerable by the schedule packs (see this class's doc re: RuleSetIds.ESCALATION not
      // being wired in anywhere yet) — always the hardcoded policy regardless of which path
      // generated the row.
      escalationPolicy = HardcodedRuleSource().escalationPolicy(visitType),
      createdAtEpochMillis = System.currentTimeMillis(),
    )
  }

  private suspend fun safeEvaluate(
    rulesJson: JsonObject,
    answers: JsonObject,
    ruleSetId: String,
  ): JsonObject? = try {
    ruleEvaluator.evaluate(rulesJson, answers)
  } catch (e: Exception) {
    Log.w(TAG, "GoRulesScheduleAdapter: evaluate($ruleSetId) threw ${e::class.simpleName} — ${e.message}")
    null
  }

  private fun parseDateOrNull(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

  /** Gson's [JsonObject.getAsJsonObject] throws a ClassCastException on an explicit JSON `null`
   * (as opposed to a genuinely absent key) — several of these packs' response fields are
   * documented as nullable and really do come back as literal `null`, not just "absent". */
  private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeUnless { it.isJsonNull }?.asJsonObject
}
