package org.armman.sakhi.data.visitform

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Matches the backend's `createVisitInstanceSchema` (`.strict()`) exactly — see the ANC Visit Form
 * storage API reference (`POST /visits`, `visitInstance.controller.ts` /
 * `create-visitInstance.dto.ts`). `scheduleId`/`beneficiaryId` are the *server*-assigned ids (a
 * beneficiary's/schedule's local UUIDs mean nothing to this endpoint), `sakhiId` is the caller's
 * own subject id, and `statusLookupValueId` is a `lookup_values.lookup_value_id` from the
 * `VISIT_STATUS` category (`GET /lookups/VISIT_STATUS` on auth-service) — see
 * [VisitFormSubmissionCoordinator] for how each is resolved.
 *
 * `meetBeneficiaryFlag`/`notMetReason` aren't populated by [VisitFormSubmissionCoordinator] this
 * pass (no confirmed mapping from the form's own "did you meet the beneficiary" question onto
 * these fields yet) — modelled here so a later CR can wire them without a DTO change.
 */
data class CreateVisitInstanceRequestDto(
  val scheduleId: String,
  val beneficiaryId: String,
  val sakhiId: String,
  val localVisitUuid: String,
  val statusLookupValueId: String,
  val actualVisitDate: String? = null,
  val meetBeneficiaryFlag: Boolean? = null,
  val notMetReason: String? = null,
  val completedAt: String? = null,
)

data class VisitInstanceResponseData(
  val id: String,
)

data class CreateVisitInstanceResponseDto(
  val success: Boolean,
  val message: String?,
  val data: VisitInstanceResponseData?,
)

/** Retrofit contract for `visit-form-service`'s visit-instance endpoint. Requires a Bearer token,
 * attached by [org.armman.sakhi.data.auth.AuthInterceptor] — same as every other authenticated API
 * in the app. */
interface VisitApi {
  @POST("visits")
  suspend fun createVisitInstance(
    @Body request: CreateVisitInstanceRequestDto,
  ): Response<CreateVisitInstanceResponseDto>
}
