package org.armman.sakhi.data.notification

import org.armman.sakhi.data.auth.session.SessionStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [NotificationRepository] backed by [NotificationApi]. Deliberately no on-disk cache/
 * fallback yet (unlike [org.armman.sakhi.data.dashboard.RemoteDashboardRepository]) — the
 * notification banner is best-effort supplementary UI, not core dashboard data the Sakhi depends
 * on being available offline; add persistence here if that changes once the feature is live in
 * the field.
 *
 * Every failure path (no session, request failure, malformed body, exception) resolves to an
 * empty list rather than throwing, so a notification-feed problem never surfaces as a dashboard
 * error state — see [NotificationRepository.getNotifications]'s doc.
 */
@Singleton
class RemoteNotificationRepository @Inject constructor(
  private val notificationApi: NotificationApi,
  private val sessionStore: SessionStore,
) : NotificationRepository {

  override suspend fun getNotifications(): List<AppNotification> {
    // GET /notifications scopes itself off the caller's JWT (backend-confirmed 2026-09-02, see
    // NotificationApi's doc) — no sakhiId is sent. Still gated on a session existing at all, so a
    // logged-out state doesn't fire a request that can only 401.
    if (sessionStore.readSession() == null) return emptyList()
    return try {
      notificationApi.getNotifications()
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
        ?.mapNotNull { it.toDomain() }
        ?: emptyList()
    } catch (e: Exception) {
      // Offline, timeout, malformed body — all degrade to "no notifications" rather than an
      // error state.
      emptyList()
    }
  }

  /** Null when the row is missing the fields needed to render or identify it — dropped rather
   * than shown malformed (same "degrade, don't crash" rule as
   * [org.armman.sakhi.data.dashboard.RemoteDashboardRepository.toDomain]). */
  private fun NotificationDto.toDomain(): AppNotification? {
    val notificationId = id?.takeIf { it.isNotBlank() } ?: return null
    val notificationTitle = title?.takeIf { it.isNotBlank() } ?: return null
    return AppNotification(
      id = notificationId,
      type = type ?: "UNKNOWN",
      title = notificationTitle,
      body = body.orEmpty(),
      priority = priority ?: Int.MAX_VALUE,
      status = status ?: "UNKNOWN",
      createdAt = createdAt?.toInstantOrNull(),
      readAt = readAt?.toInstantOrNull(),
      ctaType = ctaType,
      linkedEntityType = linkedEntityType,
      linkedEntityId = linkedEntityId,
    )
  }
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
