package org.armman.sakhi.data.enrollment

/** In-memory fake, same conventions as every other `Fake*Dao` in this test source set. */
class FakeEnrollmentRiskBaselineDao : EnrollmentRiskBaselineDao {
  private val rows = mutableMapOf<String, EnrollmentRiskBaselineEntity>()

  override suspend fun insertIfAbsent(entity: EnrollmentRiskBaselineEntity) {
    rows.putIfAbsent(entity.localBeneficiaryId, entity)
  }

  override suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): EnrollmentRiskBaselineEntity? =
    rows[localBeneficiaryId]
}
