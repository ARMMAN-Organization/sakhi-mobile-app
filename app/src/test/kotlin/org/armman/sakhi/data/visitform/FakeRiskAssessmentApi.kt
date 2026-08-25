package org.armman.sakhi.data.visitform

import retrofit2.Response

/**
 * Recording fake for [RiskAssessmentApi], mirroring the conventions of the other `Fake*Api` test
 * doubles in this package (record the request, return a controllable canned response).
 *
 * Defaults to a successful empty-grading response: the real
 * [VisitFormSubmissionCoordinator.triggerRiskAssessment] is best-effort — it swallows and logs
 * every failure precisely so a risk-grading problem can never fail an otherwise-good visit
 * submission — so a default that returns success keeps existing submission tests asserting the
 * submission path, not this side call. Set [responseToReturn] (or [errorToThrow]) to exercise the
 * failure branches explicitly.
 */
class FakeRiskAssessmentApi : RiskAssessmentApi {

  /** Every request received, in call order — empty when the coordinator correctly skipped the
   * call (no risk phase for the form code, or no server submission id). */
  val requests = mutableListOf<CreateRiskAssessmentRequestDto>()

  var responseToReturn: Response<CreateRiskAssessmentResponseDto> =
    Response.success(CreateRiskAssessmentResponseDto(success = true, message = null, data = null))

  /** When set, thrown instead of returning [responseToReturn] — for the "call itself failed"
   * (offline, socket timeout) branch rather than the "HTTP non-2xx" branch. */
  var errorToThrow: Throwable? = null

  override suspend fun createRiskAssessment(
    request: CreateRiskAssessmentRequestDto,
  ): Response<CreateRiskAssessmentResponseDto> {
    requests += request
    errorToThrow?.let { throw it }
    return responseToReturn
  }
}
