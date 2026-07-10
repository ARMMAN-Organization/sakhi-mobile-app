package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.beneficiary.BeneficiaryRepository
import org.armman.sakhi.data.beneficiary.StaticBeneficiaryRepository
import javax.inject.Singleton

/** Binds the static beneficiary implementation; swap when the API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BeneficiaryModule {
  @Binds
  @Singleton
  abstract fun bindBeneficiaryRepository(impl: StaticBeneficiaryRepository): BeneficiaryRepository
}
