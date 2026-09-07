package org.armman.sakhi.data.visitform

import kotlinx.coroutines.delay
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the Visit Form context API. Delete once the real
 * endpoint exists — only DI references this class.
 */
@Singleton
class StaticVisitFormRepository @Inject constructor(
  private val beneficiaryProfileRepository: BeneficiaryProfileRepository,
  /** Bug fix (2026-09-04): source of the beneficiary's completed-visit history — see
   * [firstAncVisitWeightKg]'s doc for why this class needs it now. */
  private val visitScheduleRepository: VisitScheduleRepository,
  private val visitFormDraftRepository: VisitFormDraftRepository,
) : VisitFormRepository {

  /**
   * CR-026 interim: every beneficiary can open a Visit Form now, not just the seeded demo ids.
   * A Sakhi's own real enrolment still resolves — [getVisitContext] falls back to
   * [syntheticContext] for any id [RECORDS] doesn't recognise — so this only needs to reject a
   * blank id (screen opened without its nav argument).
   */
  override suspend fun canStartVisit(beneficiaryId: String): Boolean = beneficiaryId.isNotBlank()

  override suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    // Propagates NoSuchElementException for a genuinely unknown id (neither seeded nor a real
    // enrolment), same contract as before — only the "no canned context" case now has a fallback.
    val profile = beneficiaryProfileRepository.getBeneficiary(beneficiaryId)
    val base = RECORDS[beneficiaryId] ?: syntheticContext(profile, beneficiaryId, visitId)
    // CR-016c: Summary banner chips come from the beneficiary's own recorded
    // diagnoses — sourced here (not duplicated as a separate fetch in the
    // ViewModel) since Visit Form's context already owns "carried-forward data".
    return base.copy(
      visitTypeLabel = VISIT_TYPE_LABELS[visitId] ?: base.visitTypeLabel,
      comorbidities = profile.diagnoses,
    )
  }

  /**
   * Fresh context for a real enrolment [RECORDS] has no canned data for. There is no prior visit
   * yet, so height/Hb/advised-delivery-place/sickle-cell all read null — exactly like a genuine
   * first visit (see [VISIT_TYPE_LABELS]'s v1 comment). LMP is read from her own registration
   * ([BeneficiaryProfile.lmp], formatted the same way [ScheduleBackedBeneficiaryProfileRepository]
   * writes it); falls back to today only if that's missing or unparseable — a stub limitation,
   * not expected once CR-026 replaces this class with the real API.
   */
  private suspend fun syntheticContext(profile: BeneficiaryProfile, beneficiaryId: String, visitId: String) = VisitContext(
    visitTypeLabel = "ANC1",
    // Bugfix: this used to be hardcoded blank for every real (non-seeded-demo) beneficiary, so
    // an RCH number entered at MOTHER_REGISTRATION never carried forward onto ANC1. Read from the
    // profile the same way `lmp` two lines below already does; still blank (not a crash) for a
    // beneficiary with no RCH card on file, exactly per FR spec row 39-40.
    rchNumber = profile.rchNumber.orEmpty(),
    lmp = profile.lmp
      ?.let { runCatching { LocalDate.parse(it, PROFILE_DATE_FORMAT) }.getOrNull() }
      ?: LocalDate.now(),
    heightCm = null,
    previousHb = null,
    advisedDeliveryPlace = null,
    sickleCell = null,
    registrationWeightKg = firstAncVisitWeightKg(profile, beneficiaryId, visitId),
  )

  /**
   * Bug fix (2026-09-04): reported bug — ANC_VISIT's "Gestational weight gain" always showed the
   * "Auto-calculated" placeholder, never an actual Normal/Severe value. Root cause: this used to
   * read [BeneficiaryProfile.weight], which is sourced from MOTHER_REGISTRATION's answers — but
   * checked against the live spec (`Registration_PW_D`, all 65 rows), MOTHER_REGISTRATION never
   * asks for the woman's weight at all. That question only exists on ANC_VISIT itself (spec row
   * 13, "Current weight of the woman in kg"), captured at her FIRST ANC visit and carried forward
   * from there — same "open only in first visit, auto-populate in the rest" convention as
   * Height/BMI (spec rows 12/14). So [BeneficiaryProfile.weight] was always blank for every real
   * (non-seeded-demo) beneficiary, and [VisitFormComputedFieldEvaluator.gestationalWeightGain]'s
   * `registrationWeightKg ?: return null` guard fired every single time.
   *
   * Reads the beneficiary's own answered [VisitFormQuestionCodes.WEIGHT_KG] back out of her first
   * COMPLETED ANC visit ([VisitCodeType.ANC], `sequenceNo == 1`) via [visitScheduleRepository] +
   * [visitFormDraftRepository] — the same two stores [org.armman.sakhi.ui.visitform
   * .DynamicVisitFormViewModel] itself writes to on submit, so no new persistence had to be added.
   * Null (falls back to the "Auto-calculated" placeholder, same as before this fix) whenever:
   *  - she has no completed first ANC visit yet (opening ANC1 itself — nothing to carry forward,
   *    matches this constant's own KDoc: weight gain isn't meaningful before a baseline exists),
   *  - that visit was recorded on a different device (this device never saved its local draft —
   *    see [VisitFormDraftRepository.getAnswers]'s own doc for why this is a real, not-yet-closed
   *    gap; CR-026 replacing this whole stub with a real backend fetch resolves it for good), or
   *  - she left the weight question blank on that visit (shouldn't happen — it's mandatory per
   *    spec — but this must not crash on a beneficiary enrolled before that was enforced).
   *
   * [visitId] excludes the CURRENT visit from counting as its own baseline — relevant when this IS
   * her first ANC visit: [org.armman.sakhi.data.schedule.VisitScheduleEntity.status] only flips to
   * COMPLETED on submit, so in practice an in-progress ANC1 would never match anyway, but the
   * explicit check keeps this correct even if that ordering ever changes.
   */
  private suspend fun firstAncVisitWeightKg(
    profile: BeneficiaryProfile,
    beneficiaryId: String,
    visitId: String,
  ): Double? {
    if (profile.type != BeneficiaryType.MOTHER) return null
    val firstAnc = visitScheduleRepository.getForBeneficiary(beneficiaryId)
      .firstOrNull { it.visitType == VisitCodeType.ANC && it.sequenceNo == 1 }
      ?.takeIf { it.localScheduleUuid != visitId }
      ?: return null
    val answers = visitFormDraftRepository.getAnswers(firstAnc.localScheduleUuid) ?: return null
    return answers.valueOf(VisitFormQuestionCodes.WEIGHT_KG)?.toDoubleOrNull()
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L

    /** Matches ScheduleBackedBeneficiaryProfileRepository.PROFILE_DATE_FORMAT — same source. */
    val PROFILE_DATE_FORMAT: DateTimeFormatter =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    // v1 = the beneficiary's first visit (no height/Hb history yet — Q12 stays
    // editable); v2-v4 carry forward height + Hb from that first visit.
    val VISIT_TYPE_LABELS = mapOf("v1" to "ANC1", "v2" to "ANC2", "v3" to "ANC3", "v4" to "ANC4")

    val MOTHER_CONTEXT = VisitContext(
      visitTypeLabel = "ANC",
      rchNumber = "RCH-2025-001234",
      lmp = LocalDate.of(2025, 12, 1),
      heightCm = 152,
      previousHb = 8.5,
      advisedDeliveryPlace = 5, // Primary Health Centre
      sickleCell = 1, // Tested and result is normal
      registrationWeightKg = 54.0, // Demo baseline for the seeded b01-b14 records.
    )

    val CHILD_CONTEXT = MOTHER_CONTEXT.copy(previousHb = 10.5)

    val RECORDS: Map<String, VisitContext> = mapOf(
      "b01" to MOTHER_CONTEXT, "b02" to MOTHER_CONTEXT, "b03" to MOTHER_CONTEXT,
      "b04" to MOTHER_CONTEXT, "b05" to MOTHER_CONTEXT, "b06" to MOTHER_CONTEXT,
      "b07" to CHILD_CONTEXT, "b08" to CHILD_CONTEXT,
      "b09" to MOTHER_CONTEXT, "b10" to MOTHER_CONTEXT,
      "b11" to CHILD_CONTEXT,
      "b12" to MOTHER_CONTEXT, "b13" to MOTHER_CONTEXT,
      "b14" to CHILD_CONTEXT,
    )
  }
}
