package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.auth.AuthApi
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.auth.CurrentUserRepository
import org.armman.sakhi.data.auth.RemoteAuthRepository
import org.armman.sakhi.data.auth.RemoteCurrentUserRepository
import org.armman.sakhi.data.auth.session.EncryptedSharedPreferencesStore
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.AndroidConnectivityChecker
import org.armman.sakhi.data.connectivity.AndroidConnectivityObserver
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.connectivity.ConnectivityObserver
import retrofit2.Retrofit
import java.time.Clock
import javax.inject.Singleton

/** Binds the real auth-service-backed implementation and its collaborators. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
  @Binds
  @Singleton
  abstract fun bindAuthRepository(impl: RemoteAuthRepository): AuthRepository

  @Binds
  @Singleton
  abstract fun bindCurrentUserRepository(impl: RemoteCurrentUserRepository): CurrentUserRepository

  @Binds
  @Singleton
  abstract fun bindConnectivityChecker(impl: AndroidConnectivityChecker): ConnectivityChecker

  @Binds
  @Singleton
  abstract fun bindConnectivityObserver(impl: AndroidConnectivityObserver): ConnectivityObserver

  @Binds
  @Singleton
  abstract fun bindSecureKeyValueStore(impl: EncryptedSharedPreferencesStore): SecureKeyValueStore

  companion object {
    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    /** System UTC clock for session-expiry checks. Injected (rather than called statically) so
     * expiry logic stays unit-testable with a fixed clock. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
  }
}
