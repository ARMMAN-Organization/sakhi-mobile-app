package org.armman.sakhi.data.beneficiaryprofile

import kotlinx.coroutines.delay
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the beneficiary-detail API. Keyed by the same ids as
 * [org.armman.sakhi.data.beneficiary.StaticBeneficiaryRepository] so every
 * "See Profile" tap resolves. Unknown ids throw so the UI shows its error state.
 * Delete once the real endpoint exists — only DI references this class.
 */
@Singleton
class StaticBeneficiaryProfileRepository @Inject constructor() : BeneficiaryProfileRepository {

  override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return RECORDS[id] ?: throw NoSuchElementException("Unknown beneficiary id: $id")
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L

    /** Mother stat set per the board: Hb, BP, Temp, Weight (all abnormal). */
    val MOTHER_STATS = listOf(
      VitalStat(value = "9 (12)", caption = "Low Hb.", abnormal = true),
      VitalStat(value = "80 (60)- 140 (120)", caption = "High BP.", abnormal = true),
      VitalStat(value = "101 (98.7)", caption = "High Temp.", abnormal = true),
      VitalStat(value = "45 (60)", caption = "Low Weight", abnormal = true),
    )

    /** Child stat set: Weight + Hb. */
    val CHILD_STATS = listOf(
      VitalStat(value = "2.1 (3.2)", caption = "Low Weight", abnormal = true),
      VitalStat(value = "11 (12)", caption = "Hb.", abnormal = false),
    )

    /** Visit history shown under "See Visits" (newest first). */
    val VISITS = listOf(
      ProfileVisit(
        id = "v4",
        label = "Visit 4",
        state = ProfileVisitState.OPEN,
        dateLabel = "24 Apr 2025",
        action = ProfileVisitAction.START_VISIT,
        daysRemaining = 2,
        startable = false, // Not due yet — Start Visit disabled per design.
        hasPreVisitHistory = true, // v1-v3 completed — prior data exists.
      ),
      ProfileVisit(
        id = "v3",
        label = "Visit 3",
        state = ProfileVisitState.COMPLETED,
        dateLabel = "24 Apr 2025",
        action = ProfileVisitAction.FILL_FORM,
        referralIncomplete = true,
        riskLabel = "High Risk",
        hasPreVisitHistory = true, // v1-v2 completed — prior data exists.
      ),
      ProfileVisit(
        id = "v2",
        label = "Visit 2",
        state = ProfileVisitState.COMPLETED,
        dateLabel = "24 Apr 2025",
        action = ProfileVisitAction.SEE_DATA,
        riskLabel = "High Risk",
        hasPreVisitHistory = true, // v1 completed — prior data exists.
      ),
      ProfileVisit(
        id = "v1",
        label = "Visit 1",
        state = ProfileVisitState.COMPLETED,
        dateLabel = "24 Apr 2025",
        action = ProfileVisitAction.SEE_DATA,
        hasPreVisitHistory = false, // The beneficiary's actual first visit — no prior data.
      ),
      ProfileVisit(
        id = "enrollment",
        label = "Enrollment",
        state = ProfileVisitState.COMPLETED,
        dateLabel = "24 Apr 2025",
        action = ProfileVisitAction.SEE_DATA,
      ),
    )

    fun mother(
      id: String,
      name: String,
      risk: RiskLevel,
      status: BeneficiaryStatus = BeneficiaryStatus.ACTIVE,
    ) = BeneficiaryProfile(
      id = id,
      name = name,
      type = BeneficiaryType.MOTHER,
      ageLabel = "25",
      village = "Rampur",
      pada = "Chausa",
      husbandName = "Akash Sharma",
      mobileNumber = "987563421",
      status = status,
      riskLevel = risk,
      lmp = "1 Dec 2025",
      edd = "1 Sep 2026",
      // Profile stat strip shows DOB | Weight for every beneficiary type.
      dob = "10 Nov 2000",
      weight = "45 Kg",
      diagnoses = listOf("Sickle Cell", "Chronic Diabetes"),
      lastVisitStats = MOTHER_STATS,
      visits = VISITS,
    )

    fun child(
      id: String,
      name: String,
      risk: RiskLevel,
      status: BeneficiaryStatus = BeneficiaryStatus.ACTIVE,
    ) = BeneficiaryProfile(
      id = id,
      name = name,
      type = BeneficiaryType.INFANT,
      ageLabel = "2 mo",
      village = "Rampur",
      pada = "Chausa",
      husbandName = "Akash Sharma",
      mobileNumber = "987563421",
      status = status,
      riskLevel = risk,
      dob = "10 Nov 2025",
      weight = "2.1 Kg",
      diagnoses = listOf("Low Birth Weight"),
      lastVisitStats = CHILD_STATS,
      visits = VISITS,
    )

    // Keyed to the beneficiary-list ids so navigation from any card resolves.
    val RECORDS: Map<String, BeneficiaryProfile> = listOf(
      mother("b01", "Sunita Sharma", RiskLevel.HIGH),
      mother("b02", "Riya Verma", RiskLevel.MODERATE),
      mother("b03", "Abha Mhatre", RiskLevel.MILD),
      mother("b04", "Kavita Patil", RiskLevel.LOW),
      mother("b05", "Meena Gavit", RiskLevel.HIGH),
      mother("b06", "Asha Pawar", RiskLevel.LOW),
      child("b07", "Baby of Asha Pawar", RiskLevel.MODERATE),
      child("b08", "Baby of Meena Gavit", RiskLevel.MILD),
      mother("b09", "Lata Wagh", RiskLevel.LOW, BeneficiaryStatus.JOURNEY_COMPLETE),
      mother("b10", "Savita More", RiskLevel.MILD, BeneficiaryStatus.JOURNEY_COMPLETE),
      child("b11", "Baby of Lata Wagh", RiskLevel.LOW, BeneficiaryStatus.JOURNEY_COMPLETE),
      mother("b12", "Rekha Jadhav", RiskLevel.MODERATE, BeneficiaryStatus.CLOSED),
      mother("b13", "Vandana Koli", RiskLevel.HIGH, BeneficiaryStatus.CLOSED),
      child("b14", "Baby of Rekha Jadhav", RiskLevel.LOW, BeneficiaryStatus.CLOSED),
    ).associateBy { it.id }
  }
}
