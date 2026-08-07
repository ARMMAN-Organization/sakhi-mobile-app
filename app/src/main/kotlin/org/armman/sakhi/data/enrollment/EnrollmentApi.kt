package org.armman.sakhi.data.enrollment

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** `pii` block of the `POST /beneficiaries` request. Name is sent as ONE joined field.
 *
 * Timeline, each confirmed against a real backend 400 response (see `api-calls.jsonl`/
 * `api-calls-live.jsonl` and chat history — the live contract has changed at least three times,
 * not a misread each time):
 *  1. 2026-07-22 (first change that day): backend required a single `fullName`
 *     (`pii.fullName: String must contain at least 1 character(s)` when omitted) and rejected
 *     split fields.
 *  2. 2026-07-22 (later the same day): backend switched to requiring `firstName`/`lastName` and
 *     rejecting `fullName` outright (`pii: Unrecognized key(s) in object: 'fullName'`).
 *  3. 2026-08-06: reverted to #1 — confirmed live via the Sakhi app's own submit error
 *     (`Unrecognized key(s) in object: 'firstName', 'lastName'`) and by ARMMAN's own reference
 *     curl for this endpoint, which sends `pii.fullName` and nothing else.
 * This DTO matches contract #3 (== #1), the current one. [joinFullName] is the single place the
 * three schema/record fields become one string — every caller uses it rather than concatenating
 * inline, so a future spacing/ordering fix only needs to change one function. If submissions start
 * failing again with either message above, check the live error body before assuming this shape
 * is still right. */
data class BeneficiaryPiiDto(
  val fullName: String,
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

/** Joins a beneficiary's discrete first/middle/last name answers into the single `fullName`
 * string [BeneficiaryPiiDto] now sends — the ONE place this join happens, per that DTO's doc, so
 * every caller (dynamic mother form, static enrollment, child registration) stays consistent if
 * the join rule (spacing, missing-middle handling) ever needs to change. [first]/[last] are
 * expected non-blank (each caller's own required-field gate already enforces that); [middle] is
 * skipped entirely when blank rather than leaving a double space. */
fun joinFullName(first: String, middle: String?, last: String): String =
  listOfNotNull(first.trim(), middle?.trim()?.takeIf { it.isNotBlank() }, last.trim())
    .joinToString(" ")

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
