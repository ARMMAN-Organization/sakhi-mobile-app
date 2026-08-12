package org.armman.sakhi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.enrollment.EnrollmentDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.schedule.ScheduleTypeConverters
import org.armman.sakhi.data.schedule.VisitScheduleDao
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.visitform.VisitFormDraftDao
import org.armman.sakhi.data.visitform.VisitFormDraftEntity

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
 *  - v4: [VisitScheduleEntity] (CR-022 offline visit scheduling). Additive [MIGRATION_3_4] —
 *    creates `visit_schedules` plus its two indices, touches no existing table. First entity that
 *    is real domain data rather than sync metadata, and the first to need [ScheduleTypeConverters]
 *    for its [java.time.LocalDate] columns.
 *  - v5: [VisitFormDraftEntity] (CR-026b visit-form offline sync — the fifth queue, alongside the
 *    three form-draft tables and `visit_schedules`). Additive [MIGRATION_4_5] — creates
 *    `visit_form_drafts` only, touches no existing table.
 */
@Database(
  entities = [
    EnrollmentDraftEntity::class,
    DynamicFormDraftEntity::class,
    ChildFormDraftEntity::class,
    VisitScheduleEntity::class,
    VisitFormDraftEntity::class,
  ],
  version = 5,
  exportSchema = true,
)
@TypeConverters(ScheduleTypeConverters::class)
abstract class SakhiDatabase : RoomDatabase() {
  abstract fun enrollmentDraftDao(): EnrollmentDraftDao
  abstract fun dynamicFormDraftDao(): DynamicFormDraftDao
  abstract fun childFormDraftDao(): ChildFormDraftDao
  abstract fun visitScheduleDao(): VisitScheduleDao
  abstract fun visitFormDraftDao(): VisitFormDraftDao

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

    /**
     * v3 → v4: adds the CR-022 `visit_schedules` table and its two indices. Purely additive — the
     * three draft tables are untouched, so anything queued for upload survives the upgrade.
     *
     * Column definitions must match [VisitScheduleEntity] exactly or Room's schema validation
     * fails at open time. Two conversions to keep in mind when editing:
     *  - enums (`visitType`, `anchorType`, `status`, `escalationPolicy`) are stored as TEXT by name;
     *  - [java.time.LocalDate] columns are TEXT ISO-8601 via [ScheduleTypeConverters], NOT integers.
     *
     * Index names are Room's own convention (`index_<table>_<cols>`); a mismatch here also fails
     * validation even though the index itself would be functionally identical.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `visit_schedules` (" +
            "`localScheduleUuid` TEXT NOT NULL, " +
            "`serverScheduleId` TEXT, " +
            "`localBeneficiaryId` TEXT NOT NULL, " +
            "`serverBeneficiaryId` TEXT, " +
            "`visitCode` TEXT NOT NULL, " +
            "`visitType` TEXT NOT NULL, " +
            "`sequenceNo` INTEGER NOT NULL, " +
            "`scheduledDate` TEXT NOT NULL, " +
            "`windowStartDate` TEXT NOT NULL, " +
            "`windowEndDate` TEXT NOT NULL, " +
            "`anchorType` TEXT NOT NULL, " +
            "`anchorDate` TEXT NOT NULL, " +
            "`anchorVisitLocalUuid` TEXT, " +
            "`status` TEXT NOT NULL, " +
            "`reasonCode` TEXT, " +
            "`generatedByRuleVersion` TEXT NOT NULL, " +
            "`escalationPolicy` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`localScheduleUuid`))",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_visit_schedules_localBeneficiaryId_status` " +
            "ON `visit_schedules` (`localBeneficiaryId`, `status`)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_visit_schedules_scheduledDate` " +
            "ON `visit_schedules` (`scheduledDate`)",
        )
      }
    }

    /**
     * v4 → v5: adds the CR-026b `visit_form_drafts` table. Purely additive — no existing table is
     * touched, so every other queue's rows survive the upgrade untouched.
     *
     * Column definitions must match [VisitFormDraftEntity] exactly or Room's schema validation
     * fails at open time. `syncStatus` is stored as TEXT by enum name, same convention as every
     * other drafts table.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `visit_form_drafts` (" +
            "`localScheduleUuid` TEXT NOT NULL, " +
            "`formCode` TEXT NOT NULL, " +
            "`formVersionId` TEXT NOT NULL, " +
            "`visitDateIso` TEXT NOT NULL, " +
            "`syncStatus` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`lastAttemptAtEpochMillis` INTEGER, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`serverVisitId` TEXT, " +
            "`lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`localScheduleUuid`))",
        )
      }
    }
  }
}
