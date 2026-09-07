package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.lmpchange.LmpChangeApi
import org.armman.sakhi.data.lmpchange.LmpChangeRepository
import org.armman.sakhi.data.lmpchange.RemoteLmpChangeRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the real `POST`/`GET /lmp-change-requests` implementation, confirmed live by the backend
 * team 2026-09-02 — mirrors [ReopenModule]'s style. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LmpChangeModule {
  @Binds
  @Singleton
  abstract fun bindLmpChangeRepository(impl: RemoteLmpChangeRepository): LmpChangeRepository

  companion object {
    @Provides
    @Singleton
    fun provideLmpChangeApi(retrofit: Retrofit): LmpChangeApi = retrofit.create(LmpChangeApi::class.java)
  }
}
