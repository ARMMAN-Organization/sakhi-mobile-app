package org.armman.sakhi.data.closure

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * `POST /api/v1/closures` body — role `SAKHI`, `.strict()` server-side (rejects unknown fields).
 * [localClosureUuid] is the offline-retry idempotency key, same pattern as
 * `CreateSubmissionRequestDto.localSubmissionUuid`/`case.localCaseUuid` (CR-017) — a client-minted
 * string (1-80 chars, not required to be a UUID literal) generated once at submit time and
 * persisted with the local draft so a retry reuses it rather than minting a new one.
 *
 * 2026-08-31: `supervisorStatus`/`supervisorId`/`supervisorNotes` were REMOVED from this DTO —
 * backend confirmed they were deliberately excluded from `create-closure.dto.ts` (a client that
 * could set `supervisorStatus: "APPROVED"` directly could bypass supervisor review entirely, a
 * real security hole they closed). `ClosureService.create()` derives `supervisorStatus` itself
 * from the resolved `closureReasonLookupValueId` server-side and is never client-settable. See
 * this project's "CR-Closure-01 Backend Ask - supervisorStatus on closures.md" for the full
 * exchange, and the open SRS-conflict flag: the ANC/Infant Closure form specs both say closure is
 * IMMEDIATE for every reason including Migration ("Upon submitting this closure form, this
 * beneficiary will be deactivated from the system") with no supervisor step at all — unlike the
 * Reopen form spec, which explicitly says "Send the application to Supervisor for approval."
 * Backend's new Migration-only supervisor-review gate is real and server-enforced, but it is not
 * a documented SRS requirement — flagged back to product/backend, not yet resolved. This client
 * does NOT currently build any pending-review UI for it pending that resolution; a Migration
 * closure submits successfully but the beneficiary may stay open server-side until a Supervisor
 * acts, with nothing on this screen reflecting that yet.
 */
data class ClosureRequestDto(
  val localClosureUuid: String,
  val beneficiaryId: String,
  val closureType: String,
  val closureReasonLookupValueId: String,
  val eventDate: String? = null,
  val closureDate: String,
  val submittedByUserId: String,
)

/** `data` payload of a successful `POST /closures` response. [supervisorStatus] is now
 * server-derived (see [ClosureRequestDto]'s doc) — `"PENDING"` for a Migration-reason closure
 * awaiting Supervisor review, `null` for every other reason (already closed). Not yet consumed by
 * this app pending the SRS-conflict resolution noted there. */
data class ClosureResponseDataDto(
  val id: String?,
  val supervisorStatus: String? = null,
)

data class ClosureResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ClosureResponseDataDto?,
)

/** Retrofit contract for the closure endpoint confirmed live by the backend team. Requires a
 * Bearer token, attached by [org.armman.sakhi.data.auth.AuthInterceptor], same as every other
 * service call in this app. Never calls `PATCH /beneficiaries/:id/close` — that is a
 * server-to-server call the closure service itself makes once this POST is accepted; the mobile
 * app must not call it directly. */
interface ClosureApi {
  @POST("closures")
  suspend fun createClosure(@Body request: ClosureRequestDto): Response<ClosureResponseDto>
}
