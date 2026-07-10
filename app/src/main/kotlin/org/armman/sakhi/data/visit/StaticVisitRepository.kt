package org.armman.sakhi.data.visit

import kotlinx.coroutines.delay
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the visits API. Records cover both visit types, both
 * beneficiary types, several padas, all risk levels and ending flags so every
 * aggregate and tab is demonstrable. Delete once the real endpoint exists.
 */
@Singleton
class StaticVisitRepository @Inject constructor() : VisitRepository {

  override suspend fun getTodaysVisits(): List<Visit> {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return STATIC_VISITS
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L

    val STATIC_VISITS = listOf(
      // Jamsar — matches the design reference (p64): 4 open, 2 referral.
      visit("v01", "b01", "Sunita Sharma", RiskLevel.HIGH, VisitType.OPEN, "Jamsar", ending = true, days = 2),
      visit("v02", "b02", "Riya Verma", RiskLevel.MODERATE, VisitType.OPEN, "Jamsar", days = 6),
      visit("v03", "b15", "Tina Verma", RiskLevel.MILD, VisitType.OPEN, "Jamsar", days = 6),
      visit("v04", "b16", "Baby of Tina Verma", RiskLevel.LOW, VisitType.OPEN, "Jamsar", type = BeneficiaryType.INFANT, label = "NN 1", days = 4),
      visit("v05", "b01", "Sunita Sharma", RiskLevel.HIGH, VisitType.REFERRAL_FOLLOWUP, "Jamsar", days = 1),
      visit("v06", "b17", "Baby of Asha Pawar", RiskLevel.MODERATE, VisitType.REFERRAL_FOLLOWUP, "Jamsar", type = BeneficiaryType.INFANT, label = "NN 2", days = 3),
      // Kelghar.
      visit("v07", "b03", "Abha Mhatre", RiskLevel.MILD, VisitType.OPEN, "Kelghar", ending = true, days = 1),
      visit("v08", "b06", "Asha Pawar", RiskLevel.LOW, VisitType.OPEN, "Kelghar", days = 9),
      visit("v09", "b07", "Baby of Asha Pawar", RiskLevel.MODERATE, VisitType.REFERRAL_FOLLOWUP, "Kelghar", type = BeneficiaryType.INFANT, label = "NN 2", days = 3),
      // Savarpada.
      visit("v10", "b04", "Kavita Patil", RiskLevel.LOW, VisitType.OPEN, "Savarpada", days = 5),
      visit("v11", "b10", "Savita More", RiskLevel.MILD, VisitType.REFERRAL_FOLLOWUP, "Savarpada", days = 2),
      // Kasatvadi.
      visit("v12", "b05", "Meena Gavit", RiskLevel.HIGH, VisitType.OPEN, "Kasatvadi", ending = true, days = 1),
      visit("v13", "b08", "Baby of Meena Gavit", RiskLevel.MILD, VisitType.OPEN, "Kasatvadi", type = BeneficiaryType.INFANT, label = "NN 1", days = 5),
    )

    fun visit(
      id: String,
      beneficiaryId: String,
      name: String,
      risk: RiskLevel,
      visitType: VisitType,
      pada: String,
      type: BeneficiaryType = BeneficiaryType.MOTHER,
      label: String = "ANC 3",
      ending: Boolean = false,
      days: Int,
    ) = Visit(
      id = id,
      beneficiaryId = beneficiaryId,
      beneficiaryName = name,
      beneficiaryType = type,
      riskLevel = risk,
      visitType = visitType,
      isEnding = ending,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 4, 24),
      visitLabel = label,
      daysRemaining = days,
      phoneNumber = "+919876543210",
    )
  }
}
