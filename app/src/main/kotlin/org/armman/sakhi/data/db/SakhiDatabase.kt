package org.armman.sakhi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.armman.sakhi.data.audit.FormAuditEventDao
import org.armman.sakhi.data.audit.FormAuditEventEntity
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.enrollment.EnrollmentDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentDraftEntity
import org.armman.sakhi.data.enrollment.EnrollmentRiskBaselineDao
import org.armman.sakhi.data.enrollment.EnrollmentRiskBaselineEntity
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.schedule.ScheduleTypeConverters
import org.armman.sakhi.data.schedule.VisitScheduleDao
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.visitform.VisitFormDraftDao
import org.armman.sakhi.data.visitform.VisitFormDraftEntity
import org.armman.sakhi.data.adhocform.AdHocFormDraftDao
import org.armman.sakhi.data.adhocform.AdHocFormDraftEntity
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftDao
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftEntity
import org.armman.sakhi.data.delivery.DeliveryFormDraftDao
import org.armman.sakhi.data.delivery.DeliveryFormDraftEntity
import org.armman.sakhi.data.delivery.DeliverySessionDao
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.riskassessment.RiskAssessmentDao
import org.armman.sakhi.data.riskassessment.RiskAssessmentEntity
import org.armman.sakhi.data.riskassessment.RiskFlagEntity
import org.armman.sakhi.data.referral.ReferralLinkEntity

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
 *  - v6: [FormAuditEventEntity] (CR-035 capture-only local audit trail — form open/save/submit
 *    events). Additive [MIGRATION_5_6] — creates `form_audit_events` plus its one index, touches
 *    no existing table. No automated migration test, matching the existing convention for the
 *    three prior additive migrations (explicit team decision — no real users on the app yet).
 *  - v7: [AdHocFormDraftEntity] (ad-hoc-form offline sync — Referral / Referral Follow-up /
 *    ANC Closure / Child Closure / Beneficiary Reopen). Additive [MIGRATION_6_7] — creates
 *    `ad_hoc_form_drafts` only, touches no existing table. Same no-automated-migration-test
 *    convention as v4/v5/v6.
 *  - v8: [DeliverySessionEntity] (CR-042 Delivery Event Session resume state). Additive
 *    [MIGRATION_7_8] — creates `delivery_sessions` only, touches no existing table. Not a sync
 *    queue (see the entity's own doc) so, unlike v2-v7, it has no `syncStatus` column. Same
 *    no-automated-migration-test convention as v4/v5/v6/v7.
 *  - v9: [DeliveryFormDraftEntity] (CR-042 `DELIVERY_VISIT` submission queue). Additive
 *    [MIGRATION_8_9] — creates `delivery_form_drafts` only, touches no existing table. A real sync
 *    queue like v2-v7's tables (has `syncStatus`), distinct from v8's `delivery_sessions` (a resume
 *    pointer with no sync status of its own) — see the entity's own doc for why this is a separate
 *    table rather than reusing `ad_hoc_form_drafts`.
 *  - v10: [DeliveryChildRegistrationDraftEntity] (CR-042 delivery-session `CHILD_REGISTRATION`
 *    submission queue, for a child already auto-created by `DELIVERY_VISIT`). Additive
 *    [MIGRATION_9_10] — creates `delivery_child_registration_drafts` only, touches no existing
 *    table. Deliberately separate from both `delivery_form_drafts` (a different form/step) and the
 *    standalone `child_registration_drafts` (same form code, but that table's primary key IS the
 *    `localCaseUuid` sent to `POST /beneficiaries` — a call this flow never makes, since the child
 *    beneficiary already exists) — see the entity's own doc.
 *  - v11: [DeliverySessionEntity.deliveryFormFilledOn] (CR-042 PP1→NN/DONE step advancement).
 *    Additive [MIGRATION_10_11] — this is the first migration in this database that `ALTER
 *    TABLE`s an *existing* table rather than creating a new one: it adds one nullable
 *    `deliveryFormFilledOn` column to `delivery_sessions` so [org.armman.sakhi.data.visitform
 *    .VisitFormSubmissionCoordinator] can ask [org.armman.sakhi.data.schedule.sameSessionNnVisit]
 *    whether a same-session NN visit exists, without re-deriving the delivery form's own fill date
 *    from anywhere else. Every existing row is upgraded with this column `NULL` (SQLite's ALTER
 *    TABLE ADD COLUMN default when none is specified) — safe because every such row is either
 *    already [DeliverySessionStep.DONE] or predates this feature reaching PP1 in the field (no
 *    real users on the app yet, same standing team decision as v4-v10). No automated migration
 *    test for this one either.
 *  - v12: [VisitFormDraftEntity.localSubmissionUuid] (client-side fix so a resumed visit-form
 *    submission retry replays the same idempotency key on `POST /forms/:formCode/submissions`
 *    instead of minting a fresh one — see the field's own doc and
 *    [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator]). Additive [MIGRATION_11_12]
 *    — `ALTER TABLE`s the existing `visit_form_drafts` table, adding one `NOT NULL DEFAULT ''`
 *    `localSubmissionUuid` column (SQLite requires a non-null default to add a `NOT NULL` column
 *    to a table that may already have rows). Same standing team decision as v4-v11 — no real users
 *    on the app yet, so the placeholder `''` a pre-existing queued row would upgrade with is not
 *    backfilled with a real uuid; no automated migration test for this one either.
 *  - v13: [DeliverySessionEntity.child1BirthOrder]/`child2BirthOrder`/`child3BirthOrder` (client-side
 *    fix for the reported "stillborn twin causes the live twin's registration to prefill with the
 *    dead twin's data" bug, 2026-08-26 — see that field's own doc for the full mechanism).
 *    Additive [MIGRATION_12_13] — `ALTER TABLE`s the existing `delivery_sessions` table, adding
 *    three nullable `INTEGER` columns. Every existing row upgrades with all three `NULL`
 *    (SQLite's default), which is the correct "unknown, fall back to the old positional
 *    assumption" state for any in-flight session that predates this fix — same standing team
 *    decision as v4-v12, no automated migration test.
 *  - v14: [ReferralLinkEntity] (CR-Referral-01 local referral-link cache — lets the
 *    Beneficiary Profile's visit card show the right action/chip without a network call; see
 *    the entity's own doc for why it is keyed by `localScheduleUuid`). Additive
 *    [MIGRATION_13_14] — creates `referral_links` only, touches no existing table. Same
 *    no-automated-migration-test convention as v4-v13.
 *  - v15: [RiskAssessmentEntity]/[RiskFlagEntity] (punch-list items 1/2, 2026-08-27 — local
 *    persistence of `POST /risk-assessments` responses per visit submission, mirroring the
 *    server's `risk_assessments`/`risk_flags` tables). Additive [MIGRATION_14_15] — creates
 *    `risk_assessments` and `risk_flags` (plus its one index) only, touches no existing table.
 *    Same no-automated-migration-test convention as v4-v14.
 *  - v16: [EnrollmentRiskBaselineEntity] (punch-list item 6, 2026-08-28 — a frozen, one-time
 *    snapshot of [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment]'s result at
 *    registration submission; does NOT change the live Beneficiaries-list badge, which still
 *    recomputes on read — see that entity's own doc). Additive [MIGRATION_15_16] — creates
 *    `enrollment_risk_baselines` only, touches no existing table. Same no-automated-migration-test
 *    convention as v4-v15.
 */
@Database(
  entities = [
    EnrollmentDraftEntity::class,
    DynamicFormDraftEntity::class,
    ChildFormDraftEntity::class,
    VisitScheduleEntity::class,
    VisitFormDraftEntity::class,
    FormAuditEventEntity::class,
    AdHocFormDraftEntity::class,
    DeliverySessionEntity::class,
    DeliveryFormDraftEntity::class,
    DeliveryChildRegistrationDraftEntity::class,
    ReferralLinkEntity::class,
    RiskAssessmentEntity::class,
    RiskFlagEntity::class,
    EnrollmentRiskBaselineEntity::class,
  ],
  version = 16,
  exportSchema = true,
)
@TypeConverters(ScheduleTypeConverters::class)
abstract class SakhiDatabase : RoomDatabase() {
  abstract fun enrollmentDraftDao(): EnrollmentDraftDao
  abstract fun dynamicFormDraftDao(): DynamicFormDraftDao
  abstract fun childFormDraftDao(): ChildFormDraftDao
  abstract fun visitScheduleDao(): VisitScheduleDao
  abstract fun visitFormDraftDao(): VisitFormDraftDao
  abstract fun formAuditEventDao(): FormAuditEventDao
  abstract fun adHocFormDraftDao(): AdHocFormDraftDao
  abstract fun deliverySessionDao(): DeliverySessionDao
  abstract fun deliveryFormDraftDao(): DeliveryFormDraftDao
  abstract fun deliveryChildRegistrationDraftDao(): DeliveryChildRegistrationDraftDao
  abstract fun referralLinkDao(): ReferralLinkDao
  abstract fun riskAssessmentDao(): RiskAssessmentDao
  abstract fun enrollmentRiskBaselineDao(): EnrollmentRiskBaselineDao

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

    /**
     * v5 → v6: adds the CR-035 `form_audit_events` table and its one index. Purely additive — no
     * existing table is touched, so every queue's rows survive the upgrade untouched.
     *
     * Column definitions must match [FormAuditEventEntity] exactly or Room's schema validation
     * fails at open time. `eventType` is stored as TEXT by enum name, same convention as every
     * other enum column in this database. `id` is an autoGenerate primary key, so it's declared
     * `INTEGER PRIMARY KEY AUTOINCREMENT` per Room's own convention for that combination.
     *
     * No automated migration test for this one (explicit team decision — no real users on the app
     * yet, and none of the three prior additive migrations above were automated-tested either).
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `form_audit_events` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`subjectId` TEXT NOT NULL, " +
            "`formCode` TEXT NOT NULL, " +
            "`eventType` TEXT NOT NULL, " +
            "`timestampEpochMillis` INTEGER NOT NULL, " +
            "`performedBySakhiId` TEXT)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_form_audit_events_subjectId_formCode` " +
            "ON `form_audit_events` (`subjectId`, `formCode`)",
        )
      }
    }

    /**
     * v6 → v7: adds the ad-hoc-form `ad_hoc_form_drafts` table. Purely additive — no existing
     * table is touched, so every other queue's rows survive the upgrade untouched.
     *
     * Column definitions must match [AdHocFormDraftEntity] exactly or Room's schema validation
     * fails at open time. `syncStatus` is stored as TEXT by enum name, same convention as every
     * other enum column in this database.
     *
     * No automated migration test for this one either (same explicit team decision as v4/v5/v6).
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `ad_hoc_form_drafts` (" +
            "`localFormInstanceUuid` TEXT NOT NULL, " +
            "`localBeneficiaryId` TEXT NOT NULL, " +
            "`formCode` TEXT NOT NULL, " +
            "`formVersionId` TEXT NOT NULL, " +
            "`syncStatus` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`lastAttemptAtEpochMillis` INTEGER, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`serverSubmissionId` TEXT, " +
            "`lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`localFormInstanceUuid`))",
        )
      }
    }

    /**
     * v7 → v8: adds the CR-042 `delivery_sessions` table. Purely additive — no existing table is
     * touched, so every other queue's rows (including any in-flight `DELIVERY_VISIT`/
     * `CHILD_REGISTRATION`/`POSTPARTUM_VISIT`/`NEONATAL_VISIT` draft) survive the upgrade
     * untouched. Column definitions must match [DeliverySessionEntity] exactly or Room's schema
     * validation fails at open time. `step` is stored as TEXT by enum name, same convention as
     * every other enum column in this database; the three child-id columns and
     * `nextChildIndexToRegister` are plain nullable/INTEGER columns, no new
     * [androidx.room.TypeConverter] needed (see the entity's own doc for why not a list column).
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `delivery_sessions` (" +
            "`localSessionUuid` TEXT NOT NULL, " +
            "`localBeneficiaryId` TEXT NOT NULL, " +
            "`step` TEXT NOT NULL, " +
            "`deliverySubmissionLocalUuid` TEXT, " +
            "`child1BeneficiaryId` TEXT, " +
            "`child2BeneficiaryId` TEXT, " +
            "`child3BeneficiaryId` TEXT, " +
            "`nextChildIndexToRegister` INTEGER NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`updatedAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`localSessionUuid`))",
        )
      }
    }

    /**
     * v8 → v9: adds the CR-042 `delivery_form_drafts` table — the `DELIVERY_VISIT` submission
     * queue ([DeliveryFormDraftEntity]). Purely additive — no existing table is touched, so every
     * other queue's rows (including `delivery_sessions` itself) survive the upgrade untouched.
     * Column definitions must match [DeliveryFormDraftEntity] exactly or Room's schema validation
     * fails at open time. `syncStatus` is stored as TEXT by enum name, same convention as every
     * other enum column in this database. No automated migration test for this one either (same
     * explicit team decision as v4/v5/v6/v7).
     */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `delivery_form_drafts` (" +
            "`localSubmissionUuid` TEXT NOT NULL, " +
            "`localBeneficiaryId` TEXT NOT NULL, " +
            "`localSessionUuid` TEXT NOT NULL, " +
            "`formVersionId` TEXT NOT NULL, " +
            "`syncStatus` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`lastAttemptAtEpochMillis` INTEGER, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`serverSubmissionId` TEXT, " +
            "`lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`localSubmissionUuid`))",
        )
      }
    }

    /**
     * v9 → v10: adds the CR-042 `delivery_child_registration_drafts` table — the delivery-session
     * `CHILD_REGISTRATION` submission queue ([DeliveryChildRegistrationDraftEntity]), for a child
     * already auto-created by `DELIVERY_VISIT`. Purely additive — no existing table is touched, so
     * every other queue's rows survive the upgrade untouched. Column definitions must match
     * [DeliveryChildRegistrationDraftEntity] exactly or Room's schema validation fails at open
     * time. `syncStatus` is stored as TEXT by enum name, same convention as every other enum
     * column in this database. No automated migration test for this one either (same explicit
     * team decision as v4/v5/v6/v7/v9).
     */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `delivery_child_registration_drafts` (" +
            "`localSubmissionUuid` TEXT NOT NULL, " +
            "`localSessionUuid` TEXT NOT NULL, " +
            "`serverBeneficiaryId` TEXT NOT NULL, " +
            "`formVersionId` TEXT NOT NULL, " +
            "`syncStatus` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`lastAttemptAtEpochMillis` INTEGER, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`localSubmissionUuid`))",
        )
      }
    }

    /**
     * v10 → v11: adds the [DeliverySessionEntity.deliveryFormFilledOn] column to the existing
     * `delivery_sessions` table. The first `ALTER TABLE` migration in this database — every prior
     * bump only ever `CREATE TABLE`d a new table, so this is additive in the same spirit (no
     * existing table is dropped or rewritten) but not the same shape as v2-v10. Stored as TEXT
     * ISO-8601 via [ScheduleTypeConverters], same convention as every other [java.time.LocalDate]
     * column in this database. No `DEFAULT` clause — SQLite leaves existing rows `NULL` for a
     * column added without one, which matches the field's own nullable contract (see the entity's
     * doc). No automated migration test for this one either (same explicit team decision as
     * v4-v10).
     */
    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `delivery_sessions` ADD COLUMN `deliveryFormFilledOn` TEXT",
        )
      }
    }

    /**
     * v11 → v12: adds the [VisitFormDraftEntity.localSubmissionUuid] column to the existing
     * `visit_form_drafts` table. `NOT NULL DEFAULT ''` because SQLite requires a non-null default
     * to add a `NOT NULL` column to a table that may already have rows — see the field's own doc
     * for why every *new* save always gets a real uuid from here on regardless.
     */
    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `visit_form_drafts` ADD COLUMN `localSubmissionUuid` TEXT NOT NULL DEFAULT ''",
        )
      }
    }

    /**
     * v12 → v13: adds [DeliverySessionEntity.child1BirthOrder]/`child2BirthOrder`/`child3BirthOrder`
     * to the existing `delivery_sessions` table — see that field's own doc for why this is needed
     * alongside the existing `child1BeneficiaryId` etc. columns (compacted-position vs. real
     * birth-order slot).
     */
    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `delivery_sessions` ADD COLUMN `child1BirthOrder` INTEGER")
        db.execSQL("ALTER TABLE `delivery_sessions` ADD COLUMN `child2BirthOrder` INTEGER")
        db.execSQL("ALTER TABLE `delivery_sessions` ADD COLUMN `child3BirthOrder` INTEGER")
      }
    }

    /**
     * v13 → v14: adds the CR-Referral-01 `referral_links` table. Purely additive — no existing
     * table is touched, so every other queue's rows survive the upgrade untouched.
     *
     * Column definitions must match [ReferralLinkEntity] exactly or Room's schema validation
     * fails at open time. `status`/`referralType` are stored as TEXT by enum name, same
     * convention as every other enum column in this database. No automated migration test for
     * this one either (same explicit team decision as v4-v13).
     */
    val MIGRATION_13_14: Migration = object : Migration(13, 14) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `referral_links` (" +
            "`localScheduleUuid` TEXT NOT NULL, " +
            "`referralId` TEXT NOT NULL, " +
            "`visitId` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`referralTypeLookupValueId` TEXT NOT NULL, " +
            "`validTill` TEXT, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`localScheduleUuid`))",
        )
      }
    }

    /**
     * v14 → v15: adds the `risk_assessments` and `risk_flags` tables (punch-list items 1/2,
     * 2026-08-27). Purely additive — no existing table is touched, so every other queue's/cache's
     * rows survive the upgrade untouched.
     *
     * Column definitions must match [RiskAssessmentEntity]/[RiskFlagEntity] exactly or Room's
     * schema validation fails at open time. No enum columns here (both `overallRiskCategory` and
     * `riskGradeLookupValueId` are raw TEXT — see each entity's own doc for why). No automated
     * migration test for this one either (same explicit team decision as v4-v14).
     */
    val MIGRATION_14_15: Migration = object : Migration(14, 15) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `risk_assessments` (" +
            "`localScheduleUuid` TEXT NOT NULL, " +
            "`serverAssessmentId` TEXT NOT NULL, " +
            "`beneficiaryId` TEXT NOT NULL, " +
            "`visitId` TEXT, " +
            "`submissionId` TEXT NOT NULL, " +
            "`ruleVersionId` TEXT NOT NULL, " +
            "`evaluatedAt` TEXT NOT NULL, " +
            "`overallRiskCategory` TEXT NOT NULL, " +
            "`overallHighRiskFlag` INTEGER NOT NULL, " +
            "`hrDetectedFlag` INTEGER NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`localScheduleUuid`))",
        )
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `risk_flags` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`localScheduleUuid` TEXT NOT NULL, " +
            "`serverFlagId` TEXT NOT NULL, " +
            "`riskConditionId` TEXT NOT NULL, " +
            "`riskGradeLookupValueId` TEXT NOT NULL, " +
            "`observedValueJson` TEXT, " +
            "`isReferralTrigger` INTEGER NOT NULL, " +
            "`isEducationTrigger` INTEGER NOT NULL, " +
            "`isHrVisitTrigger` INTEGER NOT NULL)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_risk_flags_localScheduleUuid` " +
            "ON `risk_flags` (`localScheduleUuid`)",
        )
      }
    }

    /**
     * v15 → v16: adds the `enrollment_risk_baselines` table (punch-list item 6, 2026-08-28).
     * Purely additive — no existing table is touched.
     *
     * Column definitions must match [EnrollmentRiskBaselineEntity] exactly or Room's schema
     * validation fails at open time. `overallRiskLevel` is TEXT by enum name; `findingsJson` is a
     * plain Gson-serialized TEXT blob, no `@TypeConverter` needed. No automated migration test for
     * this one either (same explicit team decision as v4-v15).
     */
    val MIGRATION_15_16: Migration = object : Migration(15, 16) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `enrollment_risk_baselines` (" +
            "`localBeneficiaryId` TEXT NOT NULL, " +
            "`overallRiskLevel` TEXT NOT NULL, " +
            "`findingsJson` TEXT NOT NULL, " +
            "`computedAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`localBeneficiaryId`))",
        )
      }
    }
  }
}
