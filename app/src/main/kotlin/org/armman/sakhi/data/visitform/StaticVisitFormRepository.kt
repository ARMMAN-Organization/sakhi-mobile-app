package org.armman.sakhi.data.visitform

import kotlinx.coroutines.delay
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
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
    val base = RECORDS[beneficiaryId] ?: syntheticContext(profile)
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
  private fun syntheticContext(profile: BeneficiaryProfile) = VisitContext(
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
    registrationWeightKg = registrationWeightKgFrom(profile),
  )

  /**
   * [BeneficiaryProfile.weight] is formatted for display (`"62.5 kg"`, see
   * [org.armman.sakhi.data.beneficiaryprofile.ScheduleBackedBeneficiaryProfileRepository
   * .toProfile]) — strip the unit and parse back to a number for
   * [VisitContext.registrationWeightKg]. Null for a beneficiary whose registration didn't record
   * a weight (blank string) or whatever CHILD's own [BeneficiaryProfile.weight] means (not
   * applicable here — [VisitFormComputedFieldEvaluator]'s weight-gain calc only runs for the
   * mother/ANC_VISIT flow).
   */
  private fun registrationWeightKgFrom(profile: BeneficiaryProfile): Double? =
    profile.weight?.removeSuffix("kg")?.trim()?.toDoubleOrNull()

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
