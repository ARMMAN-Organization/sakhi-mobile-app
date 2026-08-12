package org.armman.sakhi.data.schedule

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.armman.sakhi.data.rules.RuleEvaluator
import org.armman.sakhi.data.rules.RuleSetIds
import org.armman.sakhi.data.rules.RuleSetRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"

/**
 * Calls a GoRules pack once per family and maps its response straight into
 * [VisitScheduleEntity] rows — the local-execution counterpart of what rules-service's
 * `evaluate-schedule`-style endpoints do server-side.
 *
 * ### Why this exists instead of a `GoRulesRuleSource : ScheduleRuleSource`
 * The original CR-032 plan assumed GoRules could answer [ScheduleRuleSource]'s 18 granular
 * questions (interval days, escalation policy, cutoff days, ...) one at a time. Confirmed with the
 * backend team (2026-08-12) that this is not how the packs work: each of the 6 rule sets returns
 * one full response — a `visits` array plus a handful of sibling fields — computed all at once from
 * whatever inputs that family needs. There is no way to ask a pack an isolated small question
 * without triggering a full schedule computation. So instead of implementing the fine-grained
 * interface, this class sits **above** the generators: [VisitScheduleCoordinator] tries it first
 * (behind [GoRulesScheduleFeatureFlag]) and only falls back to the existing Kotlin generators
 * ([AncScheduleGenerator] etc., still driven by [HardcodedRuleSource]) when it returns null.
 *
 * [ScheduleRuleSource]/[HardcodedRuleSource] are otherwise unchanged and remain the M2 path in
 * full — this class never touches them.
 *
 * ### What is NOT covered here
 * Escalation policy has no backend rule at all yet (confirmed dormant `RuleCategory.ESCALATION`
 * enum value only, no seeded rule set, no evaluator) — [ScheduleRuleSource.escalationPolicy]
 * keeps reading from [HardcodedRuleSource] regardless of which path generated the visit rows.
 * The ANC post-EDD check ([AncScheduleGenerator.generatePostEddVisit]) isn't wired into
 * [VisitScheduleCoordinator] today either way, so it has no GoRules counterpart here — scoped
 * out to match, not a regression.
 *
 * ### ⚠ Field names are best-effort, unverified against the live service
 * The exact request field names below follow the shapes rules-service's own API reference
 * documented before the per-mode design was corrected to "one full-response call per family."
 * The backend team confirmed the *response* shape (`visits[]` + sibling fields per pack) but not
 * every *request* field name for the one-call-per-family model. **Confirm every field name in the
 * Step 1 ngrok trial** (see `GoRulesScheduleFeatureFlag`'s doc) before flipping the flag on ANY
 * environment that isn't a local dev sandbox — a silently-wrong field name fails closed (the
 * pack likely 400s or returns empty), which [VisitScheduleCoordinator] treats the same as "no
 * cached rule" and falls back to Hardcoded, so this is safe to test but must not be trusted
 * un-verified.
 */
@Singleton
class GoRulesScheduleAdapter @Inject constructor(
  private val ruleSetRepository: RuleSetRepository,
  private val ruleEvaluator: RuleEvaluator,
) {

  /** ANC series at enrolment — the [VisitScheduleCoordinator.onMotherEnrolled] path only. */
  suspend fun ancSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val edd = context.edd ?: return null
    val answers = JsonObject().apply {
      addProperty("registrationDate", context.registrationDate.toString())
      addProperty("edd", edd.toString())
    }
    return evaluateSeries(RuleSetIds.ANC, answers, context, VisitCodeType.ANC, AnchorType.REGISTRATION)
  }

  /** The five PP visits at delivery form submission. */
  suspend fun ppSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val deliveryDate = context.deliveryDate ?: return null
    val answers = JsonObject().apply {
      addProperty("deliveryDate", deliveryDate.toString())
    }
    return evaluateSeries(RuleSetIds.PP, answers, context, VisitCodeType.PP, AnchorType.DELIVERY_DATE)
  }

  /**
   * The NN series (0, 1 or 2 rows depending on scenario A/B/C). `deliveryFormFilledDay` is the
   * gap in days between delivery and the form being filled — matches the shape the backend team's
   * own NN example used (`deliveryFormFilledDay`, not a second date), computed here rather than
   * passed by the caller so [ScheduleContext]'s two dates stay the single source of truth.
   */
  suspend fun nnSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val deliveryDate = context.deliveryDate ?: return null
    val filledOn = context.deliveryFormFilledOn ?: return null
    val answers = JsonObject().apply {
      addProperty("deliveryDate", deliveryDate.toString())
      addProperty("deliveryFormFilledDay", ChronoUnit.DAYS.between(deliveryDate, filledOn))
    }
    return evaluateSeries(RuleSetIds.NN, answers, context, VisitCodeType.NN, AnchorType.DELIVERY_DATE)
  }

  /** The INC series (0–12 months), at child registration. */
  suspend fun incSeries(context: ScheduleContext): List<VisitScheduleEntity>? {
    val dob = context.dob ?: return null
    val answers = JsonObject().apply {
      addProperty("dob", dob.toString())
      addProperty("registrationDate", context.registrationDate.toString())
      addProperty("registrationDaysFromDob", ChronoUnit.DAYS.between(dob, context.registrationDate))
    }
    val anchorType = if (context.registrationDate.isAfter(dob)) AnchorType.DOB else AnchorType.REGISTRATION
    return evaluateSeries(RuleSetIds.INC, answers, context, VisitCodeType.INC, anchorType)
  }

  /**
   * The CCV series at the INC-to-CCV transition. [incOutcomes] is reduced to the raw booleans the
   * pack asks for rather than pre-computed into [CcvRiskState] locally — the pack owns that
   * decision now (its response includes its own `riskState`).
   *
   * `last3AllAtRisk`/`last3AllNormalFullyImmunised` are passed as `false` — immunisation status
   * is not tracked anywhere in [IncVisitOutcome] today, so this is a known gap, not an oversight.
   * Flag to ARMMAN/backend if the CCV cadence depends on this in practice.
   */
  suspend fun ccvSeries(
    context: ScheduleContext,
    transitionDate: LocalDate,
    incOutcomes: List<IncVisitOutcome>,
  ): List<VisitScheduleEntity>? {
    val mostRecent = incOutcomes.maxByOrNull { it.completedOn }
    val answers = JsonObject().apply {
      addProperty("hadAnyHrInLast12m", incOutcomes.any { it.hrFinding != null })
      addProperty(
        "mostRecentHasSamOrDangerSign",
        mostRecent?.hrFinding == HrFinding.SAM || mostRecent?.hrFinding == HrFinding.DANGER_SIGN,
      )
      addProperty("mostRecentHasOtherHr", mostRecent?.hrFinding == HrFinding.OTHER)
      // Not tracked in IncVisitOutcome yet — see this method's doc.
      addProperty("last3AllAtRisk", false)
      addProperty("last3AllNormalFullyImmunised", false)
    }
    return evaluateSeries(RuleSetIds.CCV, answers, context, VisitCodeType.CCV, AnchorType.CCV_TRANSITION)
  }

  /**
   * An on-demand HR follow-up for [VisitScheduleCoordinator.onHighRiskDetected] (ANC/INC only —
   * CCV's opening HR visit is embedded in [ccvSeries]'s own response, and NN never produces one).
   *
   * Returns null both for "no cached rule" and for "the pack itself decided not to generate one"
   * (its `generateHrVisit` field is false) — [VisitScheduleCoordinator] cannot tell those apart and
   * must not need to: either way there is no row to add.
   */
  suspend fun hrVisit(
    context: ScheduleContext,
    triggeringVisit: VisitScheduleEntity,
    actualCompletionDate: LocalDate,
    existingHrCount: Int,
  ): VisitScheduleEntity? {
    val cached = ruleSetRepository.getPublishedRuleSet(RuleSetIds.HR) ?: return null
    val answers = JsonObject().apply {
      addProperty("triggeringVisitType", triggeringVisit.visitType.name)
      addProperty("actualCompletionDate", actualCompletionDate.toString())
      addProperty("sequenceNo", existingHrCount + 1)
      addProperty("triggeringVisitLocalUuid", triggeringVisit.localScheduleUuid)
    }
    val response = safeEvaluate(cached.rulesJson, answers, RuleSetIds.HR) ?: return null

    if (response.get("generateHrVisit")?.asBoolean != true) return null

    val hrType = when (triggeringVisit.visitType) {
      VisitCodeType.ANC -> VisitCodeType.ANC_HR
      VisitCodeType.INC -> VisitCodeType.INC_HR
      else -> return null
    }
    val rows = response.getAsJsonArray("visits") ?: return null
    val row = rows.firstOrNull()?.asJsonObject ?: return null
    return mapRow(row, context, hrType, cached.ruleVersionId, actualCompletionDate, AnchorType.ACTUAL_VISIT)
      ?.copy(anchorVisitLocalUuid = triggeringVisit.localScheduleUuid)
  }

  // -----------------------------------------------------------------------------------------------
  // Shared plumbing
  // -----------------------------------------------------------------------------------------------

  private suspend fun evaluateSeries(
    ruleSetId: String,
    answers: JsonObject,
    context: ScheduleContext,
    defaultVisitType: VisitCodeType,
    anchorType: AnchorType,
  ): List<VisitScheduleEntity>? {
    val cached = ruleSetRepository.getPublishedRuleSet(ruleSetId) ?: return null
    val response = safeEvaluate(cached.rulesJson, answers, ruleSetId) ?: return null
    val rows = response.getAsJsonArray("visits") ?: run {
      Log.w(TAG, "GoRulesScheduleAdapter: $ruleSetId response had no 'visits' array")
      return null
    }
    val anchorDate = when (anchorType) {
      AnchorType.DOB -> context.dob
      AnchorType.DELIVERY_DATE -> context.deliveryDate
      else -> context.registrationDate
    } ?: context.registrationDate

    val mapped = rows.mapNotNull { element ->
      mapRow(element.asJsonObject, context, defaultVisitType, cached.ruleVersionId, anchorDate, anchorType)
    }
    // An empty-but-present visits array is a real answer (e.g. NN scenario past Day 28), not a
    // parse failure — only a genuinely missing/malformed array falls back to Hardcoded.
    return mapped
  }

  private fun mapRow(
    row: JsonObject,
    context: ScheduleContext,
    defaultVisitType: VisitCodeType,
    ruleVersionId: String,
    anchorDate: LocalDate,
    anchorType: AnchorType,
  ): VisitScheduleEntity? {
    val scheduledDate = row.get("scheduledDate")?.asString?.let(::parseDateOrNull) ?: return null
    val windowStart = row.get("windowStartDate")?.asString?.let(::parseDateOrNull) ?: scheduledDate
    val windowEnd = row.get("windowEndDate")?.asString?.let(::parseDateOrNull) ?: scheduledDate
    val visitType = row.get("visitType")?.asString?.let { name ->
      runCatching { VisitCodeType.valueOf(name) }.getOrNull()
    } ?: defaultVisitType
    val sequenceNo = row.get("sequenceNo")?.asInt ?: 1
    val visitCode = row.get("visitCode")?.asString ?: "$visitType$sequenceNo"

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
      anchorVisitLocalUuid = row.get("anchorVisitLocalUuid")?.takeUnless { it.isJsonNull }?.asString,
      generatedByRuleVersion = ruleVersionId,
      // Not answerable by the schedule packs (see this class's doc) — always the hardcoded
      // policy regardless of which path generated the row.
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

  private fun JsonArray.firstOrNull() = if (size() > 0) get(0) else null
}
