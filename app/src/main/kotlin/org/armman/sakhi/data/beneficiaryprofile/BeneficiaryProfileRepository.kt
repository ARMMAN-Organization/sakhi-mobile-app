package org.armman.sakhi.data.beneficiaryprofile

import org.armman.sakhi.data.forms.FormAnswers

/**
 * Beneficiary-detail data boundary. UI depends only on this interface; the
 * backing implementation (static today, beneficiary-service API later) is bound
 * in DI. Implementations throw [NoSuchElementException] for an unknown id.
 */
interface BeneficiaryProfileRepository {
  suspend fun getBeneficiary(id: String): BeneficiaryProfile

  /**
   * The raw CHILD_REGISTRATION answers backing [id], if this beneficiary was enrolled locally on
   * this device — needed by [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel]'s
   * `prefillFromChildRegistration` (INC1/INFANT_VISIT's identity fields: name/DOB/sex/birth
   * weight/length). Default `null` so existing implementations (and every test fake already
   * written against this interface) don't need to change just to keep compiling; only
   * [ScheduleBackedBeneficiaryProfileRepository] — the one with an actual local-enrolment source —
   * overrides it.
   */
  suspend fun getChildRegistrationAnswers(id: String): FormAnswers? = null
}
