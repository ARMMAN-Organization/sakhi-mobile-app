package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.auth.AuthRepository
import org.armman.sakhi.data.auth.StaticAuthRepository
import javax.inject.Singleton

/** Binds the static auth implementation; swap the binding when the API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
  @Binds
  @Singleton
  abstract fun bindAuthRepository(impl: StaticAuthRepository): AuthRepository
}
