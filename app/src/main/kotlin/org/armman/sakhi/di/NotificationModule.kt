package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.notification.NotificationApi
import org.armman.sakhi.data.notification.NotificationRepository
import org.armman.sakhi.data.notification.RemoteNotificationRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Binds the notification feed used by the Home dashboard banner. See [NotificationApi]'s doc —
 * the request path is a placeholder pending backend confirmation (CR-Notification-Escalation
 * follow-up, 2026-09-02); this module itself needs no change once that's confirmed.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {
  @Binds
  @Singleton
  abstract fun bindNotificationRepository(impl: RemoteNotificationRepository): NotificationRepository

  companion object {
    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi = retrofit.create(NotificationApi::class.java)
  }
}
