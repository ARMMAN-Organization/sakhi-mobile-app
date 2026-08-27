package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.CreateBeneficiaryRequestDto
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseDto
import org.armman.sakhi.data.enrollment.EnrollmentApi
import retrofit2.Response

/**
 * Hand-rolled [EnrollmentApi] fake shared by every dynamic-forms test that needs to control the
 * `/beneficiaries` response — [DynamicFormSyncExecutorTest] and [RoomDynamicFormDraftRepositoryTest].
 * Kept in its own file (not `private` inside one test class) so both can use the exact same type
 * without a package-level redeclaration clash.
 */
class FakeEnrollmentApi : EnrollmentApi {
  var response: Response<CreateBeneficiaryResponseDto>? = null
  var exceptionToThrow: Throwable? = null
  var callCount = 0

  /**
   * Every request the code under test actually sent, in order. Needed to assert on fields the app
   * adds itself rather than echoes back — `acknowledgeDuplicate` and `case.previousBeneficiaryId`
   * exist only in the request, so a response-only fake cannot prove they were sent.
   */
  val requests = mutableListOf<CreateBeneficiaryRequestDto>()

  /**
   * Consumed one per call, before [response], so a test can script a sequence — e.g. a `409`
   * followed by a `201` for the confirm-and-retry path. Empty means "always [response]".
   */
  val responseQueue = ArrayDeque<Response<CreateBeneficiaryResponseDto>>()

  val lastRequest: CreateBeneficiaryRequestDto? get() = requests.lastOrNull()

  override suspend fun createBeneficiary(request: CreateBeneficiaryRequestDto): Response<CreateBeneficiaryResponseDto> {
    callCount++
    requests += request
    exceptionToThrow?.let { throw it }
    return responseQueue.removeFirstOrNull() ?: response!!
  }
}
