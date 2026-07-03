package org.armman.sakhi.data.beneficiary

/**
 * Beneficiary data boundary. UI depends only on this interface; the backing
 * implementation (static today, beneficiary-service API later) is bound in DI.
 */
interface BeneficiaryRepository {
  suspend fun getBeneficiaries(): List<Beneficiary>
}
