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
 */
@Singleton
class VisitScheduleCoordinator @Inject constructor(
  private val repository: VisitScheduleRepository,
  private val ancGenerator: AncScheduleGenerator,
  private val ppGenerator: PpScheduleGenerator,
  private val nnGenerator: NnScheduleGenerator,
  private val incGenerator: IncScheduleGenerator,
  private val ccvGenerator: CcvScheduleGenerator,
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

    val visits = ancGenerator.generateSeries(context)
    repository.saveGenerated(visits)
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
      val inc = incGenerator.generateSeries(context)
      repository.saveGenerated(inc)
      generated += inc.size
    }

    return generated
  }

  /**
   * Delivery form submitted — three effects, in this order:
   *
   * 1. **Every open ANC visit lapses** (FR-S-3.7), regardless of window position. Done first so a
   *    schedule can never briefly show both an open ANC visit and a PP visit.
   * 2. The PP series is generated, anchored to the delivery date.
   * 3. The NN series is generated, if child registration has not already produced it.
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
        val pp = ppGenerator.generateSeries(context)
        repository.saveGenerated(pp)
        pp.size
      }

    return DeliveryScheduleResult(
      lapsedAncVisits = lapsed,
      ppVisitsGenerated = ppGenerated,
      nnVisitsGenerated = generateNnIfAbsent(context),
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

    val visit = when (hrType) {
      VisitCodeType.ANC_HR ->
        ancGenerator.generateHrVisit(context, triggeringVisit, actualCompletionDate, existing)
      else ->
        incGenerator.generateHrVisit(context, triggeringVisit, actualCompletionDate, existing)
    } ?: return null

    repository.saveGenerated(listOf(visit))
    return visit
  }

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

    val visits = ccvGenerator.generateSeries(
      context = context,
      transitionDate = transitionDate,
      riskState = ccvGenerator.determineRiskState(incOutcomes),
    )
    repository.saveGenerated(visits)
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
   */
  suspend fun onLmpOrEddApproved(context: ScheduleContext): RegenerationResult {
    requireNotNull(context.edd) { "Regenerating an ANC schedule requires the corrected EDD" }

    val superseded = repository.supersedeOpenVisits(context.localBeneficiaryId)
    val visits = ancGenerator.generateSeries(context)
    repository.saveGenerated(visits)

    return RegenerationResult(supersededVisits = superseded, generatedVisits = visits.size)
  }

  private suspend fun generateNnIfAbsent(context: ScheduleContext): Int {
    if (!context.hasDeliveryDetails) return 0
    if (repository.hasScheduleOfType(context.localBeneficiaryId, VisitCodeType.NN)) return 0

    val visits = nnGenerator.generateSeries(context)
    repository.saveGenerated(visits)
    return visits.size
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
