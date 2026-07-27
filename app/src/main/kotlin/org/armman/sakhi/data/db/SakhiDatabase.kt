package org.armman.sakhi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.enrollment.EnrollmentDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftEntity

/**
 * App's single Room database. Holds enrollment, dynamic-form and Children Register sync-queue
 * tables; other offline-first features (Visit Form, Referral Follow-up) are expected to add their
 * own entities/DAOs here rather than opening separate database files, so a future migration can
 * reason about the whole local schema in one place.
 *
 * Version history:
 *  - v2: [DynamicFormDraftEntity] (CR-018 offline sync).
 *  - v3: [ChildFormDraftEntity] (CR-020 Children Register offline sync). Unlike the v1→v2 bump
 *    (which relied on `fallbackToDestructiveMigration()` because no release had shipped a v1 DB),
 *    this bump ships a REAL additive [MIGRATION_2_3] that only `CREATE TABLE`s the new
 *    `child_registration_drafts` table — no existing table is touched, so queued mother/enrollment
 *    drafts survive the upgrade. Keep providing a real migration for every future schema change.
 */
@Database(
  entities = [EnrollmentDraftEntity::class, DynamicFormDraftEntity::class, ChildFormDraftEntity::class],
  version = 3,
  exportSchema = true,
)
abstract class SakhiDatabase : RoomDatabase() {
  abstract fun enrollmentDraftDao(): EnrollmentDraftDao
  abstract fun dynamicFormDraftDao(): DynamicFormDraftDao
  abstract fun childFormDraftDao(): ChildFormDraftDao

  companion object {
    /**
     * v2 → v3: adds the CR-020 `child_registration_drafts` table. Column definitions match
     * [ChildFormDraftEntity] exactly (Room stores [org.armman.sakhi.data.enrollment.EnrollmentSyncStatus]
     * as TEXT by its enum name), so Room's own schema validation passes after the migration.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `child_registration_drafts` (" +
            "`localBeneficiaryId` TEXT NOT NULL, " +
            "`formCode` TEXT NOT NULL, " +
            "`formVersionId` TEXT NOT NULL, " +
            "`localSubmissionUuid` TEXT NOT NULL, " +
            "`syncStatus` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`lastAttemptAtEpochMillis` INTEGER, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`remoteBeneficiaryId` TEXT, " +
            "`remoteSubmissionId` TEXT, " +
            "`lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`localBeneficiaryId`))",
        )
      }
    }
  }
}
