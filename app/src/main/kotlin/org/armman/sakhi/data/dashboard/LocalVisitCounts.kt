package org.armman.sakhi.data.dashboard

import org.armman.sakhi.data.schedule.VisitScheduleEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Locally-known open-visit counts that the server dashboard/visit-tracker cannot possibly know
 * about yet (bharath, 2026-09-10 — "visit tracker screen not showing the open count for a visit
 * I can see on the profile, even when offline").
 *
 * ### Why this exists
 * Both `GET /sakhi/{sakhiId}/dashboard` and `GET /padas/{padaId}/visits` are server-computed and
 * cached client-side ([org.armman.sakhi.data.dashboard.RemoteDashboardRepository],
 * [org.armman.sakhi.data.visit.RemoteVisitRepository]) — good for surviving a dropped connection,
 * but a schedule this device generated itself (SRS FR-S-2.2: enrolment, delivery, an LMP/EDD
 * change, or the SR-ANC-01 post-EDD visit) and has not yet uploaded was never part of any server
 * response, cached or fresh. It is invisible to both screens until the schedule syncs, however
 * long that takes — which defeats the point of an offline-first app for the one thing (today's
 * open visits) a Sakhi most needs while offline.
 *
 * ### Why "unsynced" is the safe boundary
 * [VisitScheduleEntity.serverScheduleId] `== null` is a hard guarantee the row has never reached
 * the server, so counting it can never double-count something the server already reported. A
 * schedule that HAS synced is left alone — the server's own count is trusted for it — so this is
 * a pure additive overlay, same shape as [org.armman.sakhi.ui.home.HomeViewModel]'s existing
 * `overlayPendingCounts` for the beneficiary counts.
 *
 * ### Due / overdue / ending-soon, computed the same way the profile screen does
 * There is no separate "missed" bookkeeping on the device — [VisitScheduleEntity.status] stays
 * `GENERATED` even after its window closes (see
 * [org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper]'s own doc: `MISSED` is never
 * actually set anywhere in this app). So "overdue" here is purely a date comparison against
 * today, exactly like [org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper.isInWindow] —
 * not a distinct status.
 */
data class LocalVisitCounts(
  val due: Int = 0,
  val overdue: Int = 0,
  val endingSoon: Int = 0,
) {
  val isEmpty: Boolean get() = due == 0 && overdue == 0 && endingSoon == 0
}

/**
 * How close to its window closing a visit counts as "ending soon" for this local overlay.
 *
 * The backend's own `endingSoonVisitsCount` threshold isn't exposed to the client, so this is a
 * best-effort estimate, not a confirmed match — flagged rather than silently assumed identical.
 * Chosen to match [org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper]'s own "upcoming"
 * framing: a window closing within a few days is the one a Sakhi needs to act on now.
 */
private const val LOCAL_ENDING_SOON_DAYS = 3L

/**
 * Aggregates never-uploaded, currently-open schedules (see [VisitScheduleEntity.serverScheduleId])
 * into the three buckets [LocalVisitCounts] tracks.
 *
 * A schedule whose window hasn't opened yet (e.g. ANC5 while ANC4 is still the open one) is
 * counted in neither bucket — matching the server's own `dueVisitsCount`/`overdueVisitsCount`
 * semantics, which describe *actionable* visits, not the whole future series.
 */
/**
 * Both overlay variants for the Home dashboard — see [org.armman.sakhi.ui.home.HomeViewModel]'s
 * doc for why the choice between them depends on [org.armman.sakhi.data.dashboard.DashboardSummary
 * .lastSyncedAt]: [unsyncedOnly] is the safe additive count on top of a summary the backend has
 * genuinely computed at least once; [all] is the correct one when it hasn't (`lastSyncedAt ==
 * null`), because in that case there is no real server number to protect from double-counting —
 * exactly the [org.armman.sakhi.data.visittracker.LocalPadaSummaryOverlay]/[org.armman.sakhi.ui
 * .visittracker.PadaVisitsViewModel] full-replacement reasoning applied to the dashboard cards.
 */
data class LocalVisitOverlay(
  val unsyncedOnly: LocalVisitCounts = LocalVisitCounts(),
  val all: LocalVisitCounts = LocalVisitCounts(),
)

fun List<VisitScheduleEntity>.toLocalVisitCounts(today: LocalDate = LocalDate.now()): LocalVisitCounts {
  var due = 0
  var overdue = 0
  var endingSoon = 0
  for (schedule in this) {
    when {
      today.isAfter(schedule.windowEndDate) -> overdue++
      !today.isBefore(schedule.windowStartDate) -> {
        due++
        if (ChronoUnit.DAYS.between(today, schedule.windowEndDate) <= LOCAL_ENDING_SOON_DAYS) endingSoon++
      }
      // Window not open yet — not due, not overdue, not counted (matches the server's own
      // "actionable now" scope for these two fields).
      else -> Unit
    }
  }
  return LocalVisitCounts(due = due, overdue = overdue, endingSoon = endingSoon)
}
