package org.armman.sakhi.data.beneficiaryprofile

import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the profile screen's beneficiary detail from whichever source actually has it, then
 * layers a real, per-beneficiary visit schedule on top of the result (CR-022f):
 *  - a locally enrolled beneficiary (this device has a draft/synced enrolment) resolves from
 *    [LocalEnrolmentBeneficiarySource] — real identity, real diagnoses, own visit schedule.
 *  - a remote-only beneficiary (no local draft — e.g. a "Not yet assessed" row sourced from
 *    [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepository]) resolves from
 *    [RemoteBeneficiaryProfileRepository] against the real beneficiary-detail API (CR-037).
 *    [StaticBeneficiaryProfileRepository]'s fourteen-sample-id fixture is no longer consulted on
 *    this path — it previously threw for any real server id, which was the reported "We
 *    couldn't load this beneficiary" bug; it remains bound only for CR-014's own unit tests.
 *
 * ### Still static, deliberately
 * `lastVisitStats` stays empty for both sources: it comes from a completed visit's clinical
 * outcome, which — per the last-visit-vitals endpoint BE shipped this cycle — has a confirmed
 * source but not yet a confirmed JSON shape (see
 * [org.armman.sakhi.data.motherlink.BeneficiaryDetailDto]'s doc). The Visit Tracker, Dashboard and
 * My Beneficiaries screens also keep their own hardcoded visit data until CR-024 — this screen's
 * visit list and theirs will therefore disagree for the rest of M2, expected and called out in
 * the demo script rather than left to be discovered.
 */
@Singleton
class ScheduleBackedBeneficiaryProfileRepository @Inject constructor(
  private val staticProfiles: StaticBeneficiaryProfileRepository,
  private val remoteProfiles: RemoteBeneficiaryProfileRepository,
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
        // diagnosisLabels() is suspend (it may resolve codes via FormsRepository), so it has to be
        // computed here in the suspend caller, not inside the plain toProfile() below — calling a
        // suspend function through toProfile()'s non-suspend `let`/`with` doesn't compile ("should
        // be called only from a coroutine or another suspend function"). Mirrors how `village` just
        // above is already computed in this same suspend scope for the same reason.
        diagnoses = answers?.let { with(localEnrolments) { it.diagnosisLabels() } }.orEmpty(),
      )
    }
      // A remote-only beneficiary (no local enrolment draft on this device -- e.g. a "Not yet
      // assessed" row from RemoteBeneficiaryRepository) resolves against the real beneficiary-
      // detail API instead of the static fixture, which only ever knew fourteen sample ids and
      // threw NoSuchElementException for any real server id (CR-037 -- the reported "We couldn't
      // load this beneficiary" bug). staticProfiles is no longer consulted on this path; it
      // remains bound only as StaticBeneficiaryProfileRepository's own dev/demo fixture data,
      // referenced directly by CR-014's unit tests.
      // Propagates NoSuchElementException for a genuinely unknown/unreachable id, per the
      // interface contract.
      ?: remoteProfiles.getBeneficiary(id)

    val schedules = scheduleRepository.getActiveForBeneficiary(id)

    // A beneficiary enrolled before this build has no schedule rows. Return an empty list rather
    // than falling back to the static sample: showing another woman's visits would be worse than
    // showing none, and the screen renders a distinct empty state for it.
    return profile.copy(visits = schedules.toProfileVisits(LocalDate.now()))
  }

  /** Delegates to [LocalEnrolmentBeneficiarySource.answersFor] — null for a remote-only
   * beneficiary (no local CHILD_REGISTRATION draft/submission on this device), same "leave the
   * Sakhi to fill it in fresh" fallback every other prefill in this app already uses. */
  override suspend fun getChildRegistrationAnswers(id: String): FormAnswers? =
    localEnrolments.answersFor(id)

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
   * `lastVisitStats` stays empty: it comes from a completed visit's clinical outcome, which lives
   * in the visit form (CR-026). `diagnoses`, unlike `lastVisitStats`, IS captured at enrollment
   * time (Health History, Q58/Q60) — see [LocalEnrolmentBeneficiarySource.diagnosisLabels] for the
   * reported-bug fix that started reading it back out here.
   */
  private fun Beneficiary.toProfile(
    id: String,
    answers: FormAnswers?,
    village: String?,
    diagnoses: List<String>,
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
    // CR (RCH-number-blank-on-ANC1 bugfix): captured at MOTHER_REGISTRATION under the same
    // `input_rch_number` code the submission mapper reads (DynamicFormSubmissionMapper's
    // QuestionCode.RCH_NUMBER) -- was never read back out here, so it stayed blank on the
    // Visit Form's carried-forward context regardless of what the Sakhi entered.
    rchNumber = answers?.displayValue(QuestionCode.RCH_NUMBER),
    // CHILD_REGISTRATION stores the infant's own DOB/weight under different question codes than
    // MOTHER_REGISTRATION's `date_of_birth`/`weight_kg` (used below for a MOTHER profile). Reading
    // the mother's codes for a child left both fields permanently blank on every child's profile.
    dob = answers?.displayDate(dobQuestionCode),
    weight = answers?.displayValue(weightQuestionCode)?.let { "$it kg" },
    // CHILD_REGISTRATION has neither question this reads, so a child's profile always gets an
    // empty list here — matching every other MOTHER_REGISTRATION-only field above. Computed by the
    // suspend caller (getBeneficiary) and passed straight through — see the call site's comment.
    diagnoses = diagnoses,
    registrationDate = registrationDate,
  )

  private val Beneficiary.dobQuestionCode: String
    get() = if (type == BeneficiaryType.INFANT) {
      ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT
    } else {
      QuestionCode.DATE_OF_BIRTH
    }

  private val Beneficiary.weightQuestionCode: String
    get() = if (type == BeneficiaryType.INFANT) {
      ChildRegistrationQuestionCodes.CHILD_WEIGHT_AT_BIRTH_KG
    } else {
      QuestionCode.WEIGHT_KG
    }

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
    const val RCH_NUMBER = "input_rch_number"
  }

  private companion object {
    val VILLAGE_QUESTION = GeographyQuestionCodes.VILLAGE

    val PROFILE_DATE_FORMAT: DateTimeFormatter =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
  }
}
