package org.armman.sakhi.data.lookup

/** Configurable fake — [valuesByCategory] defaults to a plausible MOTHER/CHILD +
 * PREGNANT_WOMAN/INFANT seed so most tests don't need to configure it explicitly. */
class FakeLookupRepository(
  var valuesByCategory: MutableMap<String, List<LookupValue>> = mutableMapOf(
    "CASE_TYPE" to listOf(
      LookupValue(id = "lookup-case-mother", valueCode = "MOTHER", valueLabel = "Mother"),
      LookupValue(id = "lookup-case-child", valueCode = "CHILD", valueLabel = "Child"),
    ),
    "BENEFICIARY_TYPE" to listOf(
      LookupValue(id = "lookup-ben-pw", valueCode = "PREGNANT_WOMAN", valueLabel = "Pregnant Woman"),
      LookupValue(id = "lookup-ben-infant", valueCode = "INFANT", valueLabel = "Infant"),
    ),
  ),
) : LookupRepository {
  override suspend fun getValues(categoryCode: String): List<LookupValue> =
    valuesByCategory[categoryCode] ?: emptyList()
}
