package org.armman.sakhi.data.forms

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/** Matches the backend's `createSubmissionSchema` (`.strict()`) exactly:
 * `formVersionId`/`beneficiaryId` are required UUIDs, `visitId` is nullable/optional (not used by
 * enrollment — only relevant for visit-linked forms, out of scope here), `localSubmissionUuid` is
 * the offline-retry idempotency key (same pattern as `case.localCaseUuid`, CR-017), and `formData`
 * is the free-form answer blob keyed by `question_code`. */
data class CreateSubmissionRequestDto(
  val formVersionId: String,
  val beneficiaryId: String,
  val visitId: String? = null,
  val localSubmissionUuid: String,
  val formData: Map<String, Any?>,
)

data class SubmissionResponseData(
  val id: String,
)

data class CreateSubmissionResponseDto(
  val success: Boolean,
  val message: String?,
  val data: SubmissionResponseData?,
)

/** Retrofit contract for `visit-form-service`'s form-submission endpoint (CR-018). Requires a
 * Bearer token, attached by [org.armman.sakhi.data.auth.AuthInterceptor]. */
interface FormSubmissionApi {
  @POST("forms/{formCode}/submissions")
  suspend fun createSubmission(
    @Path("formCode") formCode: String,
    @Body request: CreateSubmissionRequestDto,
  ): Response<CreateSubmissionResponseDto>
}
