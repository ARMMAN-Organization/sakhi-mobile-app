package org.armman.sakhi.data.geography

/**
 * Geography hierarchy for enrollment (Excel Q12–18):
 * state → district → block/taluka → revenue village → pada, with PHC and
 * sub-centre derived from the village. Swap the static impl for a Retrofit
 * one when the geography API exists.
 */
interface GeographyRepository {

  /** The Sakhi's assigned state/district/block for prefill. */
  suspend fun getSakhiAssignment(): SakhiAssignment

  suspend fun getStates(): List<GeographyUnit>

  suspend fun getDistricts(stateId: String): List<GeographyUnit>

  suspend fun getBlocks(districtId: String): List<GeographyUnit>

  suspend fun getVillages(blockId: String): List<GeographyUnit>

  suspend fun getPadas(villageId: String): List<GeographyUnit>

  suspend fun getPhcs(villageId: String): List<GeographyUnit>

  suspend fun getSubCentres(villageId: String): List<GeographyUnit>
}
