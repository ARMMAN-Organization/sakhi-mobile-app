package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.MotherLinkRepository
import org.armman.sakhi.data.motherlink.RemoteMotherLinkRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Binds the child-enrollment mother link (CR-031) to the real `beneficiary-service` read endpoints,
 * through the same Retrofit instance/API gateway as every other service.
 *
 * Note this is separate from [BeneficiaryModule], which still binds the *mock*
 * `StaticBeneficiaryRepository` for the My Beneficiaries list. The two are deliberately not merged:
 * that list needs risk level, visit state and next-visit data the `/beneficiaries` response does not
 * carry, so swapping it over is its own CR.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MotherLinkModule {
  @Binds
  @Singleton
  abstract fun bindMotherLinkRepository(impl: RemoteMotherLinkRepository): MotherLinkRepository

  companion object {
    @Provides
    @Singleton
    fun provideBeneficiaryApi(retrofit: Retrofit): BeneficiaryApi =
      retrofit.create(BeneficiaryApi::class.java)
  }
}
