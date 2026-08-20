package org.armman.sakhi.data.delivery

/**
 * Where a Delivery Event Session (CR-042) currently stands. Stored as TEXT by name on
 * [DeliverySessionEntity.step], same convention as every other enum column in this database.
 *
 * Steps are strictly forward-moving — nothing in this session ever regresses a beneficiary from a
 * later step back to an earlier one. [DONE] is terminal: [DeliverySessionDao.getActiveForBeneficiary]
 * excludes it, so a finished session never resurfaces as resumable.
 */
enum class DeliverySessionStep {
  /** The `DELIVERY_VISIT` form itself has not yet been submitted. Entry state for a brand new
   * session — a row only exists in this state if the Sakhi started the session and left before
   * submitting, since the row is written *at* submission, not before (see
   * [DeliverySessionRepository] doc for why there is nothing to resume before that point). */
  DELIVERY_FORM,

  /** `DELIVERY_VISIT` submitted; at least one live-born child still needs its registration form
   * completed. [DeliverySessionEntity.nextChildIndexToRegister] says which one. */
  CHILD_REGISTRATION,

  /** All live-born children (if any) are registered; the mother's PP1 form has not yet been
   * submitted. */
  PP1,

  /** PP1 submitted; the same-session NN visit (NN1 or NN2, or neither — see
   * [org.armman.sakhi.data.schedule.sameSessionNnVisit]) has not yet been submitted. */
  NN,

  /** Every applicable step for this delivery is complete. Terminal. */
  DONE,
}
