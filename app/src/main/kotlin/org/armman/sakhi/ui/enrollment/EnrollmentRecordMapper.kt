package org.armman.sakhi.ui.enrollment

import org.armman.sakhi.data.enrollment.ConsentSnapshot
import org.armman.sakhi.data.enrollment.EnrollmentRecord
import org.armman.sakhi.data.enrollment.HealthHistorySnapshot
import java.time.Instant

/**
 * Maps the in-memory draft to the persisted record. Callers must gate on
 * [PersonalInfoState.isComplete] first — mandatory answers are require()d
 * here so an incomplete draft can never be silently persisted.
 */
fun EnrollmentUiState.toEnrollmentRecord(submittedAt: Instant = Instant.now()): EnrollmentRecord {
  val info = personalInfo
  require(info.isComplete) { "Cannot build a record from an incomplete draft" }
  require(healthHistory.isComplete) { "Cannot build a record from incomplete health history" }
  return EnrollmentRecord(
    beneficiaryId = info.beneficiaryId,
    registrationDate = info.registrationDate,
    projectName = info.projectName,
    lmp = requireNotNull(info.lmp),
    edd = requireNotNull(info.edd),
    gestationalAgeWeeks = requireNotNull(info.gestationalAgeWeeks),
    stateId = requireNotNull(info.stateId),
    districtId = requireNotNull(info.districtId),
    blockId = requireNotNull(info.blockId),
    villageId = requireNotNull(info.villageId),
    padaId = requireNotNull(info.padaId),
    phcId = requireNotNull(info.phcId),
    subCentreId = requireNotNull(info.subCentreId),
    firstName = info.firstName.trim(),
    middleName = info.middleName.trim(),
    lastName = info.lastName.trim(),
    dob = info.dob,
    ageYears = requireNotNull(info.ageYears),
    address = info.address.trim(),
    mobileNumber = info.mobileNumber,
    phoneOwner = requireNotNull(info.phoneOwner),
    networkAvailability = requireNotNull(info.networkAvailability),
    educationSelf = requireNotNull(info.educationSelf),
    educationPartner = requireNotNull(info.educationPartner),
    partnerOccupation = requireNotNull(info.partnerOccupation),
    yearsInVillage = requireNotNull(info.yearsInVillage.toIntOrNull()),
    migrationPattern = requireNotNull(info.migrationPattern),
    incomeBand = requireNotNull(info.incomeBand),
    religion = requireNotNull(info.religion),
    category = requireNotNull(info.category),
    householdMembers = requireNotNull(info.householdMembers.toIntOrNull()),
    childrenUnderFive = requireNotNull(info.childrenUnderFive.toIntOrNull()),
    consent = ConsentSnapshot(
      willingPersonalInfo = consent.willingPersonalInfo,
      willingHealthHistory = consent.willingHealthHistory,
      willingDiagnosticTests = consent.willingDiagnosticTests,
      understandsReferral = consent.understandsReferral,
      consentReceived = consent.consentReceived,
      photoUri = consent.photoUri,
    ),
    healthHistory = healthHistory.toSnapshot(),
    heightCm = healthHistory.heightCm.toDoubleOrNull(),
    weightKg = healthHistory.weightKg.toDoubleOrNull(),
    submittedAt = submittedAt,
  )
}

private fun HealthHistoryState.toSnapshot(): HealthHistorySnapshot = HealthHistorySnapshot(
  trimester = trimester,
  plannedPregnancy = requireNotNull(plannedPregnancy),
  tookTreatment = requireNotNull(tookTreatment),
  treatmentType = treatmentType.takeIf { showTreatmentType },
  rchStatus = requireNotNull(rchStatus),
  rchNumber = rchNumber.trim().takeIf { showRchNumber && it.isNotBlank() },
  ancStatus = requireNotNull(ancStatus),
  anc1Date = anc1Date.takeIf { showAnc1Details },
  ancConditions = if (showAnc1Details) ancConditions else emptySet(),
  tdNone = tdNone,
  td1Date = td1Date.takeIf { td1 },
  td2Date = td2Date.takeIf { td2 },
  tdBoosterDate = tdBoosterDate.takeIf { tdBooster },
  gravida = requireNotNull(gravida.toIntOrNull()),
  para = requireNotNull(para.toIntOrNull()),
  livingChildren = requireNotNull(livingChildren.toIntOrNull()),
  abortions = requireNotNull(abortions.toIntOrNull()),
  stillBirths = requireNotNull(stillBirths.toIntOrNull()),
  deadChildren = deadChildren.toIntOrNull(),
  lastPregnancyWhen = lastPregnancyWhen.takeIf { showLastPregnancy },
  deliveryComplications = if (showLastPregnancy) deliveryComplications else emptySet(),
  lastDeliveryDuration = lastDeliveryDuration.takeIf { showLastPregnancy },
  lastDeliveryType = lastDeliveryType.takeIf { showLastPregnancy },
  lastDeliveryPlace = lastDeliveryPlace.takeIf { showLastPregnancy },
  lastDeliveryOutcome = lastDeliveryOutcome.takeIf { showLastPregnancy },
  birthWeight = birthWeight.takeIf { showLastPregnancy && showBirthWeight },
  selfConditions = selfConditions,
  longTermMeds = longTermMeds,
  sickleCell = requireNotNull(sickleCell),
  substanceUse = substanceUse,
  familyHistory = requireNotNull(familyHistory),
  familyConditions = if (showFamilyConditions) familyConditions else emptySet(),
  malnutrition = malnutrition,
  remarks = remarks.trim().takeIf { it.isNotBlank() },
)
