package org.armman.sakhi.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.db.SakhiDatabase
import org.armman.sakhi.data.enrollment.EnrollmentDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import javax.inject.Singleton

private const val DATABASE_NAME = "sakhi.db"

/** Provides the single Room database instance and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

  @Provides
  @Singleton
  fun provideSakhiDatabase(@ApplicationContext context: Context): SakhiDatabase =
    Room.databaseBuilder(context, SakhiDatabase::class.java, DATABASE_NAME)
      // See SakhiDatabase's doc: acceptable only because no release has shipped with a v1 DB yet.
      .fallbackToDestructiveMigration()
      .build()

  @Provides
  @Singleton
  fun provideEnrollmentDraftDao(database: SakhiDatabase): EnrollmentDraftDao =
    database.enrollmentDraftDao()

  @Provides
  @Singleton
  fun provideDynamicFormDraftDao(database: SakhiDatabase): DynamicFormDraftDao =
    database.dynamicFormDraftDao()

  @Provides
  @Singleton
  fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
    WorkManager.getInstance(context)
}
