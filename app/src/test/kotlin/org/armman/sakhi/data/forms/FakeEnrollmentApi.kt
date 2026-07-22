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

  override suspend fun createBeneficiary(request: CreateBeneficiaryRequestDto): Response<CreateBeneficiaryResponseDto> {
    callCount++
    exceptionToThrow?.let { throw it }
    return response!!
  }
}
