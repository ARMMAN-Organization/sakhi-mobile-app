package org.armman.sakhi.data.beneficiaryprofile

import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Matches the format already used on the enrolment summary and profile cards.
 *
 * Pinned to [Locale.ENGLISH] rather than the device default: the pattern itself is English-ordered
 * ("4 Aug 2026"), so a Marathi default locale would render Marathi month names inside an English
 * layout. Localising the visit date properly means localising the pattern too, which is a design
 * decision rather than a formatting one — raised for CR-024.
 */
private val DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Turns generated visit schedules into the profile screen's "See Visits" rows (CR-022f).
 *
 * Kept as a pure mapper rather than logic inside the repository so the display rules — ordering,
 * which visits are hidden, when Start Visit becomes tappable — are unit-testable without Room.
 *
 * ### Next visit at the top, history beneath
 * Upcoming visits are listed **soonest first**, then completed ones newest first.
 *
 * Not reverse-chronological. The design's "Visit 4, Visit 3, Visit 2…" looks like newest-first, but
 * Visit 4 there is the *open* one and the rest are completed history — so the rule is "what the
 * Sakhi does next, then what she has already done". Sorting the whole list newest-first instead
 * buries her next visit under every future one: on a freshly generated ten-visit ANC series she
 * would see ANC10, eight months away, at the top and today's ANC1 at the very bottom.
 */
fun List<VisitScheduleEntity>.toProfileVisits(today: LocalDate): List<ProfileVisit> {
  // Retired rows never appear: a superseded visit was replaced by an LMP correction, and a
  // cancelled one lapsed at delivery. Neither is something the Sakhi can act on, and showing them
  // would imply a backlog that does not exist.
  val visible = filterNot { it.status in RETIRED_STATUSES }

  // Completion order matters for FR-S-4.6 — "is there prior data?" is answered against visits
  // earlier in the *schedule*, so it must be computed before any reordering for display.
  val completedBefore = visible.runningFold(0) { count, visit ->
    if (visit.status == VisitScheduleStatus.COMPLETED) count + 1 else count
  }

  val (completed, upcoming) = visible
    .mapIndexed { index, visit ->
      visit to visit.toProfileVisit(today, priorCompletedCount = completedBefore[index])
    }
    .partition { (_, profileVisit) -> profileVisit.state == ProfileVisitState.COMPLETED }

  return upcoming.sortedBy { (entity, _) -> entity.scheduledDate }.map { it.second } +
    completed.sortedByDescending { (entity, _) -> entity.scheduledDate }.map { it.second }
}

private fun VisitScheduleEntity.toProfileVisit(
  today: LocalDate,
  priorCompletedCount: Int,
): ProfileVisit {
  val completed = status == VisitScheduleStatus.COMPLETED
  val missed = status == VisitScheduleStatus.MISSED

  return ProfileVisit(
    // The schedule's own key, so the visit form receives an identifier the server will recognise
    // once the schedule has synced. Replaces the old "v1".."v4" space — see CR-025.
    id = localScheduleUuid,
    label = visitCode,
    // ProfileVisitState has only OPEN and COMPLETED, so a missed visit has nowhere of its own to
    // sit. It renders as OPEN-but-not-startable, which at least disables the button.
    state = if (completed) ProfileVisitState.COMPLETED else ProfileVisitState.OPEN,
    dateLabel = scheduledDate.format(DATE_LABEL_FORMAT),
    action = if (completed) ProfileVisitAction.SEE_DATA else ProfileVisitAction.START_VISIT,
    // No countdown on a missed visit. Left as-is it would read "0 days remaining", implying the
    // window shuts today rather than that it already has — false urgency on a row the Sakhi can do
    // nothing about. A proper "Missed" treatment needs a third ProfileVisitState and a card design;
    // raised for CR-024 rather than invented here.
    daysRemaining = if (completed || missed) null else daysRemaining(today),
    startable = !completed && !missed && isInWindow(today),
    // FR-S-4.6: the Pre-Visit Health History screen needs at least one earlier completed visit to
    // have anything to show. A beneficiary's genuine first visit skips straight to the form.
    hasPreVisitHistory = priorCompletedCount > 0,
    // riskLabel and referralIncomplete stay unset. Both are derived from a completed visit's
    // clinical outcome, which lives in the visit form — unbuilt until CR-026. The static list this
    // replaces hardcoded them, so the "High Risk" chip disappears from the profile until then.
    // A known, deliberate regression, not an oversight.
  )
}

/**
 * The countdown badge, or null when the visit is too far off to be worth one.
 *
 * Two readings in one field, both matching the design's "1 day remaining":
 *  - **Inside the window** — days until it closes. That is the Sakhi's real deadline; a visit
 *    scheduled yesterday with three days of window left is not overdue, so counting to the
 *    scheduled date would wrongly show it as such.
 *  - **Before the window opens** — days until it does, but only within [UPCOMING_BADGE_DAYS]. The
 *    design shows "1 day remaining" on a visit whose Start Visit button is still greyed out, so the
 *    badge is a heads-up as well as a deadline.
 *
 * Beyond that threshold there is no badge at all. A freshly generated ANC series is ten visits
 * spanning nine months; labelling every one "245 days remaining" is noise that buries the one card
 * that matters.
 */
private fun VisitScheduleEntity.daysRemaining(today: LocalDate): Int? {
  val daysToClose = ChronoUnit.DAYS.between(today, windowEndDate)
  if (daysToClose < 0) return null

  val daysToOpen = ChronoUnit.DAYS.between(today, windowStartDate)
  return when {
    daysToOpen <= 0 -> daysToClose.toInt()
    daysToOpen <= UPCOMING_BADGE_DAYS -> daysToOpen.toInt()
    else -> null
  }
}

/**
 * How far ahead of its window a visit starts showing a countdown. A week reads as "coming up" to a
 * Sakhi planning her round; a month does not.
 */
private const val UPCOMING_BADGE_DAYS = 7L

/** Start Visit is enabled only inside the window — not before it opens, not after it closes. */
private fun VisitScheduleEntity.isInWindow(today: LocalDate): Boolean =
  !today.isBefore(windowStartDate) && !today.isAfter(windowEndDate)

private val RETIRED_STATUSES = setOf(
  VisitScheduleStatus.SUPERSEDED,
  VisitScheduleStatus.CANCELLED,
)
