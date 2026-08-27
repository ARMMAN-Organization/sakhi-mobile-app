package org.armman.sakhi.data.enrollment

/**
 * What a `409 CONFLICT` from `POST /beneficiaries` actually means, per SRS FR-S-2.4/2.5.
 *
 * The backend returns the same status code for two very different situations, distinguished only by
 * a `reason` marker inside the error envelope's `fieldErrors` (see [DuplicateOutcomeParser]):
 *
 *  - [HardDuplicate] — this beneficiary is already enrolled and her journey is still open.
 *    FR-S-2.4 is explicit that "registration cannot proceed", so the app blocks it outright. The
 *    backend does expose an `acknowledgeDuplicate` escape hatch for this case; the app deliberately
 *    never uses it here (product decision), because doing so would let field staff create genuine
 *    duplicate records.
 *  - [NewPregnancyPrompt] — a *previous* pregnancy exists that has already been delivered and
 *    closed, and the LMP being enrolled is a different one. FR-S-2.5 wants the Sakhi asked to
 *    confirm, then the enrolment resent with `acknowledgeDuplicate` and a link back to the earlier
 *    case. Nothing about the earlier pregnancy is modified — a new case is created alongside it.
 *
 * Carries no user-facing copy on purpose. Both messages are shown from string resources so they
 * exist in English *and* Marathi, which a sentence taken from the backend never would.
 */
sealed interface DuplicateOutcome {

  /** Already enrolled, journey still open → blocked (FR-S-2.4). */
  data object HardDuplicate : DuplicateOutcome

  /**
   * A completed earlier pregnancy → ask the Sakhi to confirm (FR-S-2.5).
   *
   * [existingBeneficiaryId] is the server id of that earlier case, sent back as
   * `case.previousBeneficiaryId` on the confirmed resubmission so the two pregnancies stay linked.
   */
  data class NewPregnancyPrompt(val existingBeneficiaryId: String) : DuplicateOutcome
}

/**
 * Reads a `409` error envelope and decides which [DuplicateOutcome] it is.
 *
 * The re-enrolment marker arrives as `fieldErrors: { reason, existingBeneficiaryId, resolution }`.
 * That key is normally per-field validation copy, which is why these three entries must never be
 * rendered as such — `SubmitErrorCopy` filters them by [DETAIL_KEYS].
 *
 * Anything unexpected degrades to [DuplicateOutcome.HardDuplicate]: the safe direction is to block
 * a registration the Sakhi can escalate, never to silently create a second record off a marker we
 * did not fully understand.
 */
object DuplicateOutcomeParser {

  /** `fieldErrors.reason` value marking an FR-S-2.5 re-enrolment prompt. */
  const val REASON_RE_ENROLLMENT = "RE_ENROLLMENT"

  private const val KEY_REASON = "reason"
  private const val KEY_EXISTING_BENEFICIARY_ID = "existingBeneficiaryId"
  private const val KEY_RESOLUTION = "resolution"

  /**
   * The `fieldErrors` keys a duplicate `409` uses for machine-readable detail rather than per-field
   * validation messages. Shown to a Sakhi they read as leaked internals ("RE_ENROLLMENT",
   * "Resubmit with acknowledgeDuplicate: true…"), so every UI path must skip them.
   */
  val DETAIL_KEYS: Set<String> = setOf(KEY_REASON, KEY_EXISTING_BENEFICIARY_ID, KEY_RESOLUTION)

  fun parse(error: ApiError): DuplicateOutcome {
    if (!error.fieldErrors[KEY_REASON].equals(REASON_RE_ENROLLMENT, ignoreCase = true)) {
      return DuplicateOutcome.HardDuplicate
    }
    // Without the earlier case's id the two pregnancies could not be linked, and an unlinked
    // "new pregnancy" record is worse than a blocked one — it silently breaks her history.
    val existingId = error.fieldErrors[KEY_EXISTING_BENEFICIARY_ID]?.trim()?.takeIf { it.isNotEmpty() }
      ?: return DuplicateOutcome.HardDuplicate
    return DuplicateOutcome.NewPregnancyPrompt(existingBeneficiaryId = existingId)
  }
}

/**
 * The Sakhi's confirmed answer to a [DuplicateOutcome.NewPregnancyPrompt], persisted on the draft so
 * the acknowledgement survives both an immediate resubmission and a later manual Data Upload.
 *
 * Its presence is the *only* thing that makes the app send `acknowledgeDuplicate`; there is no code
 * path that sets it without the Sakhi having confirmed on screen.
 */
data class DuplicateAcknowledgement(val existingBeneficiaryId: String)
