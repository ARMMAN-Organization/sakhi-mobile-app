package org.armman.sakhi.data.schedule

import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a submitted child-registration form into a [ScheduleContext] and generates the infant's
 * INC schedule (SRS FR-S-2.2A). The child twin of [MotherEnrolmentScheduleTrigger] — same shape,
 * same rationale for being a separate class rather than logic inside the draft repository.
 *
 * ### Delivery details are always absent here
 * [ScheduleContext.deliveryDate]/[ScheduleContext.deliveryFormFilledOn] are never set by this
 * trigger: CHILD_REGISTRATION does not collect delivery details (that lives in the Delivery Form,
 * not yet built — see `docs/claude-context.md`). [VisitScheduleCoordinator.onChildRegistered]
 * therefore always takes its "no delivery details yet" branch and generates the INC series only;
 * the NN series is left for whichever of [VisitScheduleCoordinator.onChildRegistered] or
 * [VisitScheduleCoordinator.onDeliveryRecorded] runs once the delivery flow exists and fires
 * second — the per-family idempotency guard in [VisitScheduleCoordinator] makes that safe.
 *
 * ### Failure never blocks the enrolment
 * Mirrors [MotherEnrolmentScheduleTrigger.generateFor]: a malformed DOB or a Room failure must not
 * lose a registration the Sakhi has already completed — she can always be given a schedule later,
 * but a lost enrolment means an untracked child. Returns the number of visits generated, or 0.
 */
@Singleton
class ChildEnrolmentScheduleTrigger @Inject constructor(
  private val coordinator: VisitScheduleCoordinator,
) {

  suspend fun generateFor(
    localBeneficiaryId: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Int = runCatching {
    val dob = answers.dateOfBirth() ?: return 0

    coordinator.onChildRegistered(
      ScheduleContext(
        localBeneficiaryId = localBeneficiaryId,
        registrationDate = registrationDate,
        dob = dob,
      ),
    )
  }.getOrDefault(0)

  /** Answers store dates as ISO strings; a malformed one reads as absent rather than throwing. */
  private fun FormAnswers.dateOfBirth(): LocalDate? =
    valueOf(ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT)
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
