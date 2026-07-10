package org.armman.sakhi.data.beneficiary

import java.time.LocalDate
import java.time.YearMonth

/** Beneficiary type — mother (PW/PP) or infant. */
enum class BeneficiaryType { MOTHER, INFANT }

/** Risk classification per the design's risk filter (High/Moderate/Mild/Low). */
enum class RiskLevel { HIGH, MODERATE, MILD, LOW }

/** Journey status — maps to the three tabs on My Beneficiaries. */
enum class BeneficiaryStatus { ACTIVE, JOURNEY_COMPLETE, CLOSED }

/** Current visit state — maps to the Active tab's sub-tabs. */
enum class VisitState { OPEN, PENDING_REFERRAL, MISSED }

/**
 * Beneficiary list item as the future beneficiary-service API is expected to
 * return it. Static implementation supplies fixed records today.
 */
data class Beneficiary(
  val id: String,
  val name: String,
  val type: BeneficiaryType,
  val riskLevel: RiskLevel,
  val status: BeneficiaryStatus,
  /** Non-null only while [status] is ACTIVE. */
  val visitState: VisitState?,
  val pada: String,
  val scheduleDate: LocalDate,
  /** Upcoming/last visit label, e.g. "ANC 3". */
  val visitLabel: String,
  val daysRemaining: Int,
  val phoneNumber: String,
  /** Non-null only when [status] is JOURNEY_COMPLETE. */
  val journeyCompletedIn: YearMonth?,
)
