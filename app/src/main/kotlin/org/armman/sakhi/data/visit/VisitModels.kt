package org.armman.sakhi.data.visit

import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import java.time.LocalDate

/** Visit bucket — maps to the two tabs on the pada visits screen. */
enum class VisitType { OPEN, REFERRAL_FOLLOWUP }

/**
 * A visit scheduled for today, as the future `GET /visits/today` API is
 * expected to return it (beneficiary display fields denormalized for the list).
 */
data class Visit(
  val id: String,
  val beneficiaryId: String,
  val beneficiaryName: String,
  val beneficiaryType: BeneficiaryType,
  val riskLevel: RiskLevel,
  val visitType: VisitType,
  /** True when the visit window is about to close (purple counts in designs). */
  val isEnding: Boolean,
  val pada: String,
  val scheduleDate: LocalDate,
  /** Visit label, e.g. "ANC 3". */
  val visitLabel: String,
  val daysRemaining: Int,
  val phoneNumber: String,
)
