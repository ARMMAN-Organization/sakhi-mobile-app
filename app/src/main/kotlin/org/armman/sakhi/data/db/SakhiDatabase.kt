package org.armman.sakhi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import org.armman.sakhi.data.enrollment.EnrollmentDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftEntity

/**
 * App's single Room database. Holds enrollment and dynamic-form sync-queue tables; other
 * offline-first features (Visit Form, Referral Follow-up) are expected to add their own
 * entities/DAOs here rather than opening separate database files, so a future migration can
 * reason about the whole local schema in one place.
 *
 * Bumped to version 2 for [DynamicFormDraftEntity] (CR-018 offline sync). No user-facing release
 * has shipped with a v1 database yet, so [org.armman.sakhi.di.DatabaseModule] uses
 * `fallbackToDestructiveMigration()` rather than a real `Migration` — that stops being acceptable
 * the moment this app is in the hands of real Sakhis with real drafts to lose; flag any future
 * schema change here for a proper migration instead.
 */
@Database(
  entities = [EnrollmentDraftEntity::class, DynamicFormDraftEntity::class],
  version = 2,
  exportSchema = true,
)
abstract class SakhiDatabase : RoomDatabase() {
  abstract fun enrollmentDraftDao(): EnrollmentDraftDao
  abstract fun dynamicFormDraftDao(): DynamicFormDraftDao
}
