package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.visitform.RiskAssessmentApi
import org.armman.sakhi.data.visitform.RoomVisitFormDraftRepository
import org.armman.sakhi.data.visitform.StaticVisitFormRepository
import org.armman.sakhi.data.visitform.VisitApi
import org.armman.sakhi.data.visitform.VisitFormDraftRepository
import org.armman.sakhi.data.visitform.VisitFormRepository
import org.armman.sakhi.data.visitform.VisitFormSyncScheduler
import org.armman.sakhi.data.visitform.WorkManagerVisitFormSyncScheduler
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the in-memory Visit Form context store until real persistence exists, and provides the
 * real `POST /visits` API [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator] calls
 * (that one is real today — only [VisitFormRepository]'s context-fetch side is still stubbed).
 * Also binds the CR-026b offline-submit queue: [VisitFormDraftRepository] and
 * [VisitFormSyncScheduler]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisitFormModule {

  @Binds
  @Singleton
  abstract fun bindVisitFormRepository(impl: StaticVisitFormRepository): VisitFormRepository

  @Binds
  @Singleton
  abstract fun bindVisitFormDraftRepository(impl: RoomVisitFormDraftRepository): VisitFormDraftRepository

  @Binds
  @Singleton
  abstract fun bindVisitFormSyncScheduler(impl: WorkManagerVisitFormSyncScheduler): VisitFormSyncScheduler

  companion object {
    @Provides
    @Singleton
    fun provideVisitApi(retrofit: Retrofit): VisitApi = retrofit.create(VisitApi::class.java)

    /** Phase 5 (CR — offline high-risk rule evaluation): `POST /risk-assessments`, confirmed open
     * to SAKHI by backend 2026-08-24 — see [RiskAssessmentApi]'s doc. */
    @Provides
    @Singleton
    fun provideRiskAssessmentApi(retrofit: Retrofit): RiskAssessmentApi =
      retrofit.create(RiskAssessmentApi::class.java)
  }
}
