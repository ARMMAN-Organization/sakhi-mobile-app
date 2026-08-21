package org.armman.sakhi.data.growthstandards

import kotlin.math.ln
import kotlin.math.pow

/**
 * WHO Child Growth Standards z-score formula (CR-033) — pure, stateless, no knowledge of which
 * of the three INFANT_VISIT indicators (wasting/stunting/underweight) a given [WhoGrowthStandardsLms
 * .Lms] came from. Callers ([org.armman.sakhi.data.visitform.InfantVisitFormComputedFieldEvaluator])
 * pick the right table via [WhoGrowthStandardsLms]'s three lookup functions and pass the result
 * here alongside the raw measurement.
 */
object WhoZScoreCalculator {

  /**
   * The standard WHO/Cole LMS z-score transform:
   * - `L != 0`: `Z = (((measurement / M) ^ L) - 1) / (L * S)`
   * - `L == 0`: `Z = ln(measurement / M) / S`
   *
   * Returns null rather than throwing for a non-positive [measurement] or a non-positive
   * [WhoGrowthStandardsLms.Lms.m]/[WhoGrowthStandardsLms.Lms.s] — none of those are physically
   * meaningful inputs (a weight/length can't be <= 0), and a computed field should degrade to
   * "unanswered" rather than crash the visit form.
   */
  fun zScore(measurement: Double, lms: WhoGrowthStandardsLms.Lms): Double? {
    if (measurement <= 0.0 || lms.m <= 0.0 || lms.s <= 0.0) return null
    return if (lms.l == 0.0) {
      ln(measurement / lms.m) / lms.s
    } else {
      (((measurement / lms.m).pow(lms.l)) - 1.0) / (lms.l * lms.s)
    }
  }
}
