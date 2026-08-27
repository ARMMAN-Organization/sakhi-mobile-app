package org.armman.sakhi.data.closure

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * `POST /api/v1/closures` body — role `SAKHI`, confirmed live by the backend team (not a plan).
 * [localClosureUuid] is the offline-retry idempotency key, same pattern as
 * `CreateSubmissionRequestDto.localSubmissionUuid`/`case.localCaseUuid` (CR-017) — a client-minted
 * string (1-80 chars, not required to be a UUID literal) generated once at submit time and
 * persisted with the local draft so a retry reuses it rather than minting a new one.
 *
 * [supervisorStatus] is set by the client, not derived server-side: `"PENDING"` only for a
 * MIGRATION-reason closure (supervisor-reviewed before it takes effect) — every other reason
 * closes immediately, so this stays null/omitted for those. See
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s closure-reason mapping for
 * exactly which reason sets which value.
 */
data class ClosureRequestDto(
  val localClosureUuid: String,
  val beneficiaryId: String,
  val closureType: String,
  val closureReasonLookupValueId: String,
  val eventDate: String? = null,
  val closureDate: String,
  val submittedByUserId: String,
  val supervisorStatus: String? = null,
  val supervisorId: String? = null,
  val supervisorNotes: String? = null,
)

/** `data` payload of a successful `POST /closures` response. Only [id] is currently read; the rest
 * of the row (closureType, supervisorStatus, etc.) is whatever the client itself just sent, so
 * there is nothing new to learn from echoing it back — modelled minimally rather than guessing at
 * every property the backend might include. */
data class ClosureResponseDataDto(
  val id: String?,
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
