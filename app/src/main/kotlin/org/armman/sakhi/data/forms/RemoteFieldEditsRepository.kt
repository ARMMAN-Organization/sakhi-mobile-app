package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.ApiErrorParser
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_VALIDATION_ERROR = 400
private const val HTTP_UNPROCESSABLE = 422

@Singleton
class RemoteFieldEditsRepository @Inject constructor(
  private val api: FormSubmissionApi,
) : FieldEditsRepository {

  override suspend fun submitEdits(submissionId: String, edits: Map<String, String>): FieldEditResult {
    val request = UpdateFormSubmissionAnswersRequestDto(
      edits = edits.map { (fieldCode, value) -> FieldEditDto(fieldCode = fieldCode, value = value) },
    )
    return try {
      val response = api.updateAnswers(submissionId, request)
      if (response.isSuccessful) {
        FieldEditResult.Success
      } else {
        val body = response.errorBody()?.string()
        val apiError = ApiErrorParser.parse(body)
        // Surface the backend's own sentence — it already names the offending fieldCode(s), per
        // the documented contract (see FieldEditResult's own doc on each variant).
        val message = apiError.message?.takeIf { it != body } ?: body
        when (response.code()) {
          HTTP_VALIDATION_ERROR -> FieldEditResult.UnknownFieldCodes(message ?: "Unknown fieldCode")
          HTTP_UNPROCESSABLE -> FieldEditResult.NotEditable(message ?: "Field not editable")
          else -> FieldEditResult.Failed(message)
        }
      }
    } catch (e: IOException) {
      FieldEditResult.Failed(e.message)
    }
  }
}
