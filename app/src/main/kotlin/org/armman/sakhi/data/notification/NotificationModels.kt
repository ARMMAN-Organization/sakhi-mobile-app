package org.armman.sakhi.data.notification

import java.time.Instant

/**
 * One dashboard notification, as returned by `GET /sakhi/{sakhiId}/notifications`.
 *
 * [priority] is the raw backend int and is NOT used for sorting — backend confirmed
 * (2026-09-02) `Notification.priority` isn't enforced in the DB yet. Use [srsStackRank] instead,
 * which is the real, fully-specified client-side stand-in per the SRS.
 *
 * There is still no per-type severity/color styling — that part of the spec (visual treatment per
 * type) hasn't been requested/confirmed, unlike the stacking order which now has real wire values.
 */
data class AppNotification(
  val id: String,
  val type: String,
  val title: String,
  val body: String,
  val priority: Int,
  val status: String,
  val createdAt: Instant?,
  val readAt: Instant?,
  /** CTA action code (e.g. "FILL_REFERRAL_FORM") — null when this notification has no action to
   * take, which today is every type except a rejected `REFERRAL_INCOMPLETE_UPDATE`. */
  val ctaType: String?,
  val linkedEntityType: String?,
  /** Id within [linkedEntityType] — see [NotificationDto.linkedEntityId]'s doc for why this is
   * NOT a beneficiary id for the referral CTA and needs a separate resolution step. */
  val linkedEntityId: String?,
) {
  /** This notification's position in the SRS FR-S-7.2 stacking order — lower sorts first (more
   * urgent). See [NotificationStackOrder] for the full mapping and its known gaps. */
  val srsStackRank: Int get() = NotificationStackOrder.rankOf(type)
}

/** Backend `notificationType` value for a rejected-referral-follow-up outcome — the only type
 * with a confirmed, working CTA today (2026-09-02). */
const val NOTIFICATION_TYPE_REFERRAL_INCOMPLETE_UPDATE = "REFERRAL_INCOMPLETE_UPDATE"

/** [AppNotification.ctaType] value confirmed live for [NOTIFICATION_TYPE_REFERRAL_INCOMPLETE_UPDATE] —
 * only set when the Supervisor rejected the follow-up (there is something to fill); null on the
 * same notification type when the referral was approved/lapsed instead. */
const val NOTIFICATION_CTA_FILL_REFERRAL_FORM = "FILL_REFERRAL_FORM"

/**
 * Client-side stand-in for the SRS FR-S-7.2 stacking order `6 > 8 > 7 > 1 > 3 > 4 > 2 > 5`
 * (SRS row numbers, not sequential rank), mapped to the real `notificationType` wire values
 * backend confirmed on 2026-09-02 (source: `notification-escalation-service/prisma/schema.prisma`
 * + the two call sites that create these rows). Safe to hardcode per backend: "if backend's
 * eventual DB-side mapping ever needs to diverge from this, we'll flag it as a breaking change,
 * not a silent one."
 *
 * KNOWN GAP — do not "fix" this without a backend answer first: `MISSED_VISIT_ESCALATION` covers
 * BOTH SRS row 1 ("HR escalation / missed visit escalation outcome", rank 3) and row 4 ("High
 * missed visits", rank 5) — backend confirmed the wire type does not distinguish them. This picks
 * row 1's rank (3, more urgent) for every `MISSED_VISIT_ESCALATION`/`HR_MISSED_VISIT_ESCALATION`
 * row as the safer default — under-prioritizing a real escalation-outcome notification by treating
 * it as the less-urgent "high missed visits" case would be worse than the reverse. Similarly,
 * `DATA_SYNC_STATUS` covers both "upload stopped mid-way" and "3-day no-sync" (SRS both list as
 * row 8 anyway, so this one has no actual ambiguity).
 */
object NotificationStackOrder {
  // SRS row number -> rank (0 = shown first / most urgent), from "6>8>7>1>3>4>2>5".
  private val SRS_ROW_TO_RANK: Map<Int, Int> =
    listOf(6, 8, 7, 1, 3, 4, 2, 5).withIndex().associate { (rank, row) -> row to rank }

  // Real notificationType wire value -> SRS row number.
  private val WIRE_TYPE_TO_SRS_ROW: Map<String, Int> = mapOf(
    "MISSED_VISIT_ESCALATION" to 1, // ambiguous with row 4 — see class doc
    "HR_MISSED_VISIT_ESCALATION" to 1,
    "REFERRAL_INCOMPLETE_UPDATE" to 2,
    "EDD_APPROACHING" to 3,
    "REOPEN_UPDATE" to 5,
    "CLOSURE_UPDATE" to 5,
    "LMP_CHANGE_UPDATE" to 6,
    "FORM_UPDATE" to 7,
    "DATA_SYNC_STATUS" to 8,
  )

  private val UNKNOWN_TYPE_RANK = SRS_ROW_TO_RANK.size // sorts after every known type, not before

  /** Rank for [type] — lower sorts first. A type not in [WIRE_TYPE_TO_SRS_ROW] (a future backend
   * addition we haven't mapped yet) sorts last rather than throwing or defaulting to "most
   * urgent", so an unrecognized type can't accidentally jump the stack. */
  fun rankOf(type: String): Int =
    WIRE_TYPE_TO_SRS_ROW[type]?.let { SRS_ROW_TO_RANK[it] } ?: UNKNOWN_TYPE_RANK
}
