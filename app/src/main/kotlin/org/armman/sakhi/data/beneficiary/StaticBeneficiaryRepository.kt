package org.armman.sakhi.data.beneficiary

import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the beneficiary API. Records deliberately cover every
 * tab, sub-tab, risk level and several padas so all filters are demonstrable.
 * Delete once the real endpoint exists — only DI references this class.
 */
@Singleton
class StaticBeneficiaryRepository @Inject constructor() : BeneficiaryRepository {

  override suspend fun getBeneficiaries(): List<Beneficiary> {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return STATIC_BENEFICIARIES
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L

    val STATIC_BENEFICIARIES = listOf(
      active("b01", "Sunita Sharma", RiskLevel.HIGH, VisitState.OPEN, "Jamsar", "ANC 3", 2),
      active("b02", "Riya Verma", RiskLevel.MODERATE, VisitState.OPEN, "Jamsar", "ANC 3", 6),
      active("b03", "Abha Mhatre", RiskLevel.MILD, VisitState.PENDING_REFERRAL, "Kelghar", "ANC 2", 4),
      active("b04", "Kavita Patil", RiskLevel.LOW, VisitState.MISSED, "Savarpada", "PNC 1", 0),
      active("b05", "Meena Gavit", RiskLevel.HIGH, VisitState.PENDING_REFERRAL, "Kasatvadi", "ANC 4", 1),
      active("b06", "Asha Pawar", RiskLevel.LOW, VisitState.OPEN, "Kelghar", "ANC 1", 9),
      active("b07", "Baby of Asha Pawar", RiskLevel.MODERATE, VisitState.MISSED, "Kelghar", "NN 2", 3, BeneficiaryType.INFANT),
      active("b08", "Baby of Meena Gavit", RiskLevel.MILD, VisitState.OPEN, "Kasatvadi", "NN 1", 5, BeneficiaryType.INFANT),
      journeyComplete("b09", "Lata Wagh", RiskLevel.LOW, "Jamsar", "CCV 6", YearMonth.of(2026, 4)),
      journeyComplete("b10", "Savita More", RiskLevel.MILD, "Savarpada", "CCV 6", YearMonth.of(2026, 4)),
      journeyComplete("b11", "Baby of Lata Wagh", RiskLevel.LOW, "Jamsar", "CCV 5", YearMonth.of(2026, 3), BeneficiaryType.INFANT),
      closed("b12", "Rekha Jadhav", RiskLevel.MODERATE, "Kelghar", "ANC 2"),
      closed("b13", "Vandana Koli", RiskLevel.HIGH, "Kasatvadi", "PNC 2"),
      closed("b14", "Baby of Rekha Jadhav", RiskLevel.LOW, "Kelghar", "NN 3", BeneficiaryType.INFANT),
    )

    fun active(
      id: String,
      name: String,
      risk: RiskLevel,
      visitState: VisitState,
      pada: String,
      visitLabel: String,
      daysRemaining: Int,
      type: BeneficiaryType = BeneficiaryType.MOTHER,
    ) = Beneficiary(
      id = id,
      name = name,
      type = type,
      riskLevel = risk,
      status = BeneficiaryStatus.ACTIVE,
      visitState = visitState,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 4, 24),
      visitLabel = visitLabel,
      daysRemaining = daysRemaining,
      phoneNumber = "+919876543210",
      journeyCompletedIn = null,
    )

    fun journeyComplete(
      id: String,
      name: String,
      risk: RiskLevel,
      pada: String,
      visitLabel: String,
      completedIn: YearMonth,
      type: BeneficiaryType = BeneficiaryType.MOTHER,
    ) = Beneficiary(
      id = id,
      name = name,
      type = type,
      riskLevel = risk,
      status = BeneficiaryStatus.JOURNEY_COMPLETE,
      visitState = null,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 4, 24),
      visitLabel = visitLabel,
      daysRemaining = 0,
      phoneNumber = "+919876543210",
      journeyCompletedIn = completedIn,
    )

    fun closed(
      id: String,
      name: String,
      risk: RiskLevel,
      pada: String,
      visitLabel: String,
      type: BeneficiaryType = BeneficiaryType.MOTHER,
    ) = Beneficiary(
      id = id,
      name = name,
      type = type,
      riskLevel = risk,
      status = BeneficiaryStatus.CLOSED,
      visitState = null,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 2, 10),
      visitLabel = visitLabel,
      daysRemaining = 0,
      phoneNumber = "+919876543210",
      journeyCompletedIn = null,
    )
  }
}
