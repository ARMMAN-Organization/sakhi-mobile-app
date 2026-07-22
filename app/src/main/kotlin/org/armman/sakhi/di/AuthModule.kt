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
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import retrofit2.Retrofit
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
  abstract fun bindSecureKeyValueStore(impl: EncryptedSharedPreferencesStore): SecureKeyValueStore

  companion object {
    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
  }
}
