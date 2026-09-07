package org.armman.sakhi.data.notification

import retrofit2.Response
import retrofit2.http.GET

/** One row of `GET /notifications`. Only the fields the app reads are modelled —
 * Gson leaves anything else out, and the backend adding a property must not break the parse (same
 * convention as [org.armman.sakhi.data.dashboard.DashboardDataDto]). */
data class NotificationDto(
  val id: String?,
  /** Backend notification-type code (e.g. "MISSED_VISIT_ESCALATION", "EDD_APPROACHING") — real
   * values confirmed 2026-09-02, mapped to the SRS FR-S-7.2 stacking order by
   * [org.armman.sakhi.data.notification.NotificationStackOrder]. */
  val type: String?,
  val title: String?,
  val body: String?,
  /** Raw backend priority — NOT used for sorting (unenforced on the DB side per backend,
   * 2026-09-02); kept only because the field exists on the wire. Sorting uses
   * [org.armman.sakhi.data.notification.AppNotification.srsStackRank] instead. */
  val priority: Int?,
  val status: String?,
  val createdAt: String?,
  val readAt: String?,
  /** CTA action code, e.g. "FILL_REFERRAL_FORM" — confirmed 2026-09-02, but only ever set for
   * `REFERRAL_INCOMPLETE_UPDATE` today (null on every other type, and null on that same type when
   * the referral was approved/lapsed rather than rejected — see [ctaType]'s use in
   * [org.armman.sakhi.ui.components.NotificationBanner]). */
  val ctaType: String?,
  /** Polymorphic reference target for [ctaType]'s navigation, e.g. "Referral". Only meaningful
   * alongside a non-null [ctaType]. */
  val linkedEntityType: String?,
  /** Id within [linkedEntityType] — for `REFERRAL_INCOMPLETE_UPDATE`/`FILL_REFERRAL_FORM` this is
   * the referral id, NOT a beneficiary id (see [org.armman.sakhi.ui.home.HomeViewModel.onFillReferralFormClicked]
   * for how the beneficiary id needed to actually navigate is resolved from this). */
  val linkedEntityId: String?,
)

data class NotificationListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: List<NotificationDto>?,
)

/**
 * Retrofit contract for the Sakhi notification feed, behind the same API gateway/base URL and
 * Bearer token as every other service ([org.armman.sakhi.data.auth.AuthInterceptor]).
 *
 * CONFIRMED live 2026-09-02 (backend read the actual route/controller/service, not just the
 * schema): plain `GET /notifications`, no path or query param for a Sakhi id at all — scoping is
 * entirely off the caller's JWT (`repository.findMany(caller.id)`,
 * `WHERE recipientUserId = caller.id`). This is unlike
 * [org.armman.sakhi.data.dashboard.DashboardApi], which takes an explicit `sakhiId` path param —
 * do not "fix" this to match that pattern, it was confirmed deliberately different. Also
 * confirmed: results are `ORDER BY createdAt DESC LIMIT 50` server-side — there is no
 * Supervisor-facing `/…/by-sakhi/:sakhiId` variant (a real gap backend flagged, not something
 * this app needs to work around today since only the Sakhi's own notifications are needed here).
 */
interface NotificationApi {
  @GET("notifications")
  suspend fun getNotifications(): Response<NotificationListResponseDto>
}
