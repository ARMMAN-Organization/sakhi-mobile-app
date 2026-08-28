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
 * lost enrolment means an untracked pregnancy. Returns the number of visits generated, or 0.
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
  ): Int = runCatching {
    val lmp = answers.lmpDate()
    if (lmp == null) {
      // Distinct from a thrown exception below: this is the expected "no LMP captured" path, not
      // a failure. Logged at WARN (not ERROR) so it doesn't look like a crash, but still shows up
      // if a real enrolment is unexpectedly missing this answer.
      Log.w(TAG, "generateFor($localBeneficiaryId): no LMP in answers, skipping schedule generation")
      return 0
    }
    // EDD is a computed field on the form, so it may not be present in `answers` at all. Deriving
    // it from the LMP here uses the same rule the form's own evaluator does.
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

  /** Answers store dates as ISO strings; a malformed one reads as absent rather than throwing. */
  private fun FormAnswers.lmpDate(): LocalDate? =
    valueOf(LMP_DATE_QUESTION_CODE)
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
