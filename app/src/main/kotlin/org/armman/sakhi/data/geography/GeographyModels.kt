package org.armman.sakhi.data.geography

/**
 * One node of the geography hierarchy, named as the future geography API is
 * expected to return it (normalized geography tables per the ERD).
 */
data class GeographyUnit(
  val id: String,
  val name: String,
)

/**
 * The Sakhi's assigned geography — used to prefill enrollment (Excel Q12–14:
 * state/district/block are auto-populated from the Sakhi's assignment).
 */
data class SakhiAssignment(
  val stateId: String,
  val districtId: String,
  val blockId: String,
)
