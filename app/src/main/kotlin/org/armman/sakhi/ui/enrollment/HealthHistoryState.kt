package org.armman.sakhi.ui.enrollment

import java.time.LocalDate

/** Field-level validation outcomes for the Health History step (Q35–65). */
enum class HealthFieldError {
  /** Mandatory field left empty (shown after a blocked Next attempt). */
  REQUIRED,

  /** Whole-number count outside its allowed range (Q45–50). */
  COUNT_RANGE,

  /** ANC-1 date must be after LMP and not in the future (Q42). */
  ANC1_DATE_INVALID,

  /** A Td dose date is in the future (Q44). */
  TD_DATE_FUTURE,

  /** Td dose dates must be ordered Td1 ≤ Td2 ≤ Booster (Q44). */
  TD_DATE_ORDER,

  /** Para must not exceed Gravida (Q46). */
  PARA_EXCEEDS_GRAVIDA,

  /** Abortions must not exceed Gravida (Q48). */
  ABORTIONS_EXCEED_GRAVIDA,

  /** Dead children must not exceed living children (Q50). */
  DEAD_EXCEEDS_LIVING,

  /** Gravida ≠ Para + Abortions + 1 (Q45 cross-total). */
  GRAVIDA_TOTAL_MISMATCH,
}

/**
 * Health History step (Excel Q35–65). Dropdown/radio answers are 1-based
 * option codes; multi-selects are a [Set] of 1-based codes (the API layer
 * expands each to a per-condition boolean). Risk severity / referral actions
 * are out of scope (GoRules risk CR).
 *
 * [gestationalAgeWeeks] is mirrored from Personal Info so the read-only
 * trimester (Q35) can be derived here.
 */
data class HealthHistoryState(
  val gestationalAgeWeeks: Int? = null,

  // Q36–38 — current pregnancy.
  val plannedPregnancy: Int? = null,
  val tookTreatment: Boolean? = null,
  val treatmentType: Int? = null,

  // Q39–40 — RCH.
  val rchStatus: Int? = null,
  val rchNumber: String = "",

  // Q41–43 — ANC.
  val ancStatus: Int? = null,
  val anc1Date: LocalDate? = null,
  val ancConditions: Set<Int> = emptySet(),

  // Q44 — Td doses.
  val tdNone: Boolean = false,
  val td1: Boolean = false,
  val td1Date: LocalDate? = null,
  val td2: Boolean = false,
  val td2Date: LocalDate? = null,
  val tdBooster: Boolean = false,
  val tdBoosterDate: LocalDate? = null,

  // Q45–50 — past obstetric history (counts as strings).
  val gravida: String = "",
  val para: String = "",
  val livingChildren: String = "",
  val abortions: String = "",
  val stillBirths: String = "",
  val deadChildren: String = "",

  // Q51–57 — last pregnancy (only when Gravida > 1).
  val lastPregnancyWhen: Int? = null,
  val deliveryComplications: Set<Int> = emptySet(),
  val lastDeliveryDuration: Int? = null,
  val lastDeliveryType: Int? = null,
  val lastDeliveryPlace: Int? = null,
  val lastDeliveryOutcome: Int? = null,
  val birthWeight: Int? = null,

  // Q58–65 — self & family medical.
  val selfConditions: Set<Int> = emptySet(),
  val longTermMeds: Set<Int> = emptySet(),
  val sickleCell: Int? = null,
  val substanceUse: Set<Int> = emptySet(),
  val familyHistory: Boolean? = null,
  val familyConditions: Set<Int> = emptySet(),
  val malnutrition: Int? = null,
  val remarks: String = "",

  /** True after a blocked Next attempt — drives the banner + empty-field errors. */
  val showValidationBanner: Boolean = false,
) {

  // --- Derived ---------------------------------------------------------------

  /** Q35: 1st (<14w), 2nd (14–27w), 3rd (≥28w); null until GA known. */
  val trimester: Int?
    get() = gestationalAgeWeeks?.let {
      when {
        it < SECOND_TRIMESTER_WEEK -> 1
        it < THIRD_TRIMESTER_WEEK -> 2
        else -> 3
      }
    }

  // --- Conditional visibility -----------------------------------------------

  val showTreatmentType: Boolean get() = tookTreatment == true
  val showRchNumber: Boolean get() = rchStatus == RCH_CARD_AVAILABLE
  val showAnc1Details: Boolean get() = ancStatus == ANC1_COMPLETED
  val showLastPregnancy: Boolean get() = (gravida.toIntOrNull() ?: 0) > 1
  val showBirthWeight: Boolean get() = lastDeliveryOutcome == DELIVERY_OUTCOME_LIVE
  val showFamilyConditions: Boolean get() = familyHistory == true

  // --- Field validation ------------------------------------------------------

  private fun countError(value: String, min: Int, max: Int, required: Boolean): HealthFieldError? {
    if (value.isBlank()) return if (required) requiredOrNull else null
    val n = value.toIntOrNull() ?: return HealthFieldError.COUNT_RANGE
    return if (n < min || n > max) HealthFieldError.COUNT_RANGE else null
  }

  val gravidaError: HealthFieldError?
    get() = countError(gravida, GRAVIDA_MIN, COUNT_MAX, required = true)

  val paraError: HealthFieldError?
    get() {
      countError(para, COUNT_MIN, COUNT_MAX, required = true)?.let { return it }
      val p = para.toIntOrNull() ?: return null
      val g = gravida.toIntOrNull() ?: return null
      return if (p > g) HealthFieldError.PARA_EXCEEDS_GRAVIDA else null
    }

  val livingChildrenError: HealthFieldError?
    get() = countError(livingChildren, COUNT_MIN, COUNT_MAX, required = true)

  val abortionsError: HealthFieldError?
    get() {
      countError(abortions, COUNT_MIN, COUNT_MAX, required = true)?.let { return it }
      val a = abortions.toIntOrNull() ?: return null
      val g = gravida.toIntOrNull() ?: return null
      return if (a > g) HealthFieldError.ABORTIONS_EXCEED_GRAVIDA else null
    }

  val stillBirthsError: HealthFieldError?
    get() = countError(stillBirths, COUNT_MIN, COUNT_MAX, required = true)

  /** Q50 optional; only range + not-exceeding-living when present. */
  val deadChildrenError: HealthFieldError?
    get() {
      countError(deadChildren, COUNT_MIN, COUNT_MAX, required = false)?.let { return it }
      val d = deadChildren.toIntOrNull() ?: return null
      val living = livingChildren.toIntOrNull() ?: return null
      return if (d > living) HealthFieldError.DEAD_EXCEEDS_LIVING else null
    }

  /** Q45 cross-total: Gravida = Para + Abortions + 1 (checked when all present). */
  val gravidaTotalError: HealthFieldError?
    get() {
      val g = gravida.toIntOrNull() ?: return null
      val p = para.toIntOrNull() ?: return null
      val a = abortions.toIntOrNull() ?: return null
      return if (g != p + a + 1) HealthFieldError.GRAVIDA_TOTAL_MISMATCH else null
    }

  val anc1DateError: HealthFieldError?
    get() {
      if (!showAnc1Details) return null
      val date = anc1Date ?: return requiredOrNull
      val future = date.isAfter(LocalDate.now())
      return if (future) HealthFieldError.ANC1_DATE_INVALID else null
    }

  /** Td date ordering (Td1 ≤ Td2 ≤ Booster) and no future dates (Q44). */
  val tdDateError: HealthFieldError?
    get() {
      val dates = listOfNotNull(
        td1Date.takeIf { td1 },
        td2Date.takeIf { td2 },
        tdBoosterDate.takeIf { tdBooster },
      )
      if (dates.any { it.isAfter(LocalDate.now()) }) return HealthFieldError.TD_DATE_FUTURE
      if (td1 && td2 && td1Date != null && td2Date != null &&
        td1Date.isAfter(td2Date)
      ) {
        return HealthFieldError.TD_DATE_ORDER
      }
      if (td2 && tdBooster && td2Date != null && tdBoosterDate != null &&
        td2Date.isAfter(tdBoosterDate)
      ) {
        return HealthFieldError.TD_DATE_ORDER
      }
      return null
    }

  // --- Completion gate -------------------------------------------------------

  /** Mandatory single-select/multi-select answers still missing. */
  private val missingSelections: Boolean
    get() {
      val required = mutableListOf<Boolean>()
      required += plannedPregnancy == null
      required += tookTreatment == null
      required += (showTreatmentType && treatmentType == null)
      required += rchStatus == null
      required += (showRchNumber && rchNumber.isBlank())
      required += ancStatus == null
      required += (showAnc1Details && ancConditions.isEmpty())
      required += tdSelectionMissing
      // Last-pregnancy block (only when Gravida > 1).
      if (showLastPregnancy) {
        required += lastPregnancyWhen == null
        required += deliveryComplications.isEmpty()
        required += lastDeliveryDuration == null
        required += lastDeliveryType == null
        required += lastDeliveryPlace == null
        required += lastDeliveryOutcome == null
        required += (showBirthWeight && birthWeight == null)
      }
      required += selfConditions.isEmpty()
      required += longTermMeds.isEmpty()
      required += sickleCell == null
      required += substanceUse.isEmpty()
      required += familyHistory == null
      required += (showFamilyConditions && familyConditions.isEmpty())
      return required.any { it }
    }

  /** Q44 is answered when "None" is ticked or at least one dose is ticked. */
  private val tdSelectionMissing: Boolean
    get() = !tdNone && !td1 && !td2 && !tdBooster

  /** Gate for advancing to Summary (HH-25). */
  val isComplete: Boolean
    get() = gravidaError == null && paraError == null && livingChildrenError == null &&
      abortionsError == null && stillBirthsError == null && deadChildrenError == null &&
      gravidaTotalError == null && anc1DateError == null && tdDateError == null &&
      !missingSelections

  // --- Helpers ---------------------------------------------------------------

  private val requiredOrNull: HealthFieldError?
    get() = if (showValidationBanner) HealthFieldError.REQUIRED else null

  companion object {
    const val SECOND_TRIMESTER_WEEK = 14
    const val THIRD_TRIMESTER_WEEK = 28

    /** Q39 option: "Registered – RCH card available (input number)". */
    const val RCH_CARD_AVAILABLE = 1

    /** Q41 option: "ANC-1 completed". */
    const val ANC1_COMPLETED = 2

    /** Q56 option: "Live birth". */
    const val DELIVERY_OUTCOME_LIVE = 1

    const val GRAVIDA_MIN = 1
    const val COUNT_MIN = 0
    const val COUNT_MAX = 14

    /**
     * Q43/Q58/Q61: 1-based codes of the mutually-exclusive options
     * ("No known condition" is always first). "Don't know" is the last option
     * and is supplied per-field to [org.armman.sakhi.ui.enrollment.EnrollmentViewModel].
     */
    const val EXCLUSIVE_NONE_CODE = 1
  }
}
