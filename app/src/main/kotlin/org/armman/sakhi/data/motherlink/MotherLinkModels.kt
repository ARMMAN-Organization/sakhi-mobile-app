package org.armman.sakhi.data.motherlink

import java.time.LocalDate

/**
 * One selectable mother in the child-enrollment mother picker (CR-031) — the domain shape, already
 * decoded from `GET /api/v1/beneficiaries`.
 *
 * [id] is the beneficiary **case UUID**. It is what lands in the `mother_beneficiary_id` answer and
 * what `POST /beneficiaries` validates as `case.motherBeneficiaryId` (typed `z.string().uuid()`
 * server-side) — so the picker must store this and never a display name or a human-readable number.
 * The form spec (row 9) describes this field as an integer; the backend disagrees and the backend
 * wins. See CR-024.
 *
 * Every geography property is a `geographyUnitId`. They are only safe to write into the form's
 * geography answers if the same id also appears in the active [FormVersion.geography]
 * (see [MotherPrefill]) — a unit the backend didn't ship for this Sakhi would be rejected with
 * `does not refer to a known geography unit` (HTTP 422).
 *
 * [dateOfBirth] is nullable on purpose: a mother row with an unparseable or missing DOB must still
 * be selectable (bad DOBs exist in the live data), it just can't prefill that one field.
 */
data class LinkedMother(
  val id: String,
  val fullName: String,
  val dateOfBirth: LocalDate?,
  val currentPhase: String,
  val registrationDate: LocalDate?,
  val stateId: String?,
  val districtId: String?,
  val talukaId: String?,
  val villageId: String?,
  val padaId: String?,
  val phcId: String?,
  val healthSubCentreId: String?,
) {
  /**
   * Whether the mother's journey has not yet reached delivery, i.e. no delivery has been recorded
   * against her. Per decision D1 these mothers are still selectable — every mother in the current
   * environment is `ANC`, so excluding them would empty the picker — but the row is badged so the
   * Sakhi knows the delivery-related answers won't be prefilled.
   */
  val deliveryNotRecorded: Boolean get() = currentPhase.equals(PHASE_ANC, ignoreCase = true)

  private companion object {
    const val PHASE_ANC = "ANC"
  }
}

/**
 * The mother's inherited consent state, read from `GET /api/v1/beneficiaries/:id`
 * (`consentRecords`, of which the backend returns only the latest).
 *
 * Deliberately a separate type from [LinkedMother]: the list endpoint does not carry consent, so
 * this arrives from a second call that is allowed to fail (offline) without failing the selection.
 */
data class LinkedMotherConsent(val consentGiven: Boolean)

/**
 * One already-resolved lookup answer inside [MotherSocioDemographics] — `beneficiary-service`'s own
 * `categoryCode`/`label` for a socio-demographic answer, not yet translated to the
 * CHILD_REGISTRATION form's `value_code`. [org.armman.sakhi.data.lookup.LookupLabelMatcher] does
 * that translation at prefill time, against the live form schema's own options for the target
 * question — see [MotherPrefill].
 */
data class MotherLookupAnswer(
  val categoryCode: String,
  val label: String,
)

/**
 * Rows 21–34 of the mother's socio-demographic details (CR-032), read from
 * `GET /beneficiaries/:id`'s `socioDemographics` block (+ `pii.address`/`pii.mobileNumber`, which
 * only the detail response carries).
 *
 * Every property is independently nullable — a Sakhi may not have answered every question at the
 * mother's own registration, and a missing value here must simply not prefill that one field,
 * exactly like [LinkedMother]'s optional geography levels.
 */
data class MotherSocioDemographics(
  val address: String?,
  val mobileNumber: String?,
  val phoneOwner: MotherLookupAnswer?,
  val mobileNetworkAvailability: MotherLookupAnswer?,
  val educationLevel: MotherLookupAnswer?,
  val partnerEducationLevel: MotherLookupAnswer?,
  val partnerOccupation: MotherLookupAnswer?,
  val yearsInVillage: Int?,
  val migrationPattern: MotherLookupAnswer?,
  val monthlyIncome: MotherLookupAnswer?,
  val religion: MotherLookupAnswer?,
  val socialCategory: MotherLookupAnswer?,
  val familyMembersCount: Int?,
  val childrenUnder5Count: Int?,
)
