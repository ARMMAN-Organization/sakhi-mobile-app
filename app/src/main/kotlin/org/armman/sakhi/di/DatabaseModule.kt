package org.armman.sakhi.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.adhocform.AdHocFormDraftDao
import org.armman.sakhi.data.audit.FormAuditEventDao
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftDao
import org.armman.sakhi.data.delivery.DeliveryFormDraftDao
import org.armman.sakhi.data.delivery.DeliverySessionDao
import org.armman.sakhi.data.db.SakhiDatabase
import org.armman.sakhi.data.enrollment.EnrollmentDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentRiskBaselineDao
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.referral.ReferralEvidenceDao
import org.armman.sakhi.data.riskassessment.RiskAssessmentDao
import org.armman.sakhi.data.schedule.VisitScheduleDao
import org.armman.sakhi.data.visitform.VisitFormDraftDao
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
      // CR-020: v2→v3 ships a real additive migration (adds child_registration_drafts) so queued
      // drafts survive the upgrade. CR-022: v3→v4 likewise adds visit_schedules. CR-026b: v4→v5
      // likewise adds visit_form_drafts. CR-035: v5→v6 likewise adds form_audit_events. v6→v7
      // likewise adds ad_hoc_form_drafts. CR-042: v7→v8 likewise adds delivery_sessions;
      // v8→v9 likewise adds delivery_form_drafts; v9→v10 likewise adds
      // delivery_child_registration_drafts; v10→v11 adds the deliveryFormFilledOn column onto
      // the existing delivery_sessions table (CR-042 PP1→NN/DONE step advancement); v11→v12 adds
      // the localSubmissionUuid column onto the existing visit_form_drafts table (resumed visit
      // submissions now replay the same idempotency key instead of minting a fresh one); v12->v13
      // adds child1BirthOrder/child2BirthOrder/child3BirthOrder onto the existing delivery_sessions
      // table (fixes a stillborn twin's slot misaligning the next live twin's registration
      // prefill).
      // fallbackToDestructiveMigration() is retained only as the last-resort net for the
      // pre-release v1 DB (see SakhiDatabase's doc).
      .addMigrations(
        SakhiDatabase.MIGRATION_2_3,
        SakhiDatabase.MIGRATION_3_4,
        SakhiDatabase.MIGRATION_4_5,
        SakhiDatabase.MIGRATION_5_6,
        SakhiDatabase.MIGRATION_6_7,
        SakhiDatabase.MIGRATION_7_8,
        SakhiDatabase.MIGRATION_8_9,
        SakhiDatabase.MIGRATION_9_10,
        SakhiDatabase.MIGRATION_10_11,
        SakhiDatabase.MIGRATION_11_12,
        SakhiDatabase.MIGRATION_12_13,
        SakhiDatabase.MIGRATION_13_14,
        SakhiDatabase.MIGRATION_14_15,
        SakhiDatabase.MIGRATION_15_16,
        SakhiDatabase.MIGRATION_16_17,
        SakhiDatabase.MIGRATION_17_18,
        SakhiDatabase.MIGRATION_18_19,
        SakhiDatabase.MIGRATION_19_20,
      )
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
  fun provideChildFormDraftDao(database: SakhiDatabase): ChildFormDraftDao =
    database.childFormDraftDao()

  @Provides
  @Singleton
  fun provideVisitScheduleDao(database: SakhiDatabase): VisitScheduleDao =
    database.visitScheduleDao()

  @Provides
  @Singleton
  fun provideVisitFormDraftDao(database: SakhiDatabase): VisitFormDraftDao =
    database.visitFormDraftDao()

  @Provides
  @Singleton
  fun provideFormAuditEventDao(database: SakhiDatabase): FormAuditEventDao =
    database.formAuditEventDao()

  @Provides
  @Singleton
  fun provideAdHocFormDraftDao(database: SakhiDatabase): AdHocFormDraftDao =
    database.adHocFormDraftDao()

  @Provides
  @Singleton
  fun provideDeliverySessionDao(database: SakhiDatabase): DeliverySessionDao =
    database.deliverySessionDao()

  @Provides
  @Singleton
  fun provideDeliveryFormDraftDao(database: SakhiDatabase): DeliveryFormDraftDao =
    database.deliveryFormDraftDao()

  @Provides
  @Singleton
  fun provideDeliveryChildRegistrationDraftDao(database: SakhiDatabase): DeliveryChildRegistrationDraftDao =
    database.deliveryChildRegistrationDraftDao()

  @Provides
  @Singleton
  fun provideReferralLinkDao(database: SakhiDatabase): ReferralLinkDao =
    database.referralLinkDao()

  @Provides
  @Singleton
  fun provideReferralEvidenceDao(database: SakhiDatabase): ReferralEvidenceDao =
    database.referralEvidenceDao()

  @Provides
  @Singleton
  fun provideRiskAssessmentDao(database: SakhiDatabase): RiskAssessmentDao =
    database.riskAssessmentDao()

  @Provides
  @Singleton
  fun provideEnrollmentRiskBaselineDao(database: SakhiDatabase): EnrollmentRiskBaselineDao =
    database.enrollmentRiskBaselineDao()

  @Provides
  @Singleton
  fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
    WorkManager.getInstance(context)
}
