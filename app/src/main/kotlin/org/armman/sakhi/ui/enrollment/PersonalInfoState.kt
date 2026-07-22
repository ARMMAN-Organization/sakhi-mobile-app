package org.armman.sakhi.ui.enrollment

import org.armman.sakhi.data.geography.GeographyUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Field-level validation outcomes surfaced by the Personal Info step. */
enum class FieldError {
  /** Mandatory field left empty (shown after a blocked Next attempt). */
  REQUIRED,

  /** LMP in the future (Excel Q5). */
  LMP_FUTURE,

  /** Registration − LMP must be strictly greater than 30 days (Q5). */
  LMP_TOO_RECENT,

  /** Registration − LMP must be strictly less than 240 days (Q5). */
  LMP_TOO_OLD,

  /** Names accept letters and spaces only (Q19). */
  NAME_SPECIAL_CHARS,

  /** Age must fall within 10–50 years (Q20). */
  AGE_OUT_OF_RANGE,

  /** Mobile number must be exactly 10 digits (Q22). */
  MOBILE_LENGTH,

  /** Household members must be within 2–15 (Q33). */
  HOUSEHOLD_RANGE,

  /** Children under 5 cannot exceed household members (Q34). */
  CHILDREN_EXCEED_HOUSEHOLD,

  /** Years in village must be a non-negative number (Q28). */
  YEARS_INVALID,
}

/**
 * Personal Info step (Excel Q5–34). Dropdown answers are stored as 1-based
 * option codes (Excel: "responses stored in numbers"); display text lives in
 * string resources keyed by the same order.
 */
data class PersonalInfoState(
  // Q5–7 — LMP block. lmpKnown: the "No" radio is a UI no-op (decision
  // 2026-07-13) so this stays true until ARMMAN defines an unknown-LMP flow.
  val lmpKnown: Boolean = true,
  val lmp: LocalDate? = null,

  // Q8–11 — auto identifiers.
  val beneficiaryId: String = "",
  val registrationDate: LocalDate = LocalDate.now(),
  val projectName: String = "",

  // Q12–18 — geography selections (ids) + option lists.
  val stateId: String? = null,
  val districtId: String? = null,
  val blockId: String? = null,
  val villageId: String? = null,
  val padaId: String? = null,
  val phcId: String? = null,
  val subCentreId: String? = null,
  val states: List<GeographyUnit> = emptyList(),
  val districts: List<GeographyUnit> = emptyList(),
  val blocks: List<GeographyUnit> = emptyList(),
  val villages: List<GeographyUnit> = emptyList(),
  val padas: List<GeographyUnit> = emptyList(),
  val phcs: List<GeographyUnit> = emptyList(),
  val subCentres: List<GeographyUnit> = emptyList(),

  // Q19–22 — identity.
  val firstName: String = "",
  val middleName: String = "",
  val lastName: String = "",
  val dob: LocalDate? = null,
  /** Entered directly OR auto-derived from [dob] (floor). */
  val ageYears: Int? = null,
  val address: String = "",
  val mobileNumber: String = "",

  // Q23–34 — demographics (1-based option codes).
  val phoneOwner: Int? = null,
  val networkAvailability: Int? = null,
  val educationSelf: Int? = null,
  val educationPartner: Int? = null,
  val partnerOccupation: Int? = null,
  val yearsInVillage: String = "",
  val migrationPattern: Int? = null,
  val incomeBand: Int? = null,
  val religion: Int? = null,
  val category: Int? = null,
  val householdMembers: String = "",
  val childrenUnderFive: String = "",

  /** True after a blocked Next attempt — drives the red banner + empty-field errors. */
  val showValidationBanner: Boolean = false,
) {

  // --- Derived values (Excel formulas, computed at app level) ---------------

  /** Q6: EDD = LMP + 280 days. */
  val edd: LocalDate? get() = lmp?.plusDays(EDD_OFFSET_DAYS)

  /** Q7: gestational age = floor((registration date − LMP) / 7) weeks. */
  val gestationalAgeWeeks: Int?
    get() = lmp?.let { (ChronoUnit.DAYS.between(it, registrationDate) / DAYS_PER_WEEK).toInt() }

  // --- Field validation ------------------------------------------------------

  val lmpError: FieldError?
    get() {
      val value = lmp ?: return requiredOrNull
      val days = ChronoUnit.DAYS.between(value, registrationDate)
      return when {
        days < 0 -> FieldError.LMP_FUTURE
        days <= MIN_LMP_DAYS -> FieldError.LMP_TOO_RECENT
        days >= MAX_LMP_DAYS -> FieldError.LMP_TOO_OLD
        else -> null
      }
    }

  val firstNameError: FieldError? get() = nameError(firstName)
  val middleNameError: FieldError? get() = nameError(middleName, required = false)
  val lastNameError: FieldError? get() = nameError(lastName)

  /** Q20: DOB or age — either one required; range 10–50. */
  val ageError: FieldError?
    get() {
      val age = ageYears ?: return requiredOrNull
      return if (age < MIN_AGE || age > MAX_AGE) FieldError.AGE_OUT_OF_RANGE else null
    }

  val addressError: FieldError? get() = if (address.isBlank()) requiredOrNull else null

  val mobileError: FieldError?
    get() = when {
      mobileNumber.isBlank() -> requiredOrNull
      mobileNumber.length != MOBILE_DIGITS || !mobileNumber.all(Char::isDigit) ->
        FieldError.MOBILE_LENGTH
      else -> null
    }

  val yearsInVillageError: FieldError?
    get() = when {
      yearsInVillage.isBlank() -> requiredOrNull
      yearsInVillage.toIntOrNull()?.takeIf { it >= 0 } == null -> FieldError.YEARS_INVALID
      else -> null
    }

  val householdMembersError: FieldError?
    get() = when {
      householdMembers.isBlank() -> requiredOrNull
      householdMembers.toIntOrNull() !in MIN_HOUSEHOLD..MAX_HOUSEHOLD -> FieldError.HOUSEHOLD_RANGE
      else -> null
    }

  val childrenUnderFiveError: FieldError?
    get() {
      if (childrenUnderFive.isBlank()) return requiredOrNull
      val children = childrenUnderFive.toIntOrNull() ?: return FieldError.CHILDREN_EXCEED_HOUSEHOLD
      val household = householdMembers.toIntOrNull() ?: return null
      return if (children > household) FieldError.CHILDREN_EXCEED_HOUSEHOLD else null
    }

  /** Mandatory dropdown/date selections still missing. */
  private val missingSelections: Boolean
    get() = listOf(
      stateId, districtId, blockId, villageId, padaId, phcId, subCentreId,
    ).any { it == null } ||
      listOf(
        phoneOwner, networkAvailability, educationSelf, educationPartner,
        partnerOccupation, migrationPattern, incomeBand, religion, category,
      ).any { it == null }

  /** Gate for advancing to Health History (PI-25). */
  val isComplete: Boolean
    get() = lmp != null && lmpError == null &&
      firstName.isNotBlank() && firstNameError == null &&
      lastName.isNotBlank() && lastNameError == null &&
      middleNameError == null &&
      ageYears != null && ageError == null &&
      address.isNotBlank() &&
      mobileError == null && mobileNumber.isNotBlank() &&
      yearsInVillageError == null &&
      householdMembersError == null &&
      childrenUnderFiveError == null &&
      !missingSelections

  /**
   * Every currently-failing validation rule, in question order, as a plain-language sentence —
   * see [org.armman.sakhi.ui.components.ValidationErrorBanner]. Replaces a single generic
   * "complete all fields" message that gave no clue which of this step's 20+ fields (7 geography
   * dropdowns + 9 demographic dropdowns + several text fields) was actually still missing.
   */
  val validationErrors: List<String>
    get() = buildList {
      if (lmp == null) {
        add("Enter the LMP date")
      } else {
        lmpError?.let {
          add(
            when (it) {
              FieldError.LMP_FUTURE -> "LMP date cannot be in the future"
              FieldError.LMP_TOO_RECENT ->
                "LMP date is too recent (must be more than $MIN_LMP_DAYS days before registration)"
              FieldError.LMP_TOO_OLD ->
                "LMP date is too old (must be less than $MAX_LMP_DAYS days before registration)"
              else -> "Check the LMP date"
            },
          )
        }
      }
      if (firstName.isBlank()) {
        add("Enter the beneficiary's first name")
      } else {
        firstNameError?.let { add("First name can only contain letters and spaces") }
      }
      middleNameError?.let { add("Middle name can only contain letters and spaces") }
      if (lastName.isBlank()) {
        add("Enter the beneficiary's last name")
      } else {
        lastNameError?.let { add("Last name can only contain letters and spaces") }
      }
      if (ageYears == null) {
        add("Enter the beneficiary's age or date of birth")
      } else {
        ageError?.let { add("Age must be between $MIN_AGE and $MAX_AGE years") }
      }
      if (address.isBlank()) add("Enter the beneficiary's address")
      if (mobileNumber.isBlank()) {
        add("Enter the beneficiary's mobile number")
      } else {
        mobileError?.let { add("Mobile number must be exactly $MOBILE_DIGITS digits") }
      }
      if (yearsInVillage.isBlank()) {
        add("Enter years lived in the village")
      } else {
        yearsInVillageError?.let { add("Years in village must be a valid non-negative number") }
      }
      if (householdMembers.isBlank()) {
        add("Enter the number of household members")
      } else {
        householdMembersError?.let {
          add("Household members must be between $MIN_HOUSEHOLD and $MAX_HOUSEHOLD")
        }
      }
      if (childrenUnderFive.isBlank()) {
        add("Enter the number of children under five")
      } else {
        childrenUnderFiveError?.let { add("Children under five cannot exceed household members") }
      }
      if (stateId == null) add("Select the state")
      if (districtId == null) add("Select the district")
      if (blockId == null) add("Select the block/taluka")
      if (villageId == null) add("Select the village")
      if (padaId == null) add("Select the pada")
      if (phcId == null) add("Select the PHC")
      if (subCentreId == null) add("Select the sub-centre")
      if (phoneOwner == null) add("Select who owns the phone")
      if (networkAvailability == null) add("Select network availability")
      if (educationSelf == null) add("Select the beneficiary's education level")
      if (educationPartner == null) add("Select the partner's education level")
      if (partnerOccupation == null) add("Select the partner's occupation")
      if (migrationPattern == null) add("Select the migration pattern")
      if (incomeBand == null) add("Select the income band")
      if (religion == null) add("Select the religion")
      if (category == null) add("Select the category")
    }

  // --- Helpers ---------------------------------------------------------------

  /** Empty-mandatory errors appear only after a blocked Next attempt. */
  private val requiredOrNull: FieldError?
    get() = if (showValidationBanner) FieldError.REQUIRED else null

  private fun nameError(value: String, required: Boolean = true): FieldError? = when {
    value.isBlank() -> if (required) requiredOrNull else null
    !value.all { it.isLetter() || it.isWhitespace() } -> FieldError.NAME_SPECIAL_CHARS
    else -> null
  }

  companion object {
    const val EDD_OFFSET_DAYS = 280L
    const val DAYS_PER_WEEK = 7L
    const val MIN_LMP_DAYS = 30L
    const val MAX_LMP_DAYS = 240L
    const val MIN_AGE = 10
    const val MAX_AGE = 50
    const val MOBILE_DIGITS = 10
    const val MIN_HOUSEHOLD = 2
    const val MAX_HOUSEHOLD = 15

    /** Age auto-derivation from DOB: floor of full years at [today]. */
    fun ageFromDob(dob: LocalDate, today: LocalDate): Int =
      ChronoUnit.YEARS.between(dob, today).toInt()
  }
}
