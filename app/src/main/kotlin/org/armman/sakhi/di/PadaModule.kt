package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.visittracker.PadaApi
import org.armman.sakhi.data.visittracker.PadaRepository
import org.armman.sakhi.data.visittracker.RemotePadaRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** M3: `GET /sakhi/{sakhiId}/padas` is confirmed live (mock, 2026-08-14) — binds the real pada
 * summary implementation, replacing [org.armman.sakhi.ui.visittracker.PadaSelectionViewModel]'s
 * old client-side aggregation over the static today's-visits list. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PadaModule {
  @Binds
  @Singleton
  abstract fun bindPadaRepository(impl: RemotePadaRepository): PadaRepository

  companion object {
    @Provides
    @Singleton
    fun providePadaApi(retrofit: Retrofit): PadaApi = retrofit.create(PadaApi::class.java)
  }
}
