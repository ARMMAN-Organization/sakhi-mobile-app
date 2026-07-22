package org.armman.sakhi.data.forms

/** Master-data boundary for dynamic form schemas (SRS FR-S-4.5 / Appendix K.2 — ARMMAN can
 * update a form's fields server-side and the app picks it up on next fetch, no app release
 * needed). Real schema/version rows live server-side; if a fetch fails with nothing cached from a
 * prior successful fetch, callers get null and must fall back to whatever they'd otherwise show
 * (e.g. a "couldn't load form, try again online" state) — an enrollment can't render at all
 * without some form to render. */
interface FormsRepository {

  /**
   * The active [FormVersion] for [formCode] (e.g. `"MOTHER_REGISTRATION"`), preferring a live
   * fetch and falling back to the last successfully cached version if offline/failed. Null only
   * if no version has ever been fetched successfully on this device.
   */
  suspend fun getActiveVersion(formCode: String): FormVersion?
}
