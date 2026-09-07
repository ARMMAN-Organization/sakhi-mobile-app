package org.armman.sakhi.data.forms

import retrofit2.Response

/**
 * Hand-rolled [FormSubmissionApi] fake shared by every dynamic-forms test that needs to control
 * the form-submission response — [DynamicFormSyncExecutorTest] and
 * [RoomDynamicFormDraftRepositoryTest]. Kept in its own file for the same reason as
 * [FakeEnrollmentApi] (avoids a package-level redeclaration clash between the two test classes).
 *
 * [exceptionToThrow], when set, is thrown instead of returning [response] — added for
 * [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSyncExecutorTest]'s IOException/retry
 * coverage, same pattern [FakeEnrollmentApi.exceptionToThrow] already uses. Defaults to null so
 * every existing caller's behavior is unchanged.
 */
class FakeFormSubmissionApi : FormSubmissionApi {
  var response: Response<CreateSubmissionResponseDto>? = null
  var callCount = 0
  var lastRequest: CreateSubmissionRequestDto? = null
  var exceptionToThrow: Throwable? = null

  override suspend fun createSubmission(
    formCode: String,
    request: CreateSubmissionRequestDto,
  ): Response<CreateSubmissionResponseDto> {
    callCount++
    lastRequest = request
    exceptionToThrow?.let { throw it }
    return response!!
  }

  override suspend fun updateAnswers(
    submissionId: String,
    request: UpdateFormSubmissionAnswersRequestDto,
  ): Response<UpdateFormSubmissionAnswersResponseDto> =
    throw NotImplementedError("CR-Registration-Edit: not exercised by the tests sharing this fake")
}
