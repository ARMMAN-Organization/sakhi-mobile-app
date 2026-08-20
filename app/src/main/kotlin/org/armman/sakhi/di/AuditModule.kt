package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.audit.RoomFormAuditRepository
import javax.inject.Singleton

/** Binds the CR-035 capture-only local audit-trail repository. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuditModule {
  @Binds
  @Singleton
  abstract fun bindFormAuditRepository(impl: RoomFormAuditRepository): FormAuditRepository
}
