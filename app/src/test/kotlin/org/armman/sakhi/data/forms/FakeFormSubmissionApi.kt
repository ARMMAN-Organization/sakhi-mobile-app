package org.armman.sakhi.data.forms

import retrofit2.Response

/**
 * Hand-rolled [FormSubmissionApi] fake shared by every dynamic-forms test that needs to control
 * the form-submission response — [DynamicFormSyncExecutorTest] and
 * [RoomDynamicFormDraftRepositoryTest]. Kept in its own file for the same reason as
 * [FakeEnrollmentApi] (avoids a package-level redeclaration clash between the two test classes).
 */
class FakeFormSubmissionApi : FormSubmissionApi {
  var response: Response<CreateSubmissionResponseDto>? = null
  var callCount = 0
  var lastRequest: CreateSubmissionRequestDto? = null

  override suspend fun createSubmission(
    formCode: String,
    request: CreateSubmissionRequestDto,
  ): Response<CreateSubmissionResponseDto> {
    callCount++
    lastRequest = request
    return response!!
  }
}
