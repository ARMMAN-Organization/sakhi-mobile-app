package org.armman.sakhi.data.schedule

import org.armman.sakhi.data.delivery.DeliverySessionEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [childBeneficiaryId] + the specific NN [visit] due for that child right now, per
 * [SameSessionNnVisitResolver.resolve].
 */
data class SameSessionNnVisitMatch(
  val childBeneficiaryId: String,
  val visit: VisitScheduleEntity,
)

/**
 * Resolves which (if any) of a Delivery Event Session's registered children has a same-session NN
 * visit due right now (CR-042 / CR-Delivery-01).
 *
 * Single source of truth for "which same-session NN visit is due" -- previously duplicated in two
 * places that quietly disagreed:
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.hasSameSessionNnVisit] queried
 * NN rows under the MOTHER's own beneficiary id ([DeliverySessionEntity.localBeneficiaryId]),
 * which can never find anything -- NN is generated under the CHILD's own beneficiary id, see
 * [VisitScheduleCoordinator.onChildRegistered]'s doc -- so the delivery session's step never
 * actually advanced to [org.armman.sakhi.data.delivery.DeliverySessionStep.NN] at all. Meanwhile
 * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel.resolveNnResumeVisit]
 * already had the correct child-id-based lookup (fixed 2026-08-21, per that function's own doc).
 * One bug, two call sites, only one got patched. This class replaces both call sites' own copies
 * so the same defect can't recur in only one of them again.
 *
 * Resolves per child in registration order (child1, then child2, then child3) and returns the
 * FIRST child with a match, rather than pooling every child's NN rows together and calling
 * [sameSessionNnVisit] once across all of them -- a twin/triplet delivery can have more than one
 * child's NN1 due on the same delivery-form-fill date, and [sameSessionNnVisit]'s `singleOrNull`
 * would silently resolve to "no match" (ambiguous) for exactly that case. Per-child resolution in
 * birth order at least always hands off to one of them, deterministically, instead of dropping
 * every twin's hand-off. Confirmed with bharath (CR-Delivery-01) as the intended behavior rather
 * than a stricter "must be unambiguous across the whole family" reading.
 */
@Singleton
class SameSessionNnVisitResolver @Inject constructor(
  private val visitScheduleRepository: VisitScheduleRepository,
) {
  /**
   * The child + [VisitScheduleEntity] due right now for [session], or null if no registered child
   * has a matching open NN row. Null (with no lookup at all) when
   * [DeliverySessionEntity.deliveryFormFilledOn] is null -- only possible on a session row that
   * predates the v11 migration and never advanced past
   * [org.armman.sakhi.data.delivery.DeliverySessionStep.DELIVERY_FORM] in the field, same
   * fallback [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator] already used.
   */
  suspend fun resolve(session: DeliverySessionEntity): SameSessionNnVisitMatch? {
    val deliveryFormFilledOn = session.deliveryFormFilledOn ?: return null
    val childBeneficiaryIds = listOfNotNull(
      session.child1BeneficiaryId,
      session.child2BeneficiaryId,
      session.child3BeneficiaryId,
    )
    childBeneficiaryIds.forEach { childBeneficiaryId ->
      val openNnVisits = visitScheduleRepository.getOpenByType(childBeneficiaryId, VisitCodeType.NN)
      val match = sameSessionNnVisit(openNnVisits, deliveryFormFilledOn)
      if (match != null) {
        return SameSessionNnVisitMatch(childBeneficiaryId = childBeneficiaryId, visit = match)
      }
    }
    return null
  }

  /** True if [resolve] would find a match -- all
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.advanceDeliverySessionIfDue]
   * needs for its step transition, without the child id/schedule row the UI hand-off additionally
   * needs. */
  suspend fun hasMatch(session: DeliverySessionEntity): Boolean = resolve(session) != null
}
