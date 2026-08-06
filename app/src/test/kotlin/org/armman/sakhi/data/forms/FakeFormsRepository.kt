package org.armman.sakhi.data.forms

/**
 * In-memory [FormsRepository]. [geography] is what callers resolving a `geographyUnitId` back to a
 * display name read, mirroring the array the real `active-version` response carries.
 */
class FakeFormsRepository(
  var geography: List<FormGeographyUnit> = emptyList(),
  var version: FormVersion? = null,
) : FormsRepository {

  override suspend fun getActiveVersion(formCode: String): FormVersion? =
    version ?: geography.takeIf { it.isNotEmpty() }?.let { units ->
      FormVersion(
        id = "version-1",
        formDefinitionId = "definition-1",
        versionNo = "v1",
        schemaJson = emptyList(),
        validationJson = emptyList(),
        effectiveFrom = "2026-01-01",
        effectiveTo = null,
        status = "PUBLISHED",
        geography = units,
      )
    }
}
