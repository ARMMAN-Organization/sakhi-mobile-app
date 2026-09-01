package org.armman.sakhi.data.forms

/**
 * In-memory [FormsRepository]. [geography] is what callers resolving a `geographyUnitId` back to a
 * display name read, mirroring the array the real `active-version` response carries.
 *
 * [versionByFormCode] lets a test stash a DIFFERENT version per `formCode` — needed once a single
 * ViewModel fetches more than one schema (e.g. [org.armman.sakhi.ui.visitform
 * .DynamicVisitFormViewModel] fetching both its own ANC_VISIT/INFANT_VISIT schema AND, since
 * CR-Referral-01 Pass 6, the in-visit referral capture step's REFERRAL_VISIT schema). Checked
 * first; [version] remains the single-schema-fits-all default every pre-existing test already
 * relies on, so it's untouched when [versionByFormCode] has no entry for the requested formCode.
 */
class FakeFormsRepository(
  var geography: List<FormGeographyUnit> = emptyList(),
  var version: FormVersion? = null,
  var versionByFormCode: MutableMap<String, FormVersion?> = mutableMapOf(),
) : FormsRepository {

  override suspend fun getActiveVersion(formCode: String): FormVersion? =
    if (versionByFormCode.containsKey(formCode)) {
      versionByFormCode[formCode]
    } else {
      version
    } ?: geography.takeIf { it.isNotEmpty() }?.let { units ->
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
