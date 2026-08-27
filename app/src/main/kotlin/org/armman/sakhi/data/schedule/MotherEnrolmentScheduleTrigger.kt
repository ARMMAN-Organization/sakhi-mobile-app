package org.armman.sakhi.data.schedule

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
    val lmp = answers.lmpDate() ?: return 0
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
  }.getOrDefault(0)

  /** Answers store dates as ISO strings; a malformed one reads as absent rather than throwing. */
  private fun FormAnswers.lmpDate(): LocalDate? =
    valueOf(LMP_DATE_QUESTION_CODE)
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
