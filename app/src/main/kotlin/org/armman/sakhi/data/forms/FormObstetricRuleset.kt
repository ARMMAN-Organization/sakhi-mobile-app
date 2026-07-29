package org.armman.sakhi.data.forms

/**
 * Obstetric-history consistency rules, checked live as the Sakhi types so a wrong figure is
 * corrected in place instead of blocking Submit later with nothing to act on.
 *
 * **These deliberately mirror the rules the submission path already enforces**
 * ([DynamicFormSubmissionMapper.validateMotherCrossFieldRules] and, for the static flow,
 * `EnrollmentApiMapper.validateMotherCrossFieldRules`), not the wording of the form spec — see the
 * conflict noted below. Enforcing anything stricter or looser here would let a form pass the UI and
 * then fail at submit, which is a worse failure than the one this fixes.
 *
 * | Rule | Also enforced at |
 * |------|------------------|
 * | `Living children + Still births + Abortions == Gravida` | `DynamicFormSubmissionMapper`, backend |
 * | `Para <= Gravida` | `EnrollmentApiMapper`, backend |
 * | `Abortions <= Gravida` | `EnrollmentApiMapper`, backend |
 * | `Dead children <= Living children` | `EnrollmentApiMapper`, backend |
 *
 * **Known spec conflict — needs an ARMMAN decision.** `Registration_PW_D` row 45 states
 * `Gravida = Live birth + Abortion + Still birth + 1` (counting the current pregnancy) and row 50
 * states `Dead children <= live birth`, where "live birth" is Para minus still births rather than
 * *living* children. Both differ from what the API accepts: the `+ 1` is absent, and living children
 * stands in for live births. The API contract wins here because it is what actually blocks a
 * submission; if ARMMAN confirms the spec, [GRAVIDA_TOTAL] and the dead-children rule change
 * together with the backend, and the constant below is the only arithmetic to revisit.
 *
 * A rule is skipped whenever any figure it needs is blank or unparseable: the Sakhi is part-way
 * through a set of related boxes, and an error that fires before she could possibly have finished
 * teaches her to ignore errors. Required-ness is a separate, already-enforced gate.
 *
 * Row 47's "L < P" and row 48's ">= 2" entries are **risk** classifications, not validations, and are
 * deliberately not enforced — flagging them as errors would block registering exactly the high-risk
 * pregnancy the risk logic exists to escalate.
 */
object FormObstetricRuleset {

  const val GRAVIDA = "gravida_total_number_of_pregnancies"
  const val PARA = "para_number_of_births_after_24_weeks"
  const val LIVING_CHILDREN = "living_children"
  const val ABORTIONS = "abortions_pregnancy_losses_before_24_weeks"
  const val STILL_BIRTHS = "still_births"
  const val DEAD_CHILDREN = "dead_children"

  /** Which rule an answer breaks. Mapped to a localized message by the UI layer. */
  enum class Violation {
    /** Living children + still births + abortions don't add up to Gravida. */
    GRAVIDA_TOTAL,

    /** Para is higher than Gravida. */
    PARA_EXCEEDS_GRAVIDA,

    /** Abortions are higher than Gravida. */
    ABORTIONS_EXCEED_GRAVIDA,

    /** Dead children are higher than living children. */
    DEAD_CHILDREN_EXCEED_LIVING,
  }

  /**
   * The rule [questionCode]'s current answer breaks, or null if it's fine or not yet checkable.
   *
   * Reported against the field the Sakhi should change — Para's own message for "Para is higher than
   * Gravida", not Gravida's — so the error appears where she is typing.
   */
  fun violationFor(questionCode: String, answers: FormAnswers): Violation? {
    val gravida = intAnswer(answers, GRAVIDA)
    val para = intAnswer(answers, PARA)
    val living = intAnswer(answers, LIVING_CHILDREN)
    val abortions = intAnswer(answers, ABORTIONS)
    val stillBirths = intAnswer(answers, STILL_BIRTHS)
    val deadChildren = intAnswer(answers, DEAD_CHILDREN)

    return when (questionCode) {
      GRAVIDA -> {
        if (gravida == null || living == null || stillBirths == null || abortions == null) return null
        Violation.GRAVIDA_TOTAL.takeIf { living + stillBirths + abortions != gravida }
      }

      PARA -> {
        if (para == null || gravida == null) return null
        Violation.PARA_EXCEEDS_GRAVIDA.takeIf { para > gravida }
      }

      ABORTIONS -> {
        if (abortions == null || gravida == null) return null
        Violation.ABORTIONS_EXCEED_GRAVIDA.takeIf { abortions > gravida }
      }

      DEAD_CHILDREN -> {
        if (deadChildren == null || living == null) return null
        Violation.DEAD_CHILDREN_EXCEED_LIVING.takeIf { deadChildren > living }
      }

      else -> null
    }
  }

  /**
   * Gravida the other answers imply (`Living children + Still births + Abortions`), or null while
   * any of them is missing. Shown alongside [Violation.GRAVIDA_TOTAL] so the message names the
   * expected figure instead of leaving the Sakhi to work the arithmetic out.
   */
  fun expectedGravida(answers: FormAnswers): Int? {
    val living = intAnswer(answers, LIVING_CHILDREN) ?: return null
    val stillBirths = intAnswer(answers, STILL_BIRTHS) ?: return null
    val abortions = intAnswer(answers, ABORTIONS) ?: return null
    return living + stillBirths + abortions
  }

  /** True when no field in [fields] breaks an obstetric rule — the next/submit gate. */
  fun allValid(fields: List<FormFieldSchema>, answers: FormAnswers): Boolean =
    fields.all { violationFor(it.questionCode, answers) == null }

  private fun intAnswer(answers: FormAnswers, questionCode: String): Int? =
    answers.valueOf(questionCode)?.takeIf { it.isNotBlank() }?.toIntOrNull()
}
