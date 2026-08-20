package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.referral.ReferralApi
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.referral.RemoteReferralRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** M3: `GET /sakhi/{sakhiId}/referrals/pending-followup` is confirmed live (mock, 2026-08-14) —
 * binds the real referral follow-up implementation. No screen consumes this yet; see
 * [org.armman.sakhi.data.referral.ReferralRepository]'s doc for why. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReferralModule {
  @Binds
  @Singleton
  abstract fun bindReferralRepository(impl: RemoteReferralRepository): ReferralRepository

  companion object {
    @Provides
    @Singleton
    fun provideReferralApi(retrofit: Retrofit): ReferralApi = retrofit.create(ReferralApi::class.java)
  }
}
