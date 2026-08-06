package org.armman.sakhi.data.visitform

import kotlinx.coroutines.delay
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import java.time.LocalDate
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

  /** Only the seeded ids have a canned context; a Sakhi's own enrolment carries a UUID. */
  override suspend fun canStartVisit(beneficiaryId: String): Boolean =
    RECORDS.containsKey(beneficiaryId)

  override suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    val base = RECORDS[beneficiaryId]
      ?: throw NoSuchElementException("No visit context for beneficiary: $beneficiaryId")
    // CR-016c: Summary banner chips come from the beneficiary's own recorded
    // diagnoses — sourced here (not duplicated as a separate fetch in the
    // ViewModel) since Visit Form's context already owns "carried-forward data".
    val comorbidities = beneficiaryProfileRepository.getBeneficiary(beneficiaryId).diagnoses
    return base.copy(
      visitTypeLabel = VISIT_TYPE_LABELS[visitId] ?: base.visitTypeLabel,
      comorbidities = comorbidities,
    )
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 500L

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
