package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Covers [RemoteFieldEditsRepository] against the exact backend contract confirmed 2026-09-02:
 * `PATCH /form-submissions/:id/answers`, all-or-nothing, two distinct error shapes told apart by
 * HTTP code alone (`400` = unknown fieldCode, `422` = not-editable fieldCode) — both carry the
 * backend's own sentence in `message`, which this repository must surface verbatim, not a generic
 * failure (see [FieldEditResult]'s own doc).
 */
class RemoteFieldEditsRepositoryTest {

  private class FakeApi : FormSubmissionApi {
    var updateResponse: Response<UpdateFormSubmissionAnswersResponseDto>? = null
    var lastSubmissionId: String? = null
    var lastRequest: UpdateFormSubmissionAnswersRequestDto? = null
    var callCount = 0

    override suspend fun createSubmission(
      formCode: String,
      request: CreateSubmissionRequestDto,
    ): Response<CreateSubmissionResponseDto> = throw NotImplementedError("not exercised by this test")

    override suspend fun updateAnswers(
      submissionId: String,
      request: UpdateFormSubmissionAnswersRequestDto,
    ): Response<UpdateFormSubmissionAnswersResponseDto> {
      callCount++
      lastSubmissionId = submissionId
      lastRequest = request
      return updateResponse!!
    }
  }

  private lateinit var api: FakeApi
  private lateinit var repository: RemoteFieldEditsRepository

  @Before
  fun setUp() {
    api = FakeApi()
    repository = RemoteFieldEditsRepository(api)
  }

  @Test
  fun `success returns FieldEditResult Success and sends every edit as a fieldCode-value pair`() = runTest {
    api.updateResponse = Response.success(UpdateFormSubmissionAnswersResponseDto(success = true, message = "OK"))

    val result = repository.submitEdits(
      "submission-1",
      mapOf("mobile_number" to "9876543210", "enter_the_beneficiary_address" to "New address"),
    )

    assertEquals(FieldEditResult.Success, result)
    assertEquals("submission-1", api.lastSubmissionId)
    val edits = requireNotNull(api.lastRequest).edits.associate { it.fieldCode to it.value }
    assertEquals("9876543210", edits["mobile_number"])
    assertEquals("New address", edits["enter_the_beneficiary_address"])
  }

  @Test
  fun `400 VALIDATION_ERROR surfaces as UnknownFieldCodes carrying the backend's own message`() = runTest {
    val body = """{"success":false,"message":"Unknown fieldCode(s) for form \"MOTHER_REGISTRATION\": not_a_real_field.","errorCode":"VALIDATION_ERROR"}"""
    api.updateResponse = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    val result = repository.submitEdits("submission-1", mapOf("not_a_real_field" to "x"))

    assertTrue(result is FieldEditResult.UnknownFieldCodes)
    assertEquals(
      "Unknown fieldCode(s) for form \"MOTHER_REGISTRATION\": not_a_real_field.",
      (result as FieldEditResult.UnknownFieldCodes).message,
    )
  }

  @Test
  fun `422 UNPROCESSABLE surfaces as NotEditable carrying the backend's own message`() = runTest {
    val body = """{"success":false,"message":"The following field(s) are not editable after submission for form \"MOTHER_REGISTRATION\": lmp_date.","errorCode":"UNPROCESSABLE"}"""
    api.updateResponse = Response.error(422, body.toResponseBody("application/json".toMediaType()))

    val result = repository.submitEdits("submission-1", mapOf("lmp_date" to "2026-01-01"))

    assertTrue(result is FieldEditResult.NotEditable)
    assertEquals(
      "The following field(s) are not editable after submission for form \"MOTHER_REGISTRATION\": lmp_date.",
      (result as FieldEditResult.NotEditable).message,
    )
  }

  @Test
  fun `a 500 surfaces as Failed, not one of the two documented shapes`() = runTest {
    api.updateResponse = Response.error(
      500,
      "Internal Server Error".toResponseBody("text/plain".toMediaType()),
    )

    val result = repository.submitEdits("submission-1", mapOf("mobile_number" to "9876543210"))

    assertTrue(result is FieldEditResult.Failed)
  }
}
