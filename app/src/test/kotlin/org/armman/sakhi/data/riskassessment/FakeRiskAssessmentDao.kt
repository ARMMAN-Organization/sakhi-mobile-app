package org.armman.sakhi.data.riskassessment

/** In-memory fake, same conventions as every other `Fake*Dao` in this test source set — unit-
 * testable without a real Room database. */
class FakeRiskAssessmentDao : RiskAssessmentDao {
  private val assessments = mutableMapOf<String, RiskAssessmentEntity>()
  private val flagsBySchedule = mutableMapOf<String, List<RiskFlagEntity>>()
  private var nextFlagId = 1L

  override suspend fun upsertAssessmentWithFlags(assessment: RiskAssessmentEntity, flags: List<RiskFlagEntity>) {
    upsertAssessment(assessment)
    deleteFlagsForSchedule(assessment.localScheduleUuid)
    if (flags.isNotEmpty()) insertFlags(flags)
  }

  override suspend fun upsertAssessment(entity: RiskAssessmentEntity) {
    assessments[entity.localScheduleUuid] = entity
  }

  override suspend fun deleteFlagsForSchedule(localScheduleUuid: String) {
    flagsBySchedule.remove(localScheduleUuid)
  }

  override suspend fun insertFlags(flags: List<RiskFlagEntity>) {
    flags.groupBy { it.localScheduleUuid }.forEach { (localScheduleUuid, groupFlags) ->
      val assigned = groupFlags.map { it.copy(id = nextFlagId++) }
      flagsBySchedule[localScheduleUuid] = flagsBySchedule[localScheduleUuid].orEmpty() + assigned
    }
  }

  override suspend fun getAssessmentByLocalScheduleUuid(localScheduleUuid: String): RiskAssessmentEntity? =
    assessments[localScheduleUuid]

  override suspend fun getFlagsByLocalScheduleUuid(localScheduleUuid: String): List<RiskFlagEntity> =
    flagsBySchedule[localScheduleUuid].orEmpty()

  override suspend fun getAssessmentsByLocalScheduleUuids(localScheduleUuids: List<String>): List<RiskAssessmentEntity> =
    localScheduleUuids.mapNotNull { assessments[it] }
}
