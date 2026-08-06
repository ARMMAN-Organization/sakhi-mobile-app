package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.beneficiary.BeneficiaryRepository
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryRepository
import javax.inject.Singleton

/**
 * CR-022g: My Beneficiaries lists the Sakhi's own enrolments only. The fourteen seeded fixtures and
 * `StaticBeneficiaryRepository` are gone — every one was a dead end with no schedule behind it, and
 * keeping them would leave a Sakhi unable to tell her own work from sample data.
 *
 * Gains a remote source when the beneficiary-list API lands (CR-024).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BeneficiaryModule {
  @Binds
  @Singleton
  abstract fun bindBeneficiaryRepository(impl: LocalBeneficiaryRepository): BeneficiaryRepository
}
