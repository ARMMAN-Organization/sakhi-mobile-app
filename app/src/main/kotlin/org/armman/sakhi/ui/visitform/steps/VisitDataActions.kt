package org.armman.sakhi.ui.visitform.steps

import java.time.LocalDate

/** Callbacks for the Visit Data step (Q1–56) — flat, mirroring the VM API. */
data class VisitDataActions(
  // Q1-4
  val onVisitDate: (LocalDate) -> Unit,
  val onMetBeneficiary: (Boolean) -> Unit,
  val onNotMetReason: (Int) -> Unit,
  // Q5-11
  val onRchNumber: (String) -> Unit,
  val onLmp: (LocalDate) -> Unit,
  val onHasSonographyReport: (Boolean) -> Unit,
  val onSonographyPhoto: () -> Unit,
  // Tests: Q12-16, Q23-32
  val onHeight: (String) -> Unit,
  val onWeight: (String) -> Unit,
  val onMuac: (String) -> Unit,
  val onBpSystolic: (String) -> Unit,
  val onBpDiastolic: (String) -> Unit,
  val onTemperature: (String) -> Unit,
  val onHemoglobin: (String) -> Unit,
  val onConfirmHemoglobin: () -> Unit,
  val onLastMealHours: (String) -> Unit,
  val onBloodGlucose: (String) -> Unit,
  val onUrineTest: (Int) -> Unit,
  val onFetalMovements: (Int) -> Unit,
  val onFetalHeartRate: (String) -> Unit,
  val onFundalHeight: (String) -> Unit,
  // Symptoms: Q17-22
  val onDangerSign: (Int) -> Unit,
  val onPalmNails: (Int) -> Unit,
  val onSclera: (Int) -> Unit,
  val onSkin: (Int) -> Unit,
  val onSwelling: (Int) -> Unit,
  val onDehydration: (Int) -> Unit,
  // History: Td doses (Q33)
  val onTdNone: (Boolean) -> Unit,
  val onTd1: (Boolean) -> Unit,
  val onTd1Date: (LocalDate) -> Unit,
  val onTd2: (Boolean) -> Unit,
  val onTd2Date: (LocalDate) -> Unit,
  val onTdBooster: (Boolean) -> Unit,
  val onTdBoosterDate: (LocalDate) -> Unit,
  // History: supplements & adherence (Q34-39)
  val onFoodConsumed: (Int) -> Unit,
  val onFoodAvoided: (Int) -> Unit,
  val onTakingIfa: (Boolean) -> Unit,
  val onIfaTablets: (String) -> Unit,
  val onIfaReason: (Int) -> Unit,
  val onCalcium: (Boolean) -> Unit,
  // History: birth prep & mental health (Q40-56)
  val onVisitedFacility: (Boolean) -> Unit,
  val onLastAncDate: (LocalDate) -> Unit,
  val onDeliveryPlace: (Int) -> Unit,
  val onSickleCell: (Int) -> Unit,
  val onFamilyPlanning: (Int) -> Unit,
  val onFeelingStressed: (Boolean) -> Unit,
  val onFamilySupport: (Boolean) -> Unit,
  val onPlanningMigration: (Boolean) -> Unit,
  val onUsgDone: (Boolean) -> Unit,
  val onUsgDate: (LocalDate) -> Unit,
  val onUsgType: (Int) -> Unit,
  val onUsgFinding: (Int) -> Unit,
  val onTransportShared: (Boolean) -> Unit,
  val onFundsArranged: (Boolean) -> Unit,
  val onBirthCompanion: (Boolean) -> Unit,
  val onRemarks: (String) -> Unit,
  val onCounsellingTopic: (Int) -> Unit,
)
