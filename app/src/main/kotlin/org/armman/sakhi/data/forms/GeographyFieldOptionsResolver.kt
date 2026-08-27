package org.armman.sakhi.data.forms

import org.armman.sakhi.data.auth.CurrentUserRepository
import org.armman.sakhi.data.geography.GeographyRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `question_code`s the dynamic MOTHER_REGISTRATION schema declares as `input_type: "select"`
 * with neither inline `options` nor a `lookup_category_code` — i.e. the schema itself doesn't say
 * where their values come from (confirmed against the real v5 `active-version` response, CR-018).
 *
 * Per product decision, these are special-cased in the app rather than waiting on the backend to
 * make them fully self-describing: they map onto the geography cascade / current-Sakhi profile
 * this app already has ([GeographyRepository], [CurrentUserRepository]). Risk accepted knowingly:
 * if the backend ever renames one of these `question_code`s, this mapping silently stops applying
 * to it (the field would render with zero options) rather than failing loudly — worth a periodic
 * sanity check against a fresh `active-version` response, not just at initial build time.
 */
object GeographyQuestionCodes {
  const val PROJECT_NAME = "project_name"
  const val STATE = "name_of_the_state"
  const val DISTRICT = "name_of_district"
  const val BLOCK_TALUKA = "name_of_block_taluka"
  const val VILLAGE = "name_of_the_revenue_village_grampanchayat"
  const val PADA = "beneficary_pada_name"
  const val PHC = "beneficary_phc_name"
  const val SUB_CENTRE = "name_of_sub_center"

  val ALL: Set<String> = setOf(PROJECT_NAME, STATE, DISTRICT, BLOCK_TALUKA, VILLAGE, PADA, PHC, SUB_CENTRE)

  /** Maps each geography `question_code` to the backend `geography[].geoType` it resolves against
   * (see [FormGeographyUnit]). [PROJECT_NAME] is intentionally absent — it is not a geography unit
   * and resolves from the Sakhi profile instead. */
  val QUESTION_CODE_TO_GEO_TYPE: Map<String, String> = mapOf(
    STATE to "STATE",
    DISTRICT to "DISTRICT",
    BLOCK_TALUKA to "BLOCK",
    VILLAGE to "VILLAGE",
    PADA to "PADA",
    PHC to "PHC",
    SUB_CENTRE to "SUBCENTRE",
  )
}

/**
 * Resolves options for [GeographyQuestionCodes.ALL] fields. Each geography level (below state)
 * depends on the field one level up already having an answer — [answers] carries whatever the
 * Sakhi has picked so far in this draft. Where nothing's picked yet, falls back to the Sakhi's own
 * assigned geography ([GeographyRepository.getSakhiAssignment]), matching the existing static
 * Personal Info step's prefill behavior.
 */
@Singleton
class GeographyFieldOptionsResolver @Inject constructor(
  private val geographyRepository: GeographyRepository,
  private val currentUserRepository: CurrentUserRepository,
) {

  /**
   * Mother flow (CR-018): geography options come straight from the [FormVersion.geography] the
   * backend ships with the active version — NOT the static [GeographyRepository] cascade — so every
   * submitted `geographyUnitId` is one the backend's `/beneficiaries` validation recognizes. Using
   * the static UUIDs is exactly what produced `pii.phcId does not refer to a known geography unit`
   * (HTTP 422) once the backend's geography table diverged from the hardcoded ids.
   *
   * The backend currently ships exactly one unit per level (the Sakhi's assignment), so each level
   * resolves to a single option; if it ever ships several for a level they're all returned, in
   * which case the field falls back to a normal dropdown (see [DynamicFormField]). [PROJECT_NAME]
   * is not a geography unit — it still resolves from the Sakhi profile. A `question_code` with no
   * matching `geoType` in [geography] returns empty: a real backend gap surfaced as an unfillable
   * (submit-gated) field rather than a silently wrong id.
   */
  suspend fun optionsFromVersionGeography(
    questionCode: String,
    geography: List<FormGeographyUnit>,
  ): List<FormFieldOption> {
    if (questionCode == GeographyQuestionCodes.PROJECT_NAME) {
      val projectName = currentUserRepository.getProfile()?.projectName ?: return emptyList()
      return listOf(FormFieldOption(label = projectName, sortOrder = 0, valueCode = projectName))
    }
    val geoType = GeographyQuestionCodes.QUESTION_CODE_TO_GEO_TYPE[questionCode] ?: return emptyList()
    return geography
      .filter { it.geoType == geoType }
      .mapIndexed { index, unit ->
        FormFieldOption(label = unit.name, sortOrder = index, valueCode = unit.geographyUnitId)
      }
  }

  suspend fun optionsFor(questionCode: String, answers: FormAnswers): List<FormFieldOption> {
    val assignment = geographyRepository.getSakhiAssignment()
    return when (questionCode) {
      GeographyQuestionCodes.PROJECT_NAME -> {
        val projectName = currentUserRepository.getProfile()?.projectName ?: return emptyList()
        listOf(FormFieldOption(label = projectName, sortOrder = 0, valueCode = projectName))
      }

      GeographyQuestionCodes.STATE ->
        geographyRepository.getStates().toOptions()

      GeographyQuestionCodes.DISTRICT -> {
        val stateId = answers.valueOf(GeographyQuestionCodes.STATE) ?: assignment.stateId
        geographyRepository.getDistricts(stateId).toOptions()
      }

      GeographyQuestionCodes.BLOCK_TALUKA -> {
        val districtId = answers.valueOf(GeographyQuestionCodes.DISTRICT) ?: assignment.districtId
        geographyRepository.getBlocks(districtId).toOptions()
      }

      GeographyQuestionCodes.VILLAGE -> {
        val blockId = answers.valueOf(GeographyQuestionCodes.BLOCK_TALUKA) ?: assignment.blockId
        geographyRepository.getVillages(blockId).toOptions()
      }

      GeographyQuestionCodes.PADA -> {
        val villageId = answers.valueOf(GeographyQuestionCodes.VILLAGE) ?: return emptyList()
        geographyRepository.getPadas(villageId).toOptions()
      }

      GeographyQuestionCodes.PHC -> {
        val villageId = answers.valueOf(GeographyQuestionCodes.VILLAGE) ?: return emptyList()
        geographyRepository.getPhcs(villageId).toOptions()
      }

      GeographyQuestionCodes.SUB_CENTRE -> {
        val villageId = answers.valueOf(GeographyQuestionCodes.VILLAGE) ?: return emptyList()
        geographyRepository.getSubCentres(villageId).toOptions()
      }

      else -> emptyList()
    }
  }

  private fun List<org.armman.sakhi.data.geography.GeographyUnit>.toOptions(): List<FormFieldOption> =
    mapIndexed { index, unit -> FormFieldOption(label = unit.name, sortOrder = index, valueCode = unit.id) }
}
