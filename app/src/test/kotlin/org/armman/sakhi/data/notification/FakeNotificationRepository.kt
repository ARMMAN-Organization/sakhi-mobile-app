package org.armman.sakhi.data.notification

/** In-memory fake — [notificationsToReturn] defaults to empty, matching
 * [NotificationRepository.getNotifications]'s own documented best-effort-empty-list contract. */
class FakeNotificationRepository : NotificationRepository {
  var notificationsToReturn: List<AppNotification> = emptyList()

  override suspend fun getNotifications(): List<AppNotification> = notificationsToReturn
}
