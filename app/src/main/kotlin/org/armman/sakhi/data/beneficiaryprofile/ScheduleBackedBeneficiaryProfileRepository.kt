package org.armman.sakhi.data.beneficiaryprofile

import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves the profile screen with **real** visit schedules while the rest of the profile stays
 * static (CR-022f).
 *
 * A deliberate half-step. Wiring the whole profile needs a beneficiary-detail API that does not
 * exist yet, but the visit list can be real today — and it is the part that proves the scheduling
 * engine works end to end. So this delegates to [StaticBeneficiaryProfileRepository] for identity,
 * vitals and diagnoses, and replaces only [BeneficiaryProfile.visits].
 *
 * What that fixes immediately: the static repository returns the *same five-row visit list for
 * every beneficiary*, so today two different women show identical visit histories. After this,
 * each shows her own schedule.
 *
 * ### Still static, deliberately
 * The Visit Tracker, Dashboard and My Beneficiaries screens keep their hardcoded visit data until
 * CR-024. The tracker and this screen will therefore disagree for the rest of M2 — expected, and
 * called out in the demo script rather than left to be discovered.
 */
@Singleton
class ScheduleBackedBeneficiaryProfileRepository @Inject constructor(
  private val staticProfiles: StaticBeneficiaryProfileRepository,
  private val scheduleRepository: VisitScheduleRepository,
  private val localEnrolments: LocalEnrolmentBeneficiarySource,
) : BeneficiaryProfileRepository {

  override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
    // A locally enrolled woman's id is a UUID the static records know nothing about, so she has to
    // be resolved first — otherwise every tap from My Beneficiaries into a real enrolment lands on
    // the error state.
    val profile = localEnrolments.findLocalBeneficiary(id)?.let { local ->
      val answers = localEnrolments.answersFor(id)
      local.toProfile(
        id = id,
        answers = answers,
        // Geography answers are ids; the names come from the same place the form's own dropdown
        // reads them (the active version's geography array), served from cache when offline.
        village = answers?.let { with(localEnrolments) { it.resolveGeography(VILLAGE_QUESTION) } },
      )
    }
      // Propagates NoSuchElementException for a genuinely unknown id, per the interface contract.
      ?: staticProfiles.getBeneficiary(id)

    val schedules = scheduleRepository.getActiveForBeneficiary(id)

    // A beneficiary enrolled before this build has no schedule rows. Return an empty list rather
    // than falling back to the static sample: showing another woman's visits would be worse than
    // showing none, and the screen renders a distinct empty state for it.
    return profile.copy(visits = schedules.toProfileVisits(LocalDate.now()))
  }

  /**
   * Builds the profile header from what the enrolment form actually captured.
   *
   * Most of these fields *are* answered by MOTHER_REGISTRATION — age, DOB, weight and village all
   * exist in the schema — so leaving them blank was an omission rather than missing data. They are
   * mapped here.
   *
   * Husband's name arrived in the schema on 2026-08-05 (backend PR #105) as an **optional**
   * `husbands_name` text question, so it is blank for anyone enrolled before that and for anyone
   * who simply leaves it unanswered.
   *
   * `lastVisitStats` and `diagnoses` stay empty: both come from a completed visit's clinical
   * outcome, which lives in the visit form (CR-026).
   */
  private fun Beneficiary.toProfile(
    id: String,
    answers: FormAnswers?,
    village: String?,
  ) = BeneficiaryProfile(
    id = id,
    name = name,
    type = type,
    ageLabel = answers?.displayValue(QuestionCode.AGE).orEmpty(),
    village = village.orEmpty(),
    pada = pada,
    husbandName = answers?.displayValue(QuestionCode.HUSBANDS_NAME).orEmpty(),
    mobileNumber = phoneNumber,
    status = status,
    riskLevel = riskLevel,
    lmp = answers?.displayDate(QuestionCode.LMP_DATE),
    edd = answers?.displayDate(QuestionCode.EDD),
    dob = answers?.displayDate(QuestionCode.DATE_OF_BIRTH),
    weight = answers?.displayValue(QuestionCode.WEIGHT_KG)?.let { "$it kg" },
  )

  private fun FormAnswers.displayValue(questionCode: String): String? =
    valueOf(questionCode)?.trim()?.takeIf { it.isNotBlank() }

  /** Answers store dates as ISO strings; render them the way the rest of the profile does. */
  private fun FormAnswers.displayDate(questionCode: String): String? =
    displayValue(questionCode)?.let { raw ->
      runCatching { LocalDate.parse(raw).format(PROFILE_DATE_FORMAT) }.getOrDefault(raw)
    }

  private object QuestionCode {
    /** Added to MOTHER_REGISTRATION on 2026-08-05 (backend PR #105). Optional, so often blank. */
    const val HUSBANDS_NAME = "husbands_name"

    const val AGE = "age_of_the_beneficiary"
    const val DATE_OF_BIRTH = "date_of_birth"
    const val LMP_DATE = "lmp_date"
    const val EDD = "edd"
    const val WEIGHT_KG = "weight_kg"
  }

  private companion object {
    val VILLAGE_QUESTION = GeographyQuestionCodes.VILLAGE

    val PROFILE_DATE_FORMAT: DateTimeFormatter =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
  }
}
