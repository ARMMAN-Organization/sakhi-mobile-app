package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.FormsApi
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.RemoteFormsRepository
import org.armman.sakhi.data.forms.RoomDynamicFormDraftRepository
import org.armman.sakhi.data.forms.WorkManagerDynamicFormSyncScheduler
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the real dynamic-form-schema-endpoint-backed repository, offline draft store, and sync
 * scheduler (CR-018). */
@Module
@InstallIn(SingletonComponent::class)
abstract class FormsModule {
  @Binds
  @Singleton
  abstract fun bindFormsRepository(impl: RemoteFormsRepository): FormsRepository

  @Binds
  @Singleton
  abstract fun bindDynamicFormDraftRepository(impl: RoomDynamicFormDraftRepository): DynamicFormDraftRepository

  @Binds
  @Singleton
  abstract fun bindDynamicFormSyncScheduler(impl: WorkManagerDynamicFormSyncScheduler): DynamicFormSyncScheduler

  companion object {
    @Provides
    @Singleton
    fun provideFormsApi(retrofit: Retrofit): FormsApi = retrofit.create(FormsApi::class.java)

    @Provides
    @Singleton
    fun provideFormSubmissionApi(retrofit: Retrofit): FormSubmissionApi =
      retrofit.create(FormSubmissionApi::class.java)
  }
}
