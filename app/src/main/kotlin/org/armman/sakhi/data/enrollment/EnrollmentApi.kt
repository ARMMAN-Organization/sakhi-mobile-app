package org.armman.sakhi.data.enrollment

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** `pii` block of the `POST /beneficiaries` request. Name is sent as three discrete fields.
 *
 * 2026-07-22 timeline, both confirmed against real backend 400 responses (see
 * `api-calls.jsonl` and chat history — the live schema changed mid-day, not a misread):
 *  1. Earlier: backend required a single `fullName` (`pii.fullName: String must contain at
 *     least 1 character(s)` when omitted) and rejected split fields.
 *  2. Now: backend requires `firstName`/`lastName` and rejects `fullName` outright
 *     (`pii: Unrecognized key(s) in object: 'fullName'`, `pii.firstName: Required`,
 *     `pii.lastName: Required`).
 * This DTO matches contract #2, the current one. If submissions start failing again with either
 * of these messages, check the live error body before assuming this shape is still right — the
 * `/beneficiaries` PII contract has changed at least twice in one day. */
data class BeneficiaryPiiDto(
  val firstName: String,
  val middleName: String?,
  val lastName: String,
  val phone: String?,
  val alternatePhone: String?,
  val dateOfBirth: String?,
  val sex: String?,
  val addressLine: String?,
  val villageId: String?,
  val padaId: String?,
  val healthSubCentreId: String?,
  val phcId: String?,
  val healthBlockId: String?,
  val stateId: String?,
  val districtId: String?,
  val talukaId: String?,
  val rchNumber: String?,
)

/** `case` block — `beneficiaryTypeLookupId`/`caseTypeLookupId` are UUIDs resolved via
 * [org.armman.sakhi.data.lookup.LookupRepository], never hardcoded.
 *
 * `localCaseUuid` (CR-017) is a client-generated, globally-unique identifier for this case.
 * The backend enforces a unique constraint on it so that a `POST /beneficiaries` retried after
 * a dropped connection (our WorkManager sync worker does exactly this) returns the original case
 * instead of creating a duplicate beneficiary. It must stay the same value across every retry of
 * the same enrollment — see [EnrollmentApiMapper] for where it comes from. */
data class BeneficiaryCaseDto(
  val projectId: String,
  val sakhiId: String,
  val caseType: String,
  val registrationDate: String,
  val previousBeneficiaryId: String?,
  val motherBeneficiaryId: String?,
  val beneficiaryTypeLookupId: String,
  val caseTypeLookupId: String,
  val localCaseUuid: String,
)

/** `motherDetails` block — exactly the 8 fields the backend's `.strict()` Zod schema accepts.
 * `bmiAtRegistration` is deliberately absent: it's server-computed from `heightCm`+`weightKg`
 * and sending it would be rejected by `.strict()`. */
data class MotherDetailsDto(
  val lmpDate: String,
  val gravida: Int,
  val parity: Int,
  val liveBirths: Int,
  val stillbirths: Int,
  val abortions: Int,
  val deadChildren: Int?,
  val heightCm: Double?,
  val weightKg: Double?,
)

/** `childDetails` block — not sent in this pass (mother-only); shape kept here so the child
 * enrollment follow-up (task #20) can reuse this file's DTOs directly. */
data class ChildDetailsDto(
  val dateOfBirth: String,
  val sex: String?,
  val birthWeightKg: Double?,
  val birthLengthCm: Double?,
  val prematureFlag: Boolean?,
)

/** `consent` block — status is always `"GIVEN"` from this client: a refused consent
 * (Q3 = No) never reaches submission, the Consent step blocks forward navigation instead. */
data class ConsentDto(
  val status: String,
  val date: String,
)

/** Top-level `POST /beneficiaries` request body — matches `createBeneficiarySchema` exactly:
 * `{ pii, case, motherDetails?, childDetails?, consent, acknowledgeDuplicate? }`, `.strict()`
 * (unknown fields are rejected), so no extra fields must ever be added here without checking
 * the live schema first. */
data class CreateBeneficiaryRequestDto(
  val pii: BeneficiaryPiiDto,
  val case: BeneficiaryCaseDto,
  val motherDetails: MotherDetailsDto?,
  val childDetails: ChildDetailsDto?,
  val consent: ConsentDto,
  val acknowledgeDuplicate: Boolean?,
)

/** Minimal slice of the rich response the API actually returns — only what the app needs
 * (the server-assigned beneficiary id, for local draft reconciliation). Retrofit/Gson ignore any
 * response fields not declared here (encrypted PII buffers, risk summaries, status history, etc). */
data class CreateBeneficiaryResponseData(
  val id: String,
)

data class CreateBeneficiaryResponseDto(
  val success: Boolean,
  val message: String?,
  val data: CreateBeneficiaryResponseData?,
)

/** Retrofit contract for the beneficiary-service enrollment endpoint (behind the same API
 * gateway/base URL as auth-service). Requires a Bearer token, attached by
 * [org.armman.sakhi.data.auth.AuthInterceptor]. */
interface EnrollmentApi {
  @POST("beneficiaries")
  suspend fun createBeneficiary(@Body request: CreateBeneficiaryRequestDto): Response<CreateBeneficiaryResponseDto>
}
