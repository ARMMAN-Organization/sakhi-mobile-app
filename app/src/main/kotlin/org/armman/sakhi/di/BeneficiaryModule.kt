package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.beneficiary.BeneficiaryRepository
import org.armman.sakhi.data.beneficiary.OfflineFirstBeneficiaryRepository
import javax.inject.Singleton

/**
 * CR-022g: My Beneficiaries lists the Sakhi's own enrolments. The fourteen seeded fixtures and
 * `StaticBeneficiaryRepository` are gone — every one was a dead end with no schedule behind it, and
 * keeping them would leave a Sakhi unable to tell her own work from sample data.
 *
 * Bound to [OfflineFirstBeneficiaryRepository], which also has a remote source
 * ([org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository]) for beneficiaries this device's
 * local store no longer knows about — but that source stays off in production until
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryListFeatureFlag.ENABLED] flips to true, which
 * should only happen once `GET /beneficiaries` is scoped to the calling Sakhi server-side. Until
 * then this binding behaves identically to the old direct `LocalBeneficiaryRepository` binding —
 * [OfflineFirstBeneficiaryRepository] falls straight through to the local source when the flag is
 * off.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BeneficiaryModule {
  @Binds
  @Singleton
  abstract fun bindBeneficiaryRepository(impl: OfflineFirstBeneficiaryRepository): BeneficiaryRepository
}
