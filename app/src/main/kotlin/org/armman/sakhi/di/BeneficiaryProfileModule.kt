package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.beneficiaryprofile.ScheduleBackedBeneficiaryProfileRepository
import javax.inject.Singleton

/**
 * CR-022f: the profile's visit list now comes from real generated schedules; identity, vitals and
 * diagnoses are still static until a beneficiary-detail API exists.
 *
 * [ScheduleBackedBeneficiaryProfileRepository] delegates to
 * [org.armman.sakhi.data.beneficiaryprofile.StaticBeneficiaryProfileRepository] for the static
 * half, so that class stays bound implicitly through its `@Inject` constructor rather than being
 * bound to the interface directly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BeneficiaryProfileModule {
  @Binds
  @Singleton
  abstract fun bindBeneficiaryProfileRepository(
    impl: ScheduleBackedBeneficiaryProfileRepository,
  ): BeneficiaryProfileRepository
}
