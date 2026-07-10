package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.beneficiaryprofile.StaticBeneficiaryProfileRepository
import javax.inject.Singleton

/** Binds the static beneficiary-profile implementation; swap when the API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BeneficiaryProfileModule {
  @Binds
  @Singleton
  abstract fun bindBeneficiaryProfileRepository(
    impl: StaticBeneficiaryProfileRepository,
  ): BeneficiaryProfileRepository
}
