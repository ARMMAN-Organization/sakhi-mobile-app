package org.armman.sakhi.data.visitform

import java.time.LocalDate

/**
 * Top-level Visit Form stepper steps (CR-016). Field content lands with their
 * respective sub-CRs: 016b (Visit Data), 016c (Summary), 016d (Health Info +
 * Referral + submit) — 016a scaffolds the shell and gating only.
 */
enum class VisitFormStep { VISIT_DATA, SUMMARY, HEALTH_INFO, REFERRAL }

/**
 * Carried-forward context for this visit (Excel Q2/Q5/Q6/Q12/Q26/Q42/Q43 —
 * "Auto populate based on ... registration/previous visit"). Sourced from the
 * beneficiary's registration + most recent prior visit; [VisitDataState] seeds
 * its editable fields from this on load.
 */
data class VisitContext(
  /** Q2 — e.g. "ANC2", auto-derived from the visit's position in the schedule. */
  val visitTypeLabel: String,
  /** Q5 — RCH number captured at registration (editable here if missing). */
  val rchNumber: String,
  /** Q6 — most recent LMP on file. */
  val lmp: LocalDate,
  /** Q12 — only present (and thus read-only) once a prior visit captured height. */
  val heightCm: Int?,
  /** Q26 — last visit's Hb, for the ±2 g/dl stale-value confirmation prompt. */
  val previousHb: Double?,
  /** Q42 — advised place of delivery from the previous visit, if any. */
  val advisedDeliveryPlace: Int?,
  /** Q43 — sickle cell status from the previous visit/registration, if any. */
  val sickleCell: Int?,
  /**
   * CR-016c Summary banner chips — the beneficiary's recorded diagnoses
   * (`BeneficiaryProfile.diagnoses`), shown verbatim, never relabeled onto a
   * fixed chip set (avoids mis-tagging real health data via keyword guessing).
   */
  val comorbidities: List<String> = emptyList(),
)

/** Visit Data's own sub-tabs, per the design's Tests/Symptoms/History grouping. */
enum class VisitDataSubTab { TESTS, SYMPTOMS, HISTORY }

/**
 * A mid-form critical danger sign (FR-S-4.4). [messageRes] is the string
 * resource shown on the Immediate Urgency banner. Scaffolded in 016a — no
 * detection logic populates this yet; CR-016b/c will call
 * [org.armman.sakhi.ui.visitform.VisitFormViewModel.reportCriticalCondition]
 * once real clinical thresholds are wired in.
 */
data class CriticalCondition(val messageRes: Int)
