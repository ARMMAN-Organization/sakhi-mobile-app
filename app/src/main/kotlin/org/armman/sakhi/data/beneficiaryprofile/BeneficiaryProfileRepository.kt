package org.armman.sakhi.data.beneficiaryprofile

/**
 * Beneficiary-detail data boundary. UI depends only on this interface; the
 * backing implementation (static today, beneficiary-service API later) is bound
 * in DI. Implementations throw [NoSuchElementException] for an unknown id.
 */
interface BeneficiaryProfileRepository {
  suspend fun getBeneficiary(id: String): BeneficiaryProfile
}
