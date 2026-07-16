package org.armman.sakhi.data.previsithealth

import org.armman.sakhi.data.beneficiary.RiskLevel

/**
 * One trend value slot on a risk-factor card — a past visit reading, or the
 * current "Last Visit" reference value. [label] is preformatted ("6 July",
 * "Last Visit"); [abnormal] drives the per-slot color per the design.
 */
data class TrendValue(
  val label: String,
  val value: String,
  val abnormal: Boolean,
)

/**
 * A single clinical factor's trend card. [riskLevel] null means this factor is
 * shown plainly with no risk pill (e.g. Weight, Temperature when not flagged).
 * Per FR-S-4.6 at most 5 factors total appear across BP, Hb, Weight, Blood
 * Sugar and Temperature — never more.
 */
data class RiskFactorTrend(
  val factorName: String,
  val measureLabel: String,
  val riskLevel: RiskLevel?,
  val values: List<TrendValue>,
)

/**
 * Read-only clinical reference shown before a visit (FR-S-4.6). Sourced from
 * the beneficiary's last 2 completed visits. Never shown on a beneficiary's
 * first visit — callers gate on [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit.hasPreVisitHistory]
 * before navigating here, so this model assumes data exists.
 */
data class PreVisitHealthHistory(
  /** At most 5, only the factors actually flagged as at-risk. */
  val riskFactors: List<RiskFactorTrend>,
  /** Plain vitals (no risk pill) shown below the risk cards. */
  val nonRiskVitals: List<RiskFactorTrend>,
)
