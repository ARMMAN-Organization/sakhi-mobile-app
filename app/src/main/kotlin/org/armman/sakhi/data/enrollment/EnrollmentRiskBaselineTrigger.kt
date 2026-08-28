package org.armman.sakhi.data.enrollment

import android.util.Log
import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EnrollBaselineTrigger"

/**
 * Turns a submitted mother-registration form into a persisted [EnrollmentRiskBaselineEntity]
 * (punch-list item 6, 2026-08-28) — the write-side counterpart of the read-only
 * [EnrollmentRiskAssessment]. See [EnrollmentRiskBaselineEntity]'s own doc for why this is an
 * additive historical record and does not change the live Beneficiaries-list badge.
 *
 * ### Failure never blocks the enrolment
 * Same contract as [org.armman.sakhi.data.schedule.MotherEnrolmentScheduleTrigger.generateFor],
 * for the same reason: a missing baseline can be lived without (the live badge above still works
 * exactly as before this table existed), a lost registration cannot. [generateFor] swallows its
 * own errors and never throws.
 */
@Singleton
class EnrollmentRiskBaselineTrigger @Inject constructor(
  private val dao: EnrollmentRiskBaselineDao,
) {

  private val gson = Gson()

  suspend fun generateFor(
    localBeneficiaryId: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ) {
    runCatching {
      val findings = EnrollmentRiskAssessment.findings(answers, registrationDate)
      val overall = EnrollmentRiskAssessment.overall(findings.map { it.riskLevel })
      dao.insertIfAbsent(
        EnrollmentRiskBaselineEntity(
          localBeneficiaryId = localBeneficiaryId,
          overallRiskLevel = overall.name,
          findingsJson = gson.toJson(findings),
          computedAtEpochMillis = System.currentTimeMillis(),
        ),
      )
    }.onFailure { error ->
      Log.w(TAG, "generateFor($localBeneficiaryId): failed to persist enrollment risk baseline, enrolment still saved", error)
    }
  }
}
