package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.lookup.LookupApi
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.lookup.RemoteLookupRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the real lookup-endpoint-backed implementation (auth-service `/lookups/:categoryCode`,
 * reachable through the same Retrofit instance/API gateway as [org.armman.sakhi.data.auth.AuthApi]). */
@Module
@InstallIn(SingletonComponent::class)
abstract class LookupModule {
  @Binds
  @Singleton
  abstract fun bindLookupRepository(impl: RemoteLookupRepository): LookupRepository

  companion object {
    @Provides
    @Singleton
    fun provideLookupApi(retrofit: Retrofit): LookupApi = retrofit.create(LookupApi::class.java)
  }
}
