package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.sync.CombinedUploadRecordsSource
import org.armman.sakhi.data.sync.UploadRecordsSource
import javax.inject.Singleton

/**
 * Cross-queue sync bindings (SRS §3A.1 manual-trigger-only data sync).
 *
 * `ManualSyncTrigger` and `LegacyPeriodicSyncCleanup` are concrete `@Singleton` classes with
 * `@Inject` constructors, so Hilt resolves them without a binding; only the
 * [UploadRecordsSource] abstraction needs one — it exists so `HomeViewModel` stays unit-testable
 * against a fake rather than three Room DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
  @Binds
  @Singleton
  abstract fun bindUploadRecordsSource(impl: CombinedUploadRecordsSource): UploadRecordsSource
}
