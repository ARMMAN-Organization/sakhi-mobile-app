package org.armman.sakhi.data.previsithealth

import kotlinx.coroutines.delay
import org.armman.sakhi.data.beneficiary.RiskLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the visit-history/trend API. Keyed by beneficiary id
 * only (one canned trend record per beneficiary, regardless of which of their
 * visits is being started) since no real per-visit history exists yet.
 * Delete once the real endpoint exists — only DI references this class.
 */
@Singleton
class StaticPreVisitHealthHistoryRepository @Inject constructor() :
  PreVisitHealthHistoryRepository {

  override suspend fun getHealthHistory(
    beneficiaryId: String,
    visitId: String,
  ): PreVisitHealthHistory {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return RECORDS[beneficiaryId]
      ?: throw NoSuchElementException("No pre-visit health history for beneficiary: $beneficiaryId")
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L

    /** Per the Pre-Visit Health History board (Sunita Sharma / Aishwarya Pawar). */
    val MOTHER_HISTORY = PreVisitHealthHistory(
      riskFactors = listOf(
        RiskFactorTrend(
          factorName = "Anaemia",
          measureLabel = "Hemoglobin",
          riskLevel = RiskLevel.HIGH,
          values = listOf(
            TrendValue(label = "6 July", value = "9.4", abnormal = false),
            TrendValue(label = "6 August", value = "8.5", abnormal = false),
            TrendValue(label = "Last Visit", value = "6.5", abnormal = true),
          ),
        ),
        RiskFactorTrend(
          factorName = "Hypertension",
          measureLabel = "Blood Pressure",
          riskLevel = RiskLevel.HIGH,
          values = listOf(
            TrendValue(label = "6 July", value = "60/120", abnormal = false),
            TrendValue(label = "6 August", value = "70/130", abnormal = true),
            TrendValue(label = "Last Visit", value = "80/140", abnormal = true),
          ),
        ),
      ),
      nonRiskVitals = listOf(
        RiskFactorTrend(
          factorName = "Weight",
          measureLabel = "Weight",
          riskLevel = null,
          values = listOf(
            TrendValue(label = "6 July", value = "70.2 kg", abnormal = false),
            TrendValue(label = "6 August", value = "60.2 kg", abnormal = true),
            TrendValue(label = "Last Visit", value = "70.2 kg", abnormal = false),
          ),
        ),
      ),
    )

    /** Child variant: weight + Hb trend only (no BP/pregnancy-specific factors). */
    val CHILD_HISTORY = PreVisitHealthHistory(
      riskFactors = listOf(
        RiskFactorTrend(
          factorName = "Anaemia",
          measureLabel = "Hemoglobin",
          riskLevel = RiskLevel.MODERATE,
          values = listOf(
            TrendValue(label = "6 July", value = "11.2", abnormal = false),
            TrendValue(label = "6 August", value = "10.5", abnormal = true),
            TrendValue(label = "Last Visit", value = "10.1", abnormal = true),
          ),
        ),
      ),
      nonRiskVitals = listOf(
        RiskFactorTrend(
          factorName = "Weight",
          measureLabel = "Weight",
          riskLevel = null,
          values = listOf(
            TrendValue(label = "6 July", value = "3.1 kg", abnormal = false),
            TrendValue(label = "6 August", value = "3.4 kg", abnormal = false),
            TrendValue(label = "Last Visit", value = "3.6 kg", abnormal = false),
          ),
        ),
      ),
    )

    // Keyed to the same beneficiary-profile ids as StaticBeneficiaryProfileRepository
    // so every "Start Visit"/"Fill Form" resolves. Mothers get MOTHER_HISTORY,
    // children get CHILD_HISTORY; a beneficiary's actual first visit still routes
    // straight to the Visit Form regardless (gated by ProfileVisit.hasPreVisitHistory,
    // not by whether a record exists here).
    val RECORDS: Map<String, PreVisitHealthHistory> = mapOf(
      "b01" to MOTHER_HISTORY, "b02" to MOTHER_HISTORY, "b03" to MOTHER_HISTORY,
      "b04" to MOTHER_HISTORY, "b05" to MOTHER_HISTORY, "b06" to MOTHER_HISTORY,
      "b07" to CHILD_HISTORY, "b08" to CHILD_HISTORY,
      "b09" to MOTHER_HISTORY, "b10" to MOTHER_HISTORY,
      "b11" to CHILD_HISTORY,
      "b12" to MOTHER_HISTORY, "b13" to MOTHER_HISTORY,
      "b14" to CHILD_HISTORY,
    )
  }
}
