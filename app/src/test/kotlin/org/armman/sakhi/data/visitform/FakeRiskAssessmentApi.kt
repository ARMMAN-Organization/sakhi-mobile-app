package org.armman.sakhi.data.visitform

import retrofit2.Response

/**
 * Recording fake for [RiskAssessmentApi], mirroring the conventions of the other `Fake*Api` test
 * doubles in this package (record the request, return a controllable canned response).
 *
 * Defaults to a successful empty-grading response: the real
 * [VisitFormSubmissionCoordinator.triggerRiskAssessment] is best-effort — it retries on failure
 * and ultimately swallows/logs if every attempt fails, precisely so a risk-grading problem can
 * never fail an otherwise-good visit submission — so a default that returns success keeps
 * existing submission tests asserting the submission path, not this side call. Set
 * [responseToReturn] (or [errorToThrow]) to exercise the failure branches explicitly.
 */
class FakeRiskAssessmentApi : RiskAssessmentApi {

  /** Every request received, in call order — empty when the coordinator correctly skipped the
   * call (no risk phase for the form code, or no server submission id). Its size doubles as the
   * retry attempt count when [callBehavior] is used. */
  val requests = mutableListOf<CreateRiskAssessmentRequestDto>()

  var responseToReturn: Response<CreateRiskAssessmentResponseDto> =
    Response.success(CreateRiskAssessmentResponseDto(success = true, message = null, data = null))

  /** When set, thrown instead of returning [responseToReturn] — for the "call itself failed"
   * (offline, socket timeout) branch rather than the "HTTP non-2xx" branch. Ignored once
   * [callBehavior] is set — set one or the other, not both. */
  var errorToThrow: Throwable? = null

  /**
   * Test-only per-call override for exercising retry behavior: when set, invoked with the
   * 0-based index of *this* call (how many prior calls happened) to decide what happens on this
   * specific attempt — return a [Response] to succeed/fail that attempt, or throw inside the
   * lambda to simulate a network-level failure on that attempt. Takes precedence over
   * [errorToThrow]/[responseToReturn] when non-null.
   * [VisitFormSubmissionCoordinatorTest]'s retry tests use this to fail N times then succeed, or
   * fail on every attempt, so they can assert both the retry count and the final outcome.
   */
  var callBehavior: ((callIndex: Int) -> Response<CreateRiskAssessmentResponseDto>)? = null

  override suspend fun createRiskAssessment(
    request: CreateRiskAssessmentRequestDto,
  ): Response<CreateRiskAssessmentResponseDto> {
    val callIndex = requests.size
    requests += request
    callBehavior?.let { return it(callIndex) }
    errorToThrow?.let { throw it }
    return responseToReturn
  }
}
