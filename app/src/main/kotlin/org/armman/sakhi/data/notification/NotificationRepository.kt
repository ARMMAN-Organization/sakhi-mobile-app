package org.armman.sakhi.data.notification

/**
 * Notification feed data boundary. UI/ViewModel depends only on this interface — mirrors
 * [org.armman.sakhi.data.dashboard.DashboardRepository]'s shape.
 */
interface NotificationRepository {
  /** Returns the current notification list, most-recent backend state. Best-effort: a network
   * failure returns an empty list rather than throwing — the dashboard's core summary must never
   * be blocked or errored out by the notification banner failing to load (see
   * [RemoteNotificationRepository] for why there's no offline cache fallback here yet, unlike
   * [org.armman.sakhi.data.dashboard.RemoteDashboardRepository]). */
  suspend fun getNotifications(): List<AppNotification>
}
