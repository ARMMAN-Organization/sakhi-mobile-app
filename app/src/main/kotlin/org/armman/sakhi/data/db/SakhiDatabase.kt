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
import org.armman.sakhi.data.referral.ReferralEvidenceDao
import org.armman.sakhi.data.referral.ReferralEvidenceMediaEntity

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
 *  - v17: [ReferralEvidenceMediaEntity] (CR-Referral-02 offline evidence-media queue —
 *    health facility photo, beneficiary photo, case paper, discharge summary, investigation
 *    report; one row per captured file). Additive [MIGRATION_16_17] — creates
 *    `referral_evidence_media` only, touches no existing table. Same
 *    no-automated-migration-test convention as v4-v16.
 *  - v18: [ReferralLinkEntity.facilityName]/`facilityType` (CR-Referral-02 — cached so the
 *    follow-up screen's Step 1 review can show what was recorded at referral creation, with no
 *    `GET /referrals/{id}` endpoint to fetch it fresh). Additive [MIGRATION_17_18] — `ALTER
 *    TABLE`s the existing `referral_links` table, adding two `NOT NULL DEFAULT ''` TEXT
 *    columns (SQLite requires a non-null default to add a NOT NULL column to a table that may
 *    already have rows) — same pattern as v12's `localSubmissionUuid`. No automated migration
 *    test for this one either (same explicit team decision as v4-v17).
 *  - v19: [ReferralEvidenceMediaEntity.followupId] (CR-Referral-02 — backend-confirmed real
 *    contract, 2026-08-31: media can only be finalized once the parent follow-up's real id is
 *    known, so every queued row now carries it, nullable until Submit succeeds). Additive
 *    [MIGRATION_18_19] — `ALTER TABLE`s the existing `referral_evidence_media` table, adding one
 *    nullable TEXT column. No automated migration test for this one either (same explicit team
 *    decision as v4-v18).
 *  - v20: [AdHocFormDraftEntity.referralId] / [ReferralEvidenceMediaEntity.submissionId]
 *    (CR-Referral-02/CR-Referral-01 — Referral Follow-up switched from the bespoke
 *    `POST /referrals/{id}/follow-up`-only screen to the schema-driven ad-hoc form pipeline;
 *    see [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s `REFERRAL_FOLLOWUP_VISIT`
 *    branch). [AdHocFormDraftEntity.referralId] lets a queued/retried ad-hoc submission still
 *    know which referral to transition on sync. [ReferralEvidenceMediaEntity.submissionId] is the
 *    new link key for evidence captured via the ad-hoc form's native `image` fields (the generic
 *    submission's own id is known synchronously, unlike the old `followupId` two-phase stamp) —
 *    [ReferralEvidenceMediaEntity.followupId] is kept, now unused by new rows, for the handful of
 *    already-queued rows from the retired bespoke screen. Additive [MIGRATION_19_20] — two
 *    `ALTER TABLE`s, both nullable TEXT columns. No automated migration test (same convention as
 *    v4-v19).
 *  - v21: adds `referralVisitName` to the existing `referral_links` table (CR-Referral-01,
 *    2026-09-02) — so the Referral Follow-up form can autopopulate its own "Referral visit name"
 *    question from the parent referral's own captured name (see [ReferralLinkEntity
 *    .referralVisitName]'s doc), the same "cache it at create time, read it back with no network
 *    call" pattern `facilityName`/`facilityType` already established in v18. Additive
 *    [MIGRATION_20_21] — one `ALTER TABLE`, one NOT NULL TEXT column defaulting to `''` (same
 *    blank-not-null convention as v18's two columns). No automated migration test (same
 *    convention as v4-v20).
 *  - v22: adds `decidedByUserId`/`decidedAt`/`decisionNotes` to the existing `referral_links`
 *    table (Task 8, LMP/Reopen/Referral/Audit task list) — mirrors a Supervisor's REFILL decision
 *    on a referral follow-up, refreshed from `GET /referrals?beneficiaryId=` (see
 *    [org.armman.sakhi.data.referral.RemoteReferralRepository.refreshReferralStatuses]'s doc).
 *    Additive [MIGRATION_21_22] — three `ALTER TABLE`s, all nullable TEXT columns (unlike prior
 *    referral_links columns, these have no safe non-null default — "no decision yet" is a real
 *    null, not blank string). No automated migration test (same convention as v4-v21).

 *  - v23: adds `beneficiaryId` to the existing `referral_links` table (CR-Referral-01, in-visit
 *    "Visit name"/"Referral visit name" autopopulation fix) — lets [ReferralLinkDao
 *    .countByBeneficiaryId] count a beneficiary's past referrals on this device so
 *    [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel]'s in-visit Referral capture step
 *    can label a new one "RV{n+1}", the same auto-numbering the standalone ad-hoc Referral form
 *    already does. Additive [MIGRATION_22_23] — one `ALTER TABLE`, one NOT NULL TEXT column
 *    defaulting to `''` (same blank-not-null convention as v18/v21). No automated migration test
 *    (same convention as v4-v22).
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
    ReferralEvidenceMediaEntity::class,
  ],
  version = 23,
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
  abstract fun referralEvidenceDao(): ReferralEvidenceDao

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
    /**
     * v16 → v17: adds the CR-Referral-02 `referral_evidence_media` table. Purely additive — no
     * existing table is touched.
     *
     * Column definitions must match [ReferralEvidenceMediaEntity] exactly or Room's schema
     * validation fails at open time. `evidenceType`/`syncStatus` are TEXT by enum name, same
     * convention as every other queue. No automated migration test for this one either (same
     * explicit team decision as v4-v16).
     */
    val MIGRATION_16_17: Migration = object : Migration(16, 17) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `referral_evidence_media` (" +
            "`localMediaUuid` TEXT NOT NULL, " +
            "`referralId` TEXT NOT NULL, " +
            "`evidenceType` TEXT NOT NULL, " +
            "`localFilePath` TEXT NOT NULL, " +
            "`syncStatus` TEXT NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, " +
            "`lastAttemptAtEpochMillis` INTEGER, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`remoteMediaId` TEXT, " +
            "`lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`localMediaUuid`))",
        )
      }
    }

    /**
     * v17 → v18: adds `facilityName`/`facilityType` to the existing `referral_links` table
     * (CR-Referral-02). Every existing row upgrades with both columns as `''` — acceptable
     * because no real users are on the app yet (same standing decision as v4-v17), and a blank
     * value just means Step 1's review card shows nothing for a referral cached before this
     * migration rather than crashing.
     */
    val MIGRATION_17_18: Migration = object : Migration(17, 18) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `facilityName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `facilityType` TEXT NOT NULL DEFAULT ''")
      }
    }

    /**
     * v18 → v19: adds a nullable `followupId` column to `referral_evidence_media` (CR-Referral-02,
     * backend-confirmed real contract, 2026-08-31). Purely additive, defaults to NULL for every
     * existing row — correct, since a row from before this migration was captured before the
     * concept of stamping a real follow-up id existed, and [ReferralEvidenceDao.getPendingSync]
     * treats a null [ReferralEvidenceMediaEntity.followupId] as "not yet eligible to upload"
     * rather than a data error.
     */
    val MIGRATION_18_19: Migration = object : Migration(18, 19) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `referral_evidence_media` ADD COLUMN `followupId` TEXT")
      }
    }

    /**
     * v19 → v20: adds a nullable `referralId` column to `ad_hoc_form_drafts` and a nullable
     * `submissionId` column to `referral_evidence_media` (Referral Follow-up's switch to the
     * ad-hoc form pipeline — see this class's own v20 doc). Both purely additive; every existing
     * row upgrades with `NULL`, which is correct (a pre-migration ad-hoc draft was never a
     * Referral Follow-up submission needing a referralId, and a pre-migration evidence row was
     * captured by the retired bespoke screen, which stamped `followupId` instead).
     */
    val MIGRATION_19_20: Migration = object : Migration(19, 20) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ad_hoc_form_drafts` ADD COLUMN `referralId` TEXT")
        db.execSQL("ALTER TABLE `referral_evidence_media` ADD COLUMN `submissionId` TEXT")
      }
    }

    /**
     * v20 → v21: adds `referralVisitName` to the existing `referral_links` table (CR-Referral-01,
     * 2026-09-02). Every existing row upgrades with `''` — same acceptable-blank convention as
     * v18's `facilityName`/`facilityType` columns (no real users on the app yet); a referral
     * cached before this migration just shows a blank "Referral visit name" on its Follow-up
     * form instead of crashing.
     */
    val MIGRATION_20_21: Migration = object : Migration(20, 21) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `referralVisitName` TEXT NOT NULL DEFAULT ''")
      }
    }

    /**
     * v21 -> v22: adds the Task 8 Supervisor-decision columns to `referral_links` — see the class
     * doc's v22 entry. All three nullable, no DEFAULT clause (SQLite defaults an added nullable
     * column with no explicit default to NULL, which is exactly "no decision yet").
     */
    val MIGRATION_21_22: Migration = object : Migration(21, 22) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `decidedByUserId` TEXT")
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `decidedAt` TEXT")
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `decisionNotes` TEXT")
      }
    }

    /**
     * v22 -> v23: adds `beneficiaryId` to the existing `referral_links` table — see the class
     * doc's v23 entry. Every existing row upgrades with `''`, same acceptable-blank convention as
     * v18/v21 (no real users on the app yet); a referral cached before this migration just never
     * counts toward any beneficiary's RV-numbering total.
     */
    val MIGRATION_22_23: Migration = object : Migration(22, 23) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `referral_links` ADD COLUMN `beneficiaryId` TEXT NOT NULL DEFAULT ''")
      }
    }

  }
}
