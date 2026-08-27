package org.armman.sakhi.data.schedule

import java.time.LocalDate

/**
 * Picks which (if any) generated NN visit is due *in the delivery session itself*, as opposed to
 * later on the visit tracker (CR-042, "Delivery Event Session").
 *
 * [NnScheduleGenerator.generateSeries] returns NN1, NN2, both, or neither depending on SR-NN-01's
 * three timing scenarios (see that class's doc) — but a caller driving a live session needs to know
 * which *one* of those rows (if any) should open right now, in front of the Sakhi who is still
 * standing with the beneficiary.
 *
 * ### The rule
 * Whichever generated row is scheduled for the delivery form's own fill date is the one due now:
 * - **Scenario A** (form filled Day 0–14): NN1's `scheduledDate` is clamped to the fill date, so it
 *   matches and opens now. NN2 is scheduled ~Day 15 and is correctly excluded — it belongs on the
 *   tracker, not this session.
 * - **Scenario B/C** (form filled Day 15–28): NN1 was never generated. NN2's `scheduledDate` is set
 *   to the fill date (see [NnScheduleGenerator]'s `nn2ScheduledDate` branch), so it matches instead.
 * - **After Day 28**: [nnVisits] is empty; this returns null and the session has no NN step.
 *
 * This intentionally does not just take the first element of [nnVisits] — the generator's return
 * order is an implementation detail, not a contract this function should lean on.
 */
fun sameSessionNnVisit(
  nnVisits: List<VisitScheduleEntity>,
  deliveryFormFilledOn: LocalDate,
): VisitScheduleEntity? = nnVisits.singleOrNull { it.scheduledDate == deliveryFormFilledOn }
