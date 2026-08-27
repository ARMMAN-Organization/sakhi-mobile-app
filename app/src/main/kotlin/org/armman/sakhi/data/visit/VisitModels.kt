package org.armman.sakhi.data.visit

import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import java.time.LocalDate

/** Visit bucket — maps to the two tabs on the pada visits screen, and to the API's `status`
 * query param (`open` / `referral_follow_up`). */
enum class VisitType { OPEN, REFERRAL_FOLLOWUP }

/**
 * A visit or pending referral follow-up for one pada, as `GET /padas/{padaId}/visits` returns it
 * (beneficiary display fields denormalized for the list; see
 * [org.armman.sakhi.data.visittracker.VisitApi]).
 */
data class Visit(
  /** Null for every referral-follow-up row — the API never assigns one there. */
  val id: String?,
  val beneficiaryId: String,
  /** Null when beneficiary-service's name lookup failed after the base row was fetched. */
  val beneficiaryName: String?,
  /**
   * Mother vs. infant. The API doesn't return this field yet (pending backend addition) —
   * derived from [visitLabel] via [deriveBeneficiaryType] until it does. Delete the derivation
   * and bind this straight from the DTO once the real field lands.
   */
  val beneficiaryType: BeneficiaryType,
  /**
   * Null means "no risk badge" — either the enrichment lookup failed, or the API graded this
   * beneficiary `none` and the app has no dedicated "normal risk" badge distinct from
   * "not assessed" (only High/Moderate/Mild/Low exist). Mapped to
   * [org.armman.sakhi.data.beneficiary.Beneficiary.isAssessed] = false in
   * [org.armman.sakhi.ui.visittracker.toCardModel] — the same neutral-badge path already used
   * for beneficiaries with no on-device assessment.
   */
  val riskLevel: RiskLevel?,
  val visitType: VisitType,
  val pada: String,
  val village: String,
  val scheduleDate: LocalDate,
  val dueDate: LocalDate,
  /** Visit label, e.g. "ANC 3", or "Referral Follow-up" for that tab. */
  val visitLabel: String,
  /** Computed client-side from [dueDate] vs. today — the API doesn't send it. Negative once overdue. */
  val daysRemaining: Int,
  /** Null when the phone lookup failed — hides/disables the Call action. */
  val phoneNumber: String?,
)

/**
 * Temporary mother/infant inference from the visit label, until the API adds a real `caseType`
 * field (pending backend addition). Any label containing "Infant" maps to INFANT; everything
 * else — including "ANC"/"INC" labels and the case-type-less "Referral Follow-up" label — maps
 * to MOTHER. Flagged: the referral tab gives no case-type signal at all today, so a referral
 * follow-up for an infant will show the mother icon until the real field exists.
 */
fun deriveBeneficiaryType(visitLabel: String): BeneficiaryType =
  if (visitLabel.contains("infant", ignoreCase = true)) BeneficiaryType.INFANT else BeneficiaryType.MOTHER
