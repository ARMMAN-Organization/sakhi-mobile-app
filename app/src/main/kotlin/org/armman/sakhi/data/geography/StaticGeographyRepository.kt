package org.armman.sakhi.data.geography

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the geography API with a Palghar-district fixture
 * (project launch geography). Delete once the real endpoint exists —
 * only DI references it.
 */
@Singleton
class StaticGeographyRepository @Inject constructor() : GeographyRepository {

  override suspend fun getSakhiAssignment() = SakhiAssignment(
    stateId = "MH",
    districtId = "MH-PAL",
    blockId = "MH-PAL-JAW",
  )

  override suspend fun getStates() = listOf(
    GeographyUnit("MH", "Maharashtra"),
  )

  override suspend fun getDistricts(stateId: String) = when (stateId) {
    "MH" -> listOf(
      GeographyUnit("MH-PAL", "Palghar"),
      GeographyUnit("MH-NAN", "Nandurbar"),
    )
    else -> emptyList()
  }

  override suspend fun getBlocks(districtId: String) = when (districtId) {
    "MH-PAL" -> listOf(
      GeographyUnit("MH-PAL-JAW", "Jawhar"),
      GeographyUnit("MH-PAL-MOK", "Mokhada"),
    )
    "MH-NAN" -> listOf(
      GeographyUnit("MH-NAN-DHA", "Dhadgaon"),
    )
    else -> emptyList()
  }

  override suspend fun getVillages(blockId: String) = when (blockId) {
    "MH-PAL-JAW" -> listOf(
      GeographyUnit("V-BHAV", "Bhavanibagh"),
      GeographyUnit("V-RAMP", "Rampur"),
    )
    "MH-PAL-MOK" -> listOf(
      GeographyUnit("V-KELG", "Kelghar"),
    )
    "MH-NAN-DHA" -> listOf(
      GeographyUnit("V-JAMS", "Jamsar"),
    )
    else -> emptyList()
  }

  override suspend fun getPadas(villageId: String) = when (villageId) {
    "V-BHAV" -> listOf(
      GeographyUnit("P-CHAU", "Chausa"),
      GeographyUnit("P-PAD4", "Pada 4"),
    )
    "V-RAMP" -> listOf(
      GeographyUnit("P-RAM1", "Rampur Pada 1"),
    )
    "V-KELG" -> listOf(
      GeographyUnit("P-KEL1", "Kelghar Pada 1"),
    )
    "V-JAMS" -> listOf(
      GeographyUnit("P-JAM1", "Jamsar Pada 1"),
    )
    else -> emptyList()
  }

  override suspend fun getPhcs(villageId: String) = when (villageId) {
    "V-BHAV", "V-RAMP" -> listOf(GeographyUnit("PHC-JAW", "PHC Jawhar"))
    "V-KELG" -> listOf(GeographyUnit("PHC-MOK", "PHC Mokhada"))
    "V-JAMS" -> listOf(GeographyUnit("PHC-DHA", "PHC Dhadgaon"))
    else -> emptyList()
  }

  override suspend fun getSubCentres(villageId: String) = when (villageId) {
    "V-BHAV" -> listOf(GeographyUnit("SC-BHAV", "SC Bhavanibagh"))
    "V-RAMP" -> listOf(GeographyUnit("SC-RAMP", "SC Rampur"))
    "V-KELG" -> listOf(GeographyUnit("SC-KELG", "SC Kelghar"))
    "V-JAMS" -> listOf(GeographyUnit("SC-JAMS", "SC Jamsar"))
    else -> emptyList()
  }
}
