package org.armman.sakhi.data.enrollment

import java.time.Instant
import java.time.LocalDate

/**
 * Consent answers persisted with an enrollment (Excel Q1–4 + the design's
 * four willingness checkboxes). Booleans mirror the "store as yes/no"
 * developer note in the Excel spec.
 */
data class ConsentSnapshot(
  val willingPersonalInfo: Boolean,
  val willingHealthHistory: Boolean,
  val willingDiagnosticTests: Boolean,
  val understandsReferral: Boolean,
  /** Q3 — null until the design adds the radio; kept for API parity. */
  val consentReceived: Boolean?,
  /** Q4 — app-private URI of the captured consent-form photo. */
  val photoUri: String?,
)

/**
 * One submitted Pregnant Woman enrollment (Excel Q1–34; Health History
 * Q35–65 joins when CR-015c lands). Shaped as the future enrollment API is
 * expected to accept it: dropdown/radio answers as 1-based Int codes
 * (Excel: "responses stored in numbers"), free text as entered, geography
 * as ids from the geography tables.
 */
data class EnrollmentRecord(
  /** Locally generated UUID (Q8; ERD mandates UUIDs). */
  val beneficiaryId: String,
  val registrationDate: LocalDate,
  val projectName: String,

  // Q5–7 — LMP block (EDD/GA derivable, stored for API parity).
  val lmp: LocalDate,
  val edd: LocalDate,
  val gestationalAgeWeeks: Int,

  // Q12–18 — geography ids.
  val stateId: String,
  val districtId: String,
  val blockId: String,
  val villageId: String,
  val padaId: String,
  val phcId: String,
  val subCentreId: String,

  // Q19–22 — identity.
  val firstName: String,
  val middleName: String,
  val lastName: String,
  val dob: LocalDate?,
  val ageYears: Int,
  val address: String,
  val mobileNumber: String,

  // Q23–34 — demographics (1-based option codes / integers).
  val phoneOwner: Int,
  val networkAvailability: Int,
  val educationSelf: Int,
  val educationPartner: Int,
  val partnerOccupation: Int,
  val yearsInVillage: Int,
  val migrationPattern: Int,
  val incomeBand: Int,
  val religion: Int,
  val category: Int,
  val householdMembers: Int,
  val childrenUnderFive: Int,

  val consent: ConsentSnapshot,

  /** Q35–65 — Health History (CR-015c). */
  val healthHistory: HealthHistorySnapshot,

  /** Device-side submission moment — future sync ordering key. */
  val submittedAt: Instant,
)

/**
 * Health History answers (Excel Q35–65). Single-selects are 1-based codes;
 * multi-selects are sets of 1-based codes (API expands to per-condition
 * booleans). Conditional fields are null/empty when their gate is not met.
 * Risk severity / referral actions are computed later (GoRules risk CR).
 */
data class HealthHistorySnapshot(
  val trimester: Int?,
  val plannedPregnancy: Int,
  val tookTreatment: Boolean,
  val treatmentType: Int?,
  val rchStatus: Int,
  val rchNumber: String?,
  val ancStatus: Int,
  val anc1Date: LocalDate?,
  val ancConditions: Set<Int>,
  val tdNone: Boolean,
  val td1Date: LocalDate?,
  val td2Date: LocalDate?,
  val tdBoosterDate: LocalDate?,
  val gravida: Int,
  val para: Int,
  val livingChildren: Int,
  val abortions: Int,
  val stillBirths: Int,
  val deadChildren: Int?,
  val lastPregnancyWhen: Int?,
  val deliveryComplications: Set<Int>,
  val lastDeliveryDuration: Int?,
  val lastDeliveryType: Int?,
  val lastDeliveryPlace: Int?,
  val lastDeliveryOutcome: Int?,
  val birthWeight: Int?,
  val selfConditions: Set<Int>,
  val longTermMeds: Set<Int>,
  val sickleCell: Int,
  val substanceUse: Set<Int>,
  val familyHistory: Boolean,
  val familyConditions: Set<Int>,
  val malnutrition: Int?,
  val remarks: String?,
)
