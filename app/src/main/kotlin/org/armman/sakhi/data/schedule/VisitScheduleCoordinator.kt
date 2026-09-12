package org.armman.sakhi.data.schedule

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for creating visit schedules — the seam between the app's form flows and
 * the scheduling engine.
 *
 * Form flows call the `on…` method that matches the event they just recorded; they do not know
 * which visit families that event produces, or in what order. Adding a family later is a change
 * here, not in every ViewModel.
 *
 * ### Everything here works offline
 * Generation writes to Room and nothing else. A Sakhi enrolling a woman in a village with no signal
 * sees the full schedule immediately (SRS FR-S-2.2) — the upload is a separate, later concern
 * handled by [VisitScheduleSyncExecutor].
 *
 * ### Every method is idempotent
 * A double-tapped Submit, a retried coroutine, or a flow re-entered after process death must not
 * produce a second series. Guarded per family via [VisitScheduleRepository.hasScheduleOfType],
 * which counts retired rows too — a lapsed ANC series still means ANC has been generated.
 *
 * ### GoRules (CR-032), behind one flag
 * Each `generate…Series`/`generate…Visit` private helper below tries [goRulesAdapter] first when
 * [GoRulesScheduleFeatureFlag.ENABLED] is on, falling back to the existing Kotlin generator
 * (still [HardcodedRuleSource]-driven) when the adapter returns null — "no cached rule set yet",
 * "the pack rejected the request", or the flag being off all take the same fallback path. Nothing
 * below changes when the flag is off; [goRulesAdapter] defaults to null so every existing test
 * constructor call keeps compiling unchanged.
 *
 * [onHighRiskDetected] used to be an exception here: a bare `null` from
 * [GoRulesScheduleAdapter.hrVisit] couldn't tell "no cached rule yet" apart from "the pack decided
 * against a visit," so it never fell back — a Sakhi flagging a high-risk finding before her phone
 * had synced the HR rule pack would silently get no follow-up at all. Fixed 2026-08-13:
 * [GoRulesScheduleAdapter.hrVisit] now returns [HrVisitOutcome], and [generateHrVisit] falls back
 * to the Kotlin HR generators on [HrVisitOutcome.RuleUnavailable] (or when no adapter is wired),
 * exactly like every other family below — while still trusting [HrVisitOutcome.NoVisitNeeded]
 * completely, since that is a real "no," not a missing-rule situation.
 *
 * [onLmpOrEddApproved] is intentionally left out of this wiring for now — it wasn't part of the
 * agreed CR-032 coordinator scope. It keeps calling [AncScheduleGenerator] directly even when the
 * flag is on, so a Supervisor-approved LMP/EDD correction always regenerates via Hardcoded today.
 * Worth revisiting for symmetry once the Step 1 ngrok trial lands.
 */
@Singleton
class VisitScheduleCoordinator @Inject constructor(
  private val repository: VisitScheduleRepository,
  private val ancGenerator: AncScheduleGenerator,
  private val ppGenerator: PpScheduleGenerator,
  private val nnGenerator: NnScheduleGenerator,
  private val incGenerator: IncScheduleGenerator,
  private val ccvGenerator: CcvScheduleGenerator,
  private val goRulesAdapter: GoRulesScheduleAdapter? = null,
) {

  /**
   * Mother enrolled — generates the ANC series (FR-S-2.2).
   *
   * Called from the enrolment flow *after* the draft is saved but *before* it is handed to the sync
   * queue, so a schedule always exists by the time the beneficiary can reach the server.
   *
   * Returns the number of visits generated; 0 when a schedule already existed.
   */
  suspend fun onMotherEnrolled(context: ScheduleContext): Int {
    if (repository.hasScheduleOfType(context.localBeneficiaryId, VisitCodeType.ANC)) return 0

    val visits = generateAncSeries(context)
    saveGeneratedAndBackfill(context.localBeneficiaryId, visits)
    return visits.size
  }

  /**
   * Child registered — generates the INC series, and the NN series when delivery details are known
   * (FR-S-2.2A).
   *
   * NN can arrive from either this trigger or [onDeliveryRecorded], depending on whether the mother
   * was enrolled in ANC. Whichever fires first wins; the per-family guard makes the second a no-op.
   */
  suspend fun onChildRegistered(context: ScheduleContext): Int {
    var generated = 0

    if (context.hasDeliveryDetails) {
      generated += generateNnIfAbsent(context)
    }

    if (!repository.hasScheduleOfType(context.localBeneficiaryId, VisitCodeType.INC)) {
      val inc = generateIncSeries(context)
      saveGeneratedAndBackfill(context.localBeneficiaryId, inc)
      generated += inc.size
    }

    return generated
  }

  /**
   * Delivery form submitted — two effects, in this order:
   *
   * 1. **Every open ANC visit lapses** (FR-S-3.7), regardless of window position. Done first so a
   *    schedule can never briefly show both an open ANC visit and a PP visit.
   * 2. The PP series is generated, anchored to the delivery date.
   *
   * NN is deliberately NOT generated here (CR-042 defect fix): NN belongs to the CHILD's own
   * record, not the mother's, and the SRS requires child registration before NN1/NN2 exist at all
   * ("First register child then NN1, NN2..." — INC visit logic doc). Generating NN against this
   * mother's [ScheduleContext.localBeneficiaryId] let NN1/NN2 complete with no child ever
   * registered, which is the bug this fix closes. NN is now generated only from
   * [onChildRegistered], called by `DeliveryChildRegistrationSubmissionCoordinator` once the child
   * is actually registered, anchored to the child's own local beneficiary id.
   *
   * Lapsing runs even when PP already exists, because it is a state change rather than a
   * generation — a delivery recorded twice must still leave no open ANC visits.
   */
  suspend fun onDeliveryRecorded(context: ScheduleContext): DeliveryScheduleResult {
    requireNotNull(context.deliveryDate) { "Recording a delivery requires a delivery date" }

    val lapsed = repository.lapseOpenAncVisits(context.localBeneficiaryId)

    val ppGenerated =
      if (repository.hasScheduleOfType(context.localBeneficiaryId, VisitCodeType.PP)) {
        0
      } else {
        val pp = generatePpSeries(context)
        saveGeneratedAndBackfill(context.localBeneficiaryId, pp)
        pp.size
      }

    return DeliveryScheduleResult(
      lapsedAncVisits = lapsed,
      ppVisitsGenerated = ppGenerated,
      // CR-042 fix: NN no longer generated here — see this function's own doc comment above.
      nnVisitsGenerated = 0,
    )
  }

  /**
   * A visit was completed and a high-risk condition was found — generates the HR follow-up,
   * anchored to [actualCompletionDate] (FR-S-3.4).
   *
   * Returns null when the family does not produce HR visits at all: SR-NN-01 sends a neonatal
   * critical condition to the referral flow instead.
   */
  suspend fun onHighRiskDetected(
    context: ScheduleContext,
    triggeringVisit: VisitScheduleEntity,
    actualCompletionDate: LocalDate,
  ): VisitScheduleEntity? {
    val hrType = when (triggeringVisit.visitType) {
      VisitCodeType.ANC -> VisitCodeType.ANC_HR
      VisitCodeType.INC -> VisitCodeType.INC_HR
      // Everything else — NN (SR-NN-01), PP, CCV, and the post-EDD visit — produces no HR
      // follow-up today. Post-EDD is the debatable one: it is an ANC visit by name, but the SRS
      // never says whether it can trigger, and the delivery form is overdue by then anyway.
      // Excluded deliberately rather than by omission; part of open question Q2.
      else -> return null
    }

    val existing = repository.getForBeneficiary(context.localBeneficiaryId)
      .count { it.visitType == hrType }

    val visit = generateHrVisit(context, triggeringVisit, actualCompletionDate, existing, hrType) ?: return null

    saveGeneratedAndBackfill(context.localBeneficiaryId, listOf(visit))
    return visit
  }

  // 2026-09-11: onEnrollmentHighRiskDetected() (enrolment-time baseline HR visit generation,
  // added 2026-09-02) removed per explicit product decision — see
  // MotherEnrolmentScheduleTrigger's class doc and delivery-log.md 2026-09-11 for the full
  // reasoning. A baseline finding still sets the Beneficiaries-list High Risk badge unchanged;
  // it no longer pre-generates an ANC-HR visit before any ANC visit has actually been attended.

  /**
   * The infant phase ended — generates the CCV journey (SRS CCV risk-state table).
   *
   * Deliberately a separate trigger rather than part of [onChildRegistered]: the SRS rejects
   * pre-generating CCV at registration because the risk state is unknown a year ahead. The state is
   * evaluated once, here, from the complete set of infant-phase outcomes.
   */
  suspend fun onIncPhaseCompleted(
    context: ScheduleContext,
    incOutcomes: List<IncVisitOutcome>,
  ): Int {
    if (repository.hasScheduleOfType(context.localBeneficiaryId, VisitCodeType.CCV)) return 0

    val dob = requireNotNull(context.dob) { "The CCV transition requires a date of birth" }
    val incVisits = repository.getForBeneficiary(context.localBeneficiaryId)
    val transitionDate = incGenerator.ccvTransitionDate(dob, incVisits)

    val visits = generateCcvSeries(context, transitionDate, incOutcomes)
    saveGeneratedAndBackfill(context.localBeneficiaryId, visits)
    return visits.size
  }

  /**
   * A Supervisor-approved LMP/EDD change — the **only** trigger that regenerates a schedule
   * (SRS §3A.2.3).
   *
   * Retires the open cohort and generates a fresh ANC series from the corrected dates. Completed
   * visits are untouched and nothing is deleted, so the record of what actually happened survives.
   *
   * Not idempotent by design: each approved change is a distinct clinical event, and a second
   * approval legitimately supersedes the schedule the first one produced. Callers must gate on the
   * approval, not on this method.
   *
   * Not wired to [goRulesAdapter] — see this class's doc. Always the Kotlin generator.
   */
  suspend fun onLmpOrEddApproved(context: ScheduleContext): RegenerationResult {
    requireNotNull(context.edd) { "Regenerating an ANC schedule requires the corrected EDD" }

    val superseded = repository.supersedeOpenVisits(context.localBeneficiaryId)
    val visits = ancGenerator.generateSeries(context)
    saveGeneratedAndBackfill(context.localBeneficiaryId, visits)

    return RegenerationResult(supersededVisits = superseded, generatedVisits = visits.size)
  }

  private suspend fun generateNnIfAbsent(context: ScheduleContext): Int {
    if (!context.hasDeliveryDetails) return 0
    if (repository.hasScheduleOfType(context.localBeneficiaryId, VisitCodeType.NN)) return 0

    val visits = generateNnSeries(context)
    saveGeneratedAndBackfill(context.localBeneficiaryId, visits)
    return visits.size
  }

  /**
   * Saves a freshly generated series, then immediately re-runs the same backfill
   * [org.armman.sakhi.data.forms.DynamicFormSyncExecutor] does once for a beneficiary's ANC-era
   * rows right after her Mother Registration form syncs: copy her already-known
   * [VisitScheduleEntity.serverBeneficiaryId] onto every row of hers.
   *
   * Without this, a series generated by any trigger OTHER than enrolment (PP/NN at delivery, INC/NN
   * at child registration, HR on a high-risk finding, CCV at the infant-phase transition, a
   * Supervisor-approved ANC regeneration) is created with `serverBeneficiaryId = null` — see
   * [ScheduleContext]'s own doc: the generators are pure functions with no repository access, so
   * they have no way to know it — and stays that way forever. [VisitScheduleRepository.getUnsynced]
   * requires a non-null `serverBeneficiaryId` (a schedule cannot upload before the beneficiary it
   * belongs to exists server-side), so a row missing it is invisible to every future sync attempt —
   * a manual Data Upload tap, an immediate online retry, anything — not just delayed by one. This
   * was reported live: a Sakhi who submitted the Delivery form online, then immediately opened the
   * freshly-generated PP1 visit, kept hitting "hasn't finished syncing" no matter how many times she
   * tapped Data Upload, because the PP1 row was never eligible for upload in the first place.
   *
   * Correct — not just harmless — to call this even when the beneficiary's own id isn't known yet
   * (enrolment itself, or a beneficiary who has never synced at all): [backfillServerBeneficiaryId]
   * finds nothing and no-ops, exactly the outcome before this fix existed.
   */
  private suspend fun saveGeneratedAndBackfill(localBeneficiaryId: String, visits: List<VisitScheduleEntity>) {
    repository.saveGenerated(visits)
    backfillServerBeneficiaryId(localBeneficiaryId)
  }

  /** See [saveGeneratedAndBackfill]'s doc. Looks at every row this beneficiary already has —
   * including the ones [saveGeneratedAndBackfill] just inserted, which is fine, since they carry no
   * `serverBeneficiaryId` yet and [firstNotNullOfOrNull] simply skips them — for any already-known
   * id, then stamps it onto ALL of her rows via the same
   * [VisitScheduleRepository.attachServerBeneficiaryId] update [DynamicFormSyncExecutor] uses. */
  private suspend fun backfillServerBeneficiaryId(localBeneficiaryId: String) {
    val serverBeneficiaryId = repository.getForBeneficiary(localBeneficiaryId)
      .firstNotNullOfOrNull { it.serverBeneficiaryId } ?: return
    repository.attachServerBeneficiaryId(localBeneficiaryId, serverBeneficiaryId)
  }

  // -----------------------------------------------------------------------------------------------
  // GoRules-first, Hardcoded-fallback helpers (CR-032). One per family, matching
  // GoRulesScheduleAdapter's per-pack methods.
  // -----------------------------------------------------------------------------------------------

  private suspend fun generateAncSeries(context: ScheduleContext): List<VisitScheduleEntity> {
    if (GoRulesScheduleFeatureFlag.ENABLED) {
      goRulesAdapter?.ancSeries(context)?.let { return it }
    }
    return ancGenerator.generateSeries(context)
  }

  private suspend fun generatePpSeries(context: ScheduleContext): List<VisitScheduleEntity> {
    if (GoRulesScheduleFeatureFlag.ENABLED) {
      goRulesAdapter?.ppSeries(context)?.let { return it }
    }
    return ppGenerator.generateSeries(context)
  }

  private suspend fun generateNnSeries(context: ScheduleContext): List<VisitScheduleEntity> {
    if (GoRulesScheduleFeatureFlag.ENABLED) {
      goRulesAdapter?.nnSeries(context)?.let { return it }
    }
    return nnGenerator.generateSeries(context)
  }

  private suspend fun generateIncSeries(context: ScheduleContext): List<VisitScheduleEntity> {
    if (GoRulesScheduleFeatureFlag.ENABLED) {
      goRulesAdapter?.incSeries(context)?.let { return it }
    }
    return incGenerator.generateSeries(context)
  }

  private suspend fun generateCcvSeries(
    context: ScheduleContext,
    transitionDate: LocalDate,
    incOutcomes: List<IncVisitOutcome>,
  ): List<VisitScheduleEntity> {
    if (GoRulesScheduleFeatureFlag.ENABLED) {
      goRulesAdapter?.ccvSeries(context, transitionDate, incOutcomes)?.let { return it }
    }
    return ccvGenerator.generateSeries(
      context = context,
      transitionDate = transitionDate,
      riskState = ccvGenerator.determineRiskState(incOutcomes),
    )
  }

  /**
   * Fixed 2026-08-13: this now falls back to the Kotlin HR generators on
   * [HrVisitOutcome.RuleUnavailable] — or when no adapter is wired at all — exactly like every
   * other `generate…` helper above. [HrVisitOutcome.NoVisitNeeded] is still trusted completely, no
   * fallback: that's a real "no" from the pack, not a missing-rule situation. See
   * [HrVisitOutcome]'s own doc for why the distinction matters.
   */
  private suspend fun generateHrVisit(
    context: ScheduleContext,
    triggeringVisit: VisitScheduleEntity,
    actualCompletionDate: LocalDate,
    existingHrCount: Int,
    hrType: VisitCodeType,
  ): VisitScheduleEntity? {
    if (GoRulesScheduleFeatureFlag.ENABLED) {
      when (val outcome = goRulesAdapter?.hrVisit(context, triggeringVisit, actualCompletionDate, existingHrCount)) {
        is HrVisitOutcome.Generated -> return outcome.visit
        HrVisitOutcome.NoVisitNeeded -> return null
        HrVisitOutcome.RuleUnavailable, null -> Unit // fall through to the Kotlin generator below
      }
    }
    return when (hrType) {
      VisitCodeType.ANC_HR ->
        ancGenerator.generateHrVisit(context, triggeringVisit, actualCompletionDate, existingHrCount)
      else ->
        incGenerator.generateHrVisit(context, triggeringVisit, actualCompletionDate, existingHrCount)
    }
  }

  private val ScheduleContext.hasDeliveryDetails: Boolean
    get() = deliveryDate != null && deliveryFormFilledOn != null
}

/** What a delivery submission did to the schedule. Surfaced so the UI can report it and tests can
 * assert all three effects independently. */
data class DeliveryScheduleResult(
  val lapsedAncVisits: Int,
  val ppVisitsGenerated: Int,
  val nnVisitsGenerated: Int,
)

/** What an approved LMP/EDD change did. [supersededVisits] is never a deletion count. */
data class RegenerationResult(
  val supersededVisits: Int,
  val generatedVisits: Int,
)
