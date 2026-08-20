package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.reopen.ReopenApi
import org.armman.sakhi.data.reopen.ReopenRepository
import org.armman.sakhi.data.reopen.RemoteReopenRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the real `POST`/`GET /reopen-requests` implementation, confirmed live by the backend
 * team — mirrors [ClosureModule]'s style. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReopenModule {
  @Binds
  @Singleton
  abstract fun bindReopenRepository(impl: RemoteReopenRepository): ReopenRepository

  companion object {
    @Provides
    @Singleton
    fun provideReopenApi(retrofit: Retrofit): ReopenApi = retrofit.create(ReopenApi::class.java)
  }
}
