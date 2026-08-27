package org.armman.sakhi.data.enrollment

import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.lookup.LookupRepository
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val CATEGORY_CASE_TYPE = "CASE_TYPE"
private const val CATEGORY_BENEFICIARY_TYPE = "BENEFICIARY_TYPE"
private const val VALUE_CODE_MOTHER = "MOTHER"
private const val VALUE_CODE_PREGNANT_WOMAN = "PREGNANT_WOMAN"

/**
 * Everything that can stop [EnrollmentApiMapper] from producing a request — always something
 * environmental (missing session/lookup data) or a genuine cross-field rule violation, never a
 * bug in the record itself. Distinguished so the UI/sync worker can show something more specific
 * than a generic failure.
 */
sealed class EnrollmentMappingException(message: String) : Exception(message) {
  data object NoActiveSession : EnrollmentMappingException("No signed-in Sakhi session")

  data object MissingProjectId :
    EnrollmentMappingException("Signed-in session has no projectId claim")

  data class LookupNotAvailable(val categoryCode: String, val valueCode: String) :
    EnrollmentMappingException(
      "Lookup value $valueCode not found in category $categoryCode — " +
        "has it been seeded server-side yet?",
    )

  data class CrossFieldValidation(val rule: String) :
    EnrollmentMappingException("Record fails a backend validation rule: $rule")
}

/**
 * Maps a domain [EnrollmentRecord] (mother-only, this pass) to the exact `POST /beneficiaries`
 * request body. Needs [SessionStore] for `case.sakhiId`/`case.projectId` (from the JWT claims
 * captured at login) and [LookupRepository] for `beneficiaryTypeLookupId`/`caseTypeLookupId` —
 * these are never hardcoded; see [LookupRepository]'s doc for why.
 */
@Singleton
class EnrollmentApiMapper @Inject constructor(
  private val sessionStore: SessionStore,
  private val lookupRepository: LookupRepository,
) {
  private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

  /**
   * @param acknowledgeDuplicate set true only when resubmitting after the Sakhi has explicitly
   * confirmed a 409 "possible duplicate" prompt (SRS FR-S-2.4/2.5) — never true on a first submit.
   */
  suspend fun toCreateBeneficiaryRequest(
    record: EnrollmentRecord,
    acknowledgeDuplicate: Boolean = false,
  ): Result<CreateBeneficiaryRequestDto> = runCatching {
    val session = sessionStore.readSession() ?: throw EnrollmentMappingException.NoActiveSession
    val projectId = session.projectId ?: throw EnrollmentMappingException.MissingProjectId

    val caseTypeLookupId = lookupRepository.findValue(CATEGORY_CASE_TYPE, VALUE_CODE_MOTHER)?.id
      ?: throw EnrollmentMappingException.LookupNotAvailable(CATEGORY_CASE_TYPE, VALUE_CODE_MOTHER)
    val beneficiaryTypeLookupId =
      lookupRepository.findValue(CATEGORY_BENEFICIARY_TYPE, VALUE_CODE_PREGNANT_WOMAN)?.id
        ?: throw EnrollmentMappingException.LookupNotAvailable(
          CATEGORY_BENEFICIARY_TYPE,
          VALUE_CODE_PREGNANT_WOMAN,
        )

    validateMotherCrossFieldRules(record)

    CreateBeneficiaryRequestDto(
      pii = BeneficiaryPiiDto(
        // See BeneficiaryPiiDto's doc: the backend wants ONE joined `fullName` now.
        fullName = joinFullName(first = record.firstName, middle = record.middleName, last = record.lastName),
        phone = record.mobileNumber.takeIf { it.isNotBlank() },
        alternatePhone = null, // Not collected by the current form.
        // PersonalInfoState.isComplete only requires ageYears (Q20: "DOB or age — either one
        // required"), so record.dob is legitimately null whenever the Sakhi entered age directly
        // instead of a birth date — but the backend's pii.dateOfBirth is mandatory
        // (`pii.dateOfBirth: Invalid date` when missing, confirmed against a real 400). There's no
        // way to recover the real day/month from an age-in-years answer, so this falls back to an
        // approximate DOB (registration date minus ageYears) rather than sending nothing — flagged
        // here, not silently exact: any beneficiary registered via age-only entry will have a
        // fabricated DOB with today's month/day, not their real birth date.
        dateOfBirth = (record.dob ?: record.registrationDate.minusYears(record.ageYears.toLong()))
          .format(isoDate),
        sex = "FEMALE", // This form only registers Pregnant Woman beneficiaries (Entry selector).
        addressLine = record.address.takeIf { it.isNotBlank() },
        villageId = record.villageId,
        padaId = record.padaId,
        // Naming differs: mobile's "subCentreId" is the backend's "healthSubCentreId".
        healthSubCentreId = record.subCentreId,
        phcId = record.phcId,
        // Distinct from taluka in the API; the current geography cascade has no separate
        // health-block concept, so left unset rather than guessed.
        healthBlockId = null,
        stateId = record.stateId,
        districtId = record.districtId,
        // GeographyRepository's own doc calls this level "block/taluka" — same admin unit here.
        talukaId = record.blockId,
        rchNumber = record.healthHistory.rchNumber,
      ),
      case = BeneficiaryCaseDto(
        projectId = projectId,
        sakhiId = session.subjectId,
        caseType = "MOTHER",
        registrationDate = record.registrationDate.format(isoDate),
        // Duplicate-linking (SRS FR-S-2.5, "new pregnancy for existing beneficiary") is a
        // follow-up once the 409 confirmation UX (task #17) exists — not populated this pass.
        previousBeneficiaryId = null,
        motherBeneficiaryId = null,
        beneficiaryTypeLookupId = beneficiaryTypeLookupId,
        caseTypeLookupId = caseTypeLookupId,
        // CR-017: EnrollmentRecord.beneficiaryId is already a client-generated UUID, minted once
        // when the enrollment form starts (EnrollmentViewModel.initializePersonalInfo) and never
        // regenerated — exactly the "same value on every retry" semantics localCaseUuid needs, so
        // no new field/generation logic is needed, just reusing this one.
        localCaseUuid = record.beneficiaryId,
      ),
      motherDetails = MotherDetailsDto(
        lmpDate = record.lmp.format(isoDate),
        gravida = record.healthHistory.gravida,
        parity = record.healthHistory.para, // naming differs: mobile "para" == backend "parity"
        // naming differs: mobile "livingChildren" == backend "liveBirths"
        liveBirths = record.healthHistory.livingChildren,
        stillbirths = record.healthHistory.stillBirths,
        abortions = record.healthHistory.abortions,
        deadChildren = record.healthHistory.deadChildren,
        heightCm = record.heightCm,
        weightKg = record.weightKg,
      ),
      childDetails = null,
      consent = ConsentDto(
        // A refused consent (Q3 = No) never reaches submission — the Consent step blocks
        // forward navigation itself, so by the time a record exists to map, consent was given.
        status = "GIVEN",
        date = record.registrationDate.format(isoDate),
      ),
      acknowledgeDuplicate = acknowledgeDuplicate.takeIf { it },
    )
  }

  /**
   * Mirrors `create-beneficiary.dto.ts`'s `superRefine` cross-field rules exactly, so a bad
   * submission fails fast on-device instead of round-tripping to the server first.
   *
   * The cross-total rule is `liveBirths + stillbirths + abortions === gravida - 1` — Gravida
   * counts the current pregnancy, the three outcome figures cannot. The Health History step gates
   * on the same formula via [org.armman.sakhi.ui.enrollment.HealthHistoryState.gravidaTotalError]
   * and both share [FormObstetricRuleset.CURRENT_PREGNANCY], so the on-device gate and this check
   * cannot drift again. They have drifted twice before (`para + abortions + 1` here vs
   * `livingChildren` there; then `== gravida` on-device vs `== gravida - 1` server-side, which made
   * enrollment impossible for any woman with a prior pregnancy).
   *
   * Still FLAGGED, not silently resolved: the backend compares `deadChildren` against
   * `liveBirths`/`livingChildren` (children currently alive) while `Registration_PW_D` row 50 means
   * Para minus still births. Those are not the same count, so a record can pass the sheet's reading
   * and still fail here. Needs an ARMMAN decision, not a silent pick made on device.
   */
  private fun validateMotherCrossFieldRules(record: EnrollmentRecord) {
    val hh = record.healthHistory
    if (hh.para > hh.gravida) {
      throw EnrollmentMappingException.CrossFieldValidation("parity must not exceed gravida")
    }
    if (hh.abortions > hh.gravida) {
      throw EnrollmentMappingException.CrossFieldValidation("abortions must not exceed gravida")
    }
    val deadChildren = hh.deadChildren
    if (deadChildren != null && deadChildren > hh.livingChildren) {
      throw EnrollmentMappingException.CrossFieldValidation("deadChildren must not exceed liveBirths")
    }
    if (hh.livingChildren + hh.stillBirths + hh.abortions != hh.gravida - FormObstetricRuleset.CURRENT_PREGNANCY) {
      throw EnrollmentMappingException.CrossFieldValidation(
        "liveBirths + stillbirths + abortions must equal gravida - 1",
      )
    }
  }
}
