package org.armman.sakhi.data.enrollment

import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.forms.DOB_QUESTION_CODE
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes as Q
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes.ValueCode as V
import org.armman.sakhi.data.forms.registrationDateAnswer
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The demographic/obstetric condition a baseline finding was raised for (CR-034). */
enum class EnrollmentRiskCondition {
  UNDERAGE,
  OVERAGE,
  HIGH_GRAVIDITY,
  LIVING_CHILDREN_BELOW_PARA,
  RECURRENT_ABORTIONS,
  PREVIOUS_STILL_BIRTH,
  PRETERM_LAST_DELIVERY,
  CAESAREAN_SHORT_INTERVAL,
  LAST_OUTCOME_STILL_BIRTH,
  SICKLE_CELL_DISEASE,
  SICKLE_CELL_TRAIT,
  ANC1_REPORTED_CONDITION,
  SELF_MEDICAL_CONDITION,
  HOME_DELIVERY,
  LOW_BIRTH_WEIGHT,
  PREVIOUS_DELIVERY_COMPLICATIONS,
  SUBSTANCE_USE,
}

/** What the SRS asks the Sakhi to DO about a finding — Excel column J ("Risk action"). */
enum class EnrollmentRiskAction { REFERRAL, HEALTH_MESSAGE, BOTH }

/**
 * One baseline risk condition detected from the enrollment answers.
 *
 * [isPermanent] carries the Excel's "permanent risk condition tag" (age, row 20): the condition can
 * never be cleared by a later visit, unlike a vital that can normalise. We model the Excel's
 * "Severe" tier as [RiskLevel.HIGH] + this flag rather than adding a `SEVERE` value to [RiskLevel]
 * — a fifth level would ripple through badges, filters and every risk sort in the app for one rule.
 */
data class EnrollmentRiskFinding(
  val condition: EnrollmentRiskCondition,
  val riskLevel: RiskLevel,
  val action: EnrollmentRiskAction,
  val isPermanent: Boolean = false,
)

/**
 * Baseline (enrollment-time) risk assessment for a pregnant woman, sourced verbatim from the Excel
 * `Registration_PW_D` tab, columns I ("Risk condition calculations") and J ("Risk action") — the
 * rules cited in docs/test-cases/enrollment-baseline-risk.md.
 *
 * WHY this exists on-device: until CR-034 a newly enrolled woman read as [RiskLevel.LOW] no matter
 * what was answered — `LocalEnrolmentBeneficiarySource` hardcoded it, `rules-service` has no seeded
 * decisions, and `VisitRiskAssessment` covers VITALS only (BP/Hb/glucose/MUAC/BMI/FHR), never
 * obstetric history. A high-risk pregnancy therefore looked identical to a low-risk one on the
 * Beneficiaries list, which is the one place it must not.
 *
 * This is a pure function over [FormAnswers] — no I/O, no persistence, no Room column. The answers
 * are already loaded wherever the beneficiary is built, so evaluating on read keeps the tag from
 * drifting when answers are edited, and keeps the swap to server-side GoRules (next sprint) a change
 * of one call site rather than a migration.
 *
 * Health-message-only rules (home delivery, low birth weight, previous complications, substance use,
 * sickle cell TRAIT) are returned as [RiskLevel.LOW] findings ON PURPOSE: the Excel gives them an
 * action but no risk tier, so they must reach the Sakhi as counselling without inflating the
 * beneficiary's severity.
 */
object EnrollmentRiskAssessment {

  /**
   * Severity order for both [overall] (worst-of aggregation) and finding order — highest risk
   * first, per the app-wide convention that any risk-tagged list sorts High → Moderate → Mild → Low.
   */
  private val RISK_SEVERITY_ORDER =
    listOf(RiskLevel.HIGH, RiskLevel.MODERATE, RiskLevel.MILD, RiskLevel.LOW)

  // --- Thresholds — Excel `Registration_PW_D`, column I, verbatim ---------------

  /** Row 20: "Underage < 19 years". */
  private const val AGE_UNDERAGE_EXCLUSIVE = 19

  /** Row 20: "Overage >= 35 years". */
  private const val AGE_OVERAGE_INCLUSIVE = 35

  /** Row 45: "Gravida >= 4 -> Moderate risk (High Gravidity / Grand multipara)". */
  private const val GRAVIDA_HIGH_GRAVIDITY = 4

  /** Row 48: "High risk >= 2" pregnancy losses. */
  private const val ABORTIONS_HIGH_RISK = 2

  /** Row 49: any still birth is an HRP. */
  private const val STILL_BIRTHS_HIGH_RISK = 1

  /**
   * Every baseline finding for [answers], worst risk first. [today] is only a fallback for the age
   * reference date — the registration date answer wins when present, so a record entered weeks after
   * registration still grades the woman's age AS OF registration, not as of the read.
   */
  fun findings(answers: FormAnswers, today: LocalDate = LocalDate.now()): List<EnrollmentRiskFinding> =
    buildList {
      addAll(ageFindings(answers, today))
      addAll(obstetricCountFindings(answers))
      addAll(lastDeliveryFindings(answers))
      addAll(sickleCellFindings(answers))
      addAll(conditionFindings(answers))
      addAll(healthMessageFindings(answers))
    }.sortedByRisk()

  /** The single worst level present, or [RiskLevel.LOW] when nothing was flagged. */
  fun overall(levels: List<RiskLevel>): RiskLevel =
    RISK_SEVERITY_ORDER.firstOrNull { it in levels } ?: RiskLevel.LOW

  /** Convenience for callers that only need the badge: worst level across all [findings]. */
  fun baselineRiskLevel(answers: FormAnswers, today: LocalDate = LocalDate.now()): RiskLevel =
    overall(findings(answers, today).map { it.riskLevel })

  // --- Rules -------------------------------------------------------------------

  /** Row 20 — age risk, severe and PERMANENT, referral on first instance. */
  private fun ageFindings(answers: FormAnswers, today: LocalDate): List<EnrollmentRiskFinding> {
    val age = ageAtRegistration(answers, today) ?: return emptyList()
    val condition = when {
      age < AGE_UNDERAGE_EXCLUSIVE -> EnrollmentRiskCondition.UNDERAGE
      age >= AGE_OVERAGE_INCLUSIVE -> EnrollmentRiskCondition.OVERAGE
      else -> return emptyList()
    }
    return listOf(
      EnrollmentRiskFinding(condition, RiskLevel.HIGH, EnrollmentRiskAction.REFERRAL, isPermanent = true),
    )
  }

  /** Rows 45/47/48/49 — the obstetric counts. */
  private fun obstetricCountFindings(answers: FormAnswers): List<EnrollmentRiskFinding> = buildList {
    val gravida = intAnswer(answers, FormObstetricRuleset.GRAVIDA)
    val para = intAnswer(answers, FormObstetricRuleset.PARA)
    val living = intAnswer(answers, FormObstetricRuleset.LIVING_CHILDREN)
    val abortions = intAnswer(answers, FormObstetricRuleset.ABORTIONS)
    val stillBirths = intAnswer(answers, FormObstetricRuleset.STILL_BIRTHS)

    if (gravida != null && gravida >= GRAVIDA_HIGH_GRAVIDITY) {
      add(high(EnrollmentRiskCondition.HIGH_GRAVIDITY, RiskLevel.MODERATE, EnrollmentRiskAction.BOTH))
    }
    // Row 47 "High risk L < P" — needs BOTH counts; one alone says nothing.
    if (living != null && para != null && living < para) {
      add(high(EnrollmentRiskCondition.LIVING_CHILDREN_BELOW_PARA))
    }
    if (abortions != null && abortions >= ABORTIONS_HIGH_RISK) {
      add(high(EnrollmentRiskCondition.RECURRENT_ABORTIONS))
    }
    if (stillBirths != null && stillBirths >= STILL_BIRTHS_HIGH_RISK) {
      add(high(EnrollmentRiskCondition.PREVIOUS_STILL_BIRTH))
    }
  }

  /** Rows 53/54+51/56 — the last delivery's clinical history. */
  private fun lastDeliveryFindings(answers: FormAnswers): List<EnrollmentRiskFinding> = buildList {
    if (answers.valueOf(Q.LAST_DELIVERY_DURATION) == V.TERM_PRE) {
      add(
        EnrollmentRiskFinding(
          EnrollmentRiskCondition.PRETERM_LAST_DELIVERY,
          RiskLevel.HIGH,
          EnrollmentRiskAction.HEALTH_MESSAGE,
        ),
      )
    }
    // Row 54: caesarean AND row 51's interval under 3 years. When Q51 is unanswered — Gravida = 1
    // hides it — the rule does NOT fire: a caesarean alone is not the flagged condition, and
    // inventing the interval would tag women the spec does not tag.
    val caesarean = answers.valueOf(Q.LAST_DELIVERY_TYPE) == V.DELIVERY_CAESARIAN
    val shortInterval = answers.valueOf(Q.LAST_PREGNANCY_INTERVAL) == V.INTERVAL_UNDER_3_YEARS
    if (caesarean && shortInterval) {
      add(high(EnrollmentRiskCondition.CAESAREAN_SHORT_INTERVAL))
    }
    if (answers.valueOf(Q.LAST_DELIVERY_OUTCOME) == V.OUTCOME_STILL_BIRTH) {
      add(high(EnrollmentRiskCondition.LAST_OUTCOME_STILL_BIRTH))
    }
  }

  /** Row 60 — SCD is severe risk + referral; SCT is a message (refer only if the husband tests
   * positive, which this form does not capture). */
  private fun sickleCellFindings(answers: FormAnswers): List<EnrollmentRiskFinding> =
    when (answers.valueOf(Q.SICKLE_CELL_STATUS)) {
      V.SICKLE_CELL_DISEASE -> listOf(high(EnrollmentRiskCondition.SICKLE_CELL_DISEASE))
      V.SICKLE_CELL_TRAIT -> listOf(message(EnrollmentRiskCondition.SICKLE_CELL_TRAIT))
      else -> emptyList()
    }

  /** Rows 43/58 — "Mod: If positive for any condition". */
  private fun conditionFindings(answers: FormAnswers): List<EnrollmentRiskFinding> = buildList {
    if (hasRealCondition(answers, Q.ANC1_HIGH_RISK_CONDITIONS)) {
      add(
        EnrollmentRiskFinding(
          EnrollmentRiskCondition.ANC1_REPORTED_CONDITION,
          RiskLevel.MODERATE,
          EnrollmentRiskAction.REFERRAL,
        ),
      )
    }
    if (hasRealCondition(answers, Q.SELF_MEDICAL_CONDITIONS)) {
      add(
        EnrollmentRiskFinding(
          EnrollmentRiskCondition.SELF_MEDICAL_CONDITION,
          RiskLevel.MODERATE,
          EnrollmentRiskAction.HEALTH_MESSAGE,
        ),
      )
    }
  }

  /** Rows 52/55/57/61 — counselling only; these must NOT raise the beneficiary's severity. */
  private fun healthMessageFindings(answers: FormAnswers): List<EnrollmentRiskFinding> = buildList {
    if (answers.valueOf(Q.LAST_DELIVERY_PLACE) == V.DELIVERY_PLACE_HOME) {
      add(message(EnrollmentRiskCondition.HOME_DELIVERY))
    }
    if (answers.valueOf(Q.LAST_CHILD_BIRTH_WEIGHT) == V.BIRTH_WEIGHT_UNDER_2_5_KG) {
      add(message(EnrollmentRiskCondition.LOW_BIRTH_WEIGHT))
    }
    val complications = answers.multiValueOf(Q.PREVIOUS_DELIVERY_COMPLICATIONS)
      .filter { it != V.NO_COMPLICATIONS }
    if (complications.isNotEmpty()) {
      add(message(EnrollmentRiskCondition.PREVIOUS_DELIVERY_COMPLICATIONS))
    }
    // "Used earlier but stopped" still earns the counselling message — the Excel gives the whole
    // question a health-message action and only "No"/"not willing to disclose" report no use.
    val substances = answers.multiValueOf(Q.SUBSTANCE_USE)
      .filter { it != V.SUBSTANCE_NONE && it != V.SUBSTANCE_NOT_DISCLOSED }
    if (substances.isNotEmpty()) {
      add(message(EnrollmentRiskCondition.SUBSTANCE_USE))
    }
  }

  // --- Helpers -----------------------------------------------------------------

  private fun high(
    condition: EnrollmentRiskCondition,
    level: RiskLevel = RiskLevel.HIGH,
    action: EnrollmentRiskAction = EnrollmentRiskAction.BOTH,
  ) = EnrollmentRiskFinding(condition, level, action)

  private fun message(condition: EnrollmentRiskCondition) =
    EnrollmentRiskFinding(condition, RiskLevel.LOW, EnrollmentRiskAction.HEALTH_MESSAGE)

  /**
   * Whole years between the beneficiary's DOB and the registration date. Derived from
   * [DOB_QUESTION_CODE] rather than read from the age answer: the age field is a stopgap the backend
   * has renamed more than once (`age_of_the_beneficiary`, `age_years`) and does not always declare,
   * so DOB is the only answer guaranteed to be there.
   */
  private fun ageAtRegistration(answers: FormAnswers, today: LocalDate): Int? {
    val dob = parseDate(answers.valueOf(DOB_QUESTION_CODE)) ?: return null
    val reference = parseDate(answers.registrationDateAnswer()) ?: today
    if (dob.isAfter(reference)) return null
    return ChronoUnit.YEARS.between(dob, reference).toInt()
  }

  private fun parseDate(value: String?): LocalDate? {
    val raw = value?.takeIf { it.isNotBlank() } ?: return null
    return try {
      LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
      null
    }
  }

  /** True when the multiselect holds at least one real condition — "none"/"don't know" answers are
   * mutually exclusive with the rest in the UI and carry no risk. */
  private fun hasRealCondition(answers: FormAnswers, questionCode: String): Boolean =
    answers.multiValueOf(questionCode).any { it !in V.NON_CONDITION_CODES }

  private fun intAnswer(answers: FormAnswers, questionCode: String): Int? =
    answers.valueOf(questionCode)?.takeIf { it.isNotBlank() }?.toIntOrNull()

  private fun List<EnrollmentRiskFinding>.sortedByRisk(): List<EnrollmentRiskFinding> =
    sortedBy { RISK_SEVERITY_ORDER.indexOf(it.riskLevel) }
}
