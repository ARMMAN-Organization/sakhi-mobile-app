package org.armman.sakhi.data.geography

import javax.inject.Inject
import javax.inject.Singleton

// Real geography_units rows the backend team provisioned for testing (auth-service DB), one node
// per level under Maharashtra > Nandurbar > Dhadgaon. These are REAL UUIDs the backend will
// accept — unlike the old human-readable codes below ("MH-PAL-JAW" etc.), which is exactly what
// caused the "pii.villageId: Invalid uuid" 400 on a real device (the backend requires
// geographyUnitId, a UUID, not a code). Keep every selectable id here backend-valid; do not
// reintroduce a fake-code branch that only fails at submit time.
private const val STATE_MH = "de591be5-a495-4dba-9409-3f198d878ccf"
private const val DISTRICT_NANDURBAR = "7a600bc7-0a76-4321-8c7e-8c2abb6eec74"
private const val BLOCK_DHADGAON = "85a051dc-7189-4f4d-ae02-3561a89df378"
private const val PHC_TEST = "ea7787c7-11de-410a-88f3-d58172bf98b3"
private const val SUBCENTRE_TEST = "862704ab-a774-44a5-b2c1-84220eacbfc2"
private const val VILLAGE_TEST = "aa158c5a-9258-42d2-835a-88f517107c66"
private const val PADA_TEST = "4c5cb203-e3cb-4877-b1e9-c6fac3857cbb"

/**
 * Static stand-in for the geography API, backed by the one real test branch the backend has
 * provisioned so far (Maharashtra > Nandurbar > Dhadgaon > Test PHC/Sub-centre/Village/Pada).
 * Delete once the real geography-lookup endpoint/UI exists — only DI references it.
 */
@Singleton
class StaticGeographyRepository @Inject constructor() : GeographyRepository {

  override suspend fun getSakhiAssignment() = SakhiAssignment(
    stateId = STATE_MH,
    districtId = DISTRICT_NANDURBAR,
    blockId = BLOCK_DHADGAON,
  )

  override suspend fun getStates() = listOf(
    GeographyUnit(STATE_MH, "Maharashtra"),
  )

  override suspend fun getDistricts(stateId: String) = when (stateId) {
    STATE_MH -> listOf(
      GeographyUnit(DISTRICT_NANDURBAR, "Nandurbar"),
    )
    else -> emptyList()
  }

  override suspend fun getBlocks(districtId: String) = when (districtId) {
    DISTRICT_NANDURBAR -> listOf(
      GeographyUnit(BLOCK_DHADGAON, "Dhadgaon"),
    )
    else -> emptyList()
  }

  override suspend fun getVillages(blockId: String) = when (blockId) {
    BLOCK_DHADGAON -> listOf(
      GeographyUnit(VILLAGE_TEST, "Test Village"),
    )
    else -> emptyList()
  }

  override suspend fun getPadas(villageId: String) = when (villageId) {
    VILLAGE_TEST -> listOf(
      GeographyUnit(PADA_TEST, "Test Pada"),
    )
    else -> emptyList()
  }

  override suspend fun getPhcs(villageId: String) = when (villageId) {
    VILLAGE_TEST -> listOf(GeographyUnit(PHC_TEST, "Test PHC"))
    else -> emptyList()
  }

  override suspend fun getSubCentres(villageId: String) = when (villageId) {
    VILLAGE_TEST -> listOf(GeographyUnit(SUBCENTRE_TEST, "Test Sub-centre"))
    else -> emptyList()
  }
}
