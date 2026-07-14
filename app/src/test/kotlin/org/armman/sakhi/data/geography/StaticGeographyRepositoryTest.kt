package org.armman.sakhi.data.geography

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-integrity tests for the static geography source (PI-15). */
class StaticGeographyRepositoryTest {

  private val repository = StaticGeographyRepository()

  @Test
  fun `sakhi assignment points at existing fixture nodes`() = runTest {
    val assignment = repository.getSakhiAssignment()

    assertTrue(repository.getStates().any { it.id == assignment.stateId })
    assertTrue(repository.getDistricts(assignment.stateId).any { it.id == assignment.districtId })
    assertTrue(repository.getBlocks(assignment.districtId).any { it.id == assignment.blockId })
  }

  @Test
  fun `every block has villages and every village has pada phc and sub centre`() = runTest {
    val states = repository.getStates()
    assertTrue(states.isNotEmpty())

    states.forEach { state ->
      repository.getDistricts(state.id).forEach { district ->
        repository.getBlocks(district.id).forEach { block ->
          val villages = repository.getVillages(block.id)
          assertTrue("block ${block.id} has no villages", villages.isNotEmpty())
          villages.forEach { village ->
            assertTrue("village ${village.id} has no padas", repository.getPadas(village.id).isNotEmpty())
            assertTrue("village ${village.id} has no PHC", repository.getPhcs(village.id).isNotEmpty())
            assertTrue(
              "village ${village.id} has no sub-centre",
              repository.getSubCentres(village.id).isNotEmpty(),
            )
          }
        }
      }
    }
  }

  @Test
  fun `unknown parent ids return empty lists`() = runTest {
    assertEquals(emptyList<GeographyUnit>(), repository.getDistricts("XX"))
    assertEquals(emptyList<GeographyUnit>(), repository.getBlocks("XX"))
    assertEquals(emptyList<GeographyUnit>(), repository.getVillages("XX"))
    assertEquals(emptyList<GeographyUnit>(), repository.getPadas("XX"))
    assertEquals(emptyList<GeographyUnit>(), repository.getPhcs("XX"))
    assertEquals(emptyList<GeographyUnit>(), repository.getSubCentres("XX"))
  }
}
