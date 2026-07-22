package org.armman.sakhi.data.enrollment

import retrofit2.Response

/**
 * Hand-rolled [EnrollmentApi] fake shared by every test that needs to control the
 * `/beneficiaries` response — [EnrollmentSyncExecutorTest] and [RoomEnrollmentRepositoryTest].
 * Kept in its own file (not `private` inside one test class) so both can use the exact same
 * type without a package-level redeclaration clash.
 */
class FakeEnrollmentApi : EnrollmentApi {
  var createResponse: Response<CreateBeneficiaryResponseDto>? = null
  var createExceptionToThrow: Throwable? = null
  var createCallCount = 0

  override suspend fun createBeneficiary(
    request: CreateBeneficiaryRequestDto,
  ): Response<CreateBeneficiaryResponseDto> {
    createCallCount++
    createExceptionToThrow?.let { throw it }
    return createResponse!!
  }
}
