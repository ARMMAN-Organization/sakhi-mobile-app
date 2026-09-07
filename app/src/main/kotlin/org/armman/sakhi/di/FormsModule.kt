package org.armman.sakhi.di

import com.google.gson.GsonBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.armman.sakhi.BuildConfig
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
import org.armman.sakhi.data.forms.FieldEditsRepository
import org.armman.sakhi.data.forms.FormSubmissionApi
import org.armman.sakhi.data.forms.FormVisibleWhen
import org.armman.sakhi.data.forms.FormVisibleWhenDeserializer
import org.armman.sakhi.data.forms.FormsApi
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.RemoteFieldEditsRepository
import org.armman.sakhi.data.forms.RemoteFormsRepository
import org.armman.sakhi.data.forms.RoomDynamicFormDraftRepository
import org.armman.sakhi.data.forms.WorkManagerDynamicFormSyncScheduler
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

  /** CR-Registration-Edit: same `Retrofit`/`FormSubmissionApi` as `createSubmission` above — no
   * separate module needed, this is the same `visit-form-service`. */
  @Binds
  @Singleton
  abstract fun bindFieldEditsRepository(impl: RemoteFieldEditsRepository): FieldEditsRepository

  companion object {
    /**
     * Deliberately its own [Retrofit] instance rather than reusing the app-wide one from
     * `NetworkModule` — same rationale as `ScheduleModule.provideVisitScheduleApi`. Real
     * `GET /forms/:formCode/active-version` content has been seen shipping a malformed
     * `visibleWhen` (a JSON array where the schema always documents a single object — a
     * content-authoring bug, flagged to the backend team). The app-wide Gson has no adapter for
     * that, so it throws and fails the ENTIRE schemaJson parse; [RemoteFormsRepository]'s own
     * local `gson` field only covers its offline SecureKeyValueStore cache read/write, not this
     * live network call, so patching only that one was not enough (see
     * [FormVisibleWhenDeserializer]'s own doc for the fuller story). Scoped to just this API so
     * no other endpoint's null/error handling changes.
     */
    @Provides
    @Singleton
    fun provideFormsApi(client: OkHttpClient): FormsApi {
      val tolerantGson = GsonBuilder()
        .registerTypeAdapter(FormVisibleWhen::class.java, FormVisibleWhenDeserializer())
        .create()
      val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(tolerantGson))
        .build()
      return retrofit.create(FormsApi::class.java)
    }

    @Provides
    @Singleton
    fun provideFormSubmissionApi(retrofit: Retrofit): FormSubmissionApi =
      retrofit.create(FormSubmissionApi::class.java)
  }
}
