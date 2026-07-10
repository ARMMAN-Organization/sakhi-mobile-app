package org.armman.sakhi.data.beneficiaryprofile

import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel

/**
 * A single last-visit vital reading shown as a stat tile on the profile.
 * [value] is the display string (e.g. "9 (12)" = reading (reference)); [caption]
 * is the short descriptor (e.g. "Low Hb."); [abnormal] drives the red highlight.
 */
data class VitalStat(
  val value: String,
  val caption: String,
  val abnormal: Boolean,
)

/** Whether a visit in the history is still open or already completed. */
enum class ProfileVisitState { OPEN, COMPLETED }

/**
 * Primary action offered on a visit-history card. Drives which button (and style)
 * the card renders; all are stubbed ("coming soon") until their screens exist.
 */
enum class ProfileVisitAction { START_VISIT, FILL_FORM, SEE_DATA, REFERRAL }

/**
 * One row in the "See Visits" history. OPEN visits carry [daysRemaining] + a
 * [startable] flag (Start Visit is disabled until the visit is due); COMPLETED
 * visits may carry a [riskBadge] and a [referralIncomplete] flag.
 */
data class ProfileVisit(
  val id: String,
  /** e.g. "Visit 4" or "Enrollment". */
  val label: String,
  val state: ProfileVisitState,
  /** Schedule date (OPEN) or completed date (COMPLETED), preformatted. */
  val dateLabel: String,
  val action: ProfileVisitAction,
  val daysRemaining: Int? = null,
  val startable: Boolean = false,
  val referralIncomplete: Boolean = false,
  val riskLabel: String? = null,
)

/**
 * Full beneficiary detail as the future beneficiary-service API is expected to
 * return it. Variant-specific fields are nullable: MOTHER populates [lmp]/[edd];
 * CHILD populates [dob]/[weight]. The static implementation supplies fixed records.
 */
data class BeneficiaryProfile(
  val id: String,
  val name: String,
  val type: BeneficiaryType,
  /** Age descriptor shown after the name, e.g. "25" (mother) or "2 mo" (child). */
  val ageLabel: String,
  val village: String,
  val pada: String,
  val husbandName: String,
  val mobileNumber: String,
  val status: BeneficiaryStatus,
  val riskLevel: RiskLevel,
  /** MOTHER only. */
  val lmp: String? = null,
  /** MOTHER only. */
  val edd: String? = null,
  /** CHILD only. */
  val dob: String? = null,
  /** CHILD only. */
  val weight: String? = null,
  val diagnoses: List<String> = emptyList(),
  val lastVisitStats: List<VitalStat> = emptyList(),
  /** Visit history shown under "See Visits" (newest first). */
  val visits: List<ProfileVisit> = emptyList(),
)
