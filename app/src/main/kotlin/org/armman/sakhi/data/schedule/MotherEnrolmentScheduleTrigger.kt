package org.armman.sakhi.data.schedule

import android.util.Log
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.LMP_DATE_QUESTION_CODE
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a submitted mother-registration form into a [ScheduleContext] and generates her ANC
 * schedule (SRS FR-S-2.2).
 *
 * A separate class rather than logic inside the draft repository, for two reasons: the repository
 * has no business knowing which `question_code` holds the LMP, and this way the mapping from form
 * answers to scheduling inputs is unit-testable on its own.
 *
 * ### Failure never blocks the enrolment
 * [generateFor] swallows its own errors. A malformed LMP or a Room failure must not lose a
 * registration the Sakhi has already completed — she can always be given a schedule later, but a
 * lost enrolment means an untracked pregnancy. Returns the number of ANC visits generated, or 0.
 *
 * ### 2026-09-11 — enrolment-time baseline HR visit generation removed
 * This class used to also call [VisitScheduleCoordinator.onEnrollmentHighRiskDetected] to
 * pre-generate an ANC-HR1 visit straight from registration answers (e.g. age/obstetric-history
 * conditions), added 2026-09-02. Removed per explicit product decision 2026-09-11: a baseline
 * finding still sets the beneficiary's High Risk badge (unchanged — see
 * [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment] via
 * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource], which is independent of
 * this class), but no HR visit is generated until it is actually re-detected during a real ANC
 * visit. That happens automatically: [org.armman.sakhi.data.visitform.AncRiskRegistrationResolver]
 * already merges these same registration fields (age, gravida, living children, abortions, prior
 * complications) into every ANC1 GoRules evaluation, so the referral and (if still HIGH) the HR
 * visit both now originate from the ANC1 visit itself, correctly anchored to its actual completion
 * date rather than to the registration date. See delivery-log.md 2026-09-11 for the full
 * reasoning and SRS discussion this was decided against.
 */
private const val TAG = "MotherEnrolScheduleTrigger"

@Singleton
class MotherEnrolmentScheduleTrigger @Inject constructor(
  private val coordinator: VisitScheduleCoordinator,
  private val ruleSource: ScheduleRuleSource,
) {

  suspend fun generateFor(
    localBeneficiaryId: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Int {
    return runCatching {
      val lmp = answers.lmpDate()
      if (lmp == null) {
        // Distinct from a thrown exception below: this is the expected "no LMP captured" path,
        // not a failure. Logged at WARN (not ERROR) so it doesn't look like a crash, but still
        // shows up if a real enrolment is unexpectedly missing this answer.
        Log.w(TAG, "generateFor($localBeneficiaryId): no LMP in answers, skipping schedule generation")
        return 0
      }
      // EDD is a computed field on the form, so it may not be present in `answers` at all.
      // Deriving it from the LMP here uses the same rule the form's own evaluator does.
      val edd = lmp.plusDays(ruleSource.eddOffsetDays().toLong())

      coordinator.onMotherEnrolled(
        ScheduleContext(
          localBeneficiaryId = localBeneficiaryId,
          registrationDate = registrationDate,
          lmp = lmp,
          edd = edd,
        ),
      )
    }.onFailure { error ->
      // Previously swallowed with no trace at all (getOrDefault alone) — this is exactly the blind
      // spot that let the dashboard/motherlink/previsithealth release-only regressions (see
      // proguard-rules.pro) go unnoticed for a while. Logging here doesn't change the "never block
      // enrolment" behaviour below, it just makes a future instance of this bug diagnosable from
      // logcat instead of invisible.
      Log.e(TAG, "generateFor($localBeneficiaryId): schedule generation failed, enrolment still saved", error)
    }.getOrDefault(0)
  }

  /** Answers store dates as ISO strings; a malformed one reads as absent rather than throwing. */
  private fun FormAnswers.lmpDate(): LocalDate? =
    valueOf(LMP_DATE_QUESTION_CODE)
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
