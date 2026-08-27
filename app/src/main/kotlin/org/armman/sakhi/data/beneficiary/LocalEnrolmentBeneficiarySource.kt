package org.armman.sakhi.data.beneficiary

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.childregistration.ChildFormDraftDao
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.childregistration.ChildFormDraftPayload
import org.armman.sakhi.data.childregistration.childFormDraftGson
import org.armman.sakhi.data.childregistration.childFormDraftPayloadKey
import org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment
import org.armman.sakhi.data.forms.BeneficiaryNameQuestionCodes
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes
import org.armman.sakhi.data.forms.dynamicFormDraftGson
import org.armman.sakhi.data.forms.dynamicFormDraftPayloadKey
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Question codes this source reads. Mirrors `DynamicFormSubmissionMapper`'s private set. */
private object QuestionCode {
  const val FIRST_NAME = "first_name"
  const val MIDDLE_NAME = "middle_name"
  const val LAST_NAME = "last_name"
  const val MOBILE_NUMBER = "mobile_number"

  /** CHILD_REGISTRATION's own name field — a single question, unlike the mother's split/combined
   * shapes above. Mirrors `ChildRegistrationSubmissionMapper.QuestionCode.NAME_OF_THE_CHILD`. */
  const val NAME_OF_THE_CHILD = "name_of_the_child"
}

/**
 * Reads beneficiaries the Sakhi has enrolled on this device (CR-022g, extended to children).
 *
 * ### Why this exists
 * Until CR-022g the beneficiary list came only from `StaticBeneficiaryRepository`'s fourteen seeded
 * records, so a woman the Sakhi actually enrolled never appeared anywhere in the app — and with
 * her went the only route to the visit schedule generated for her. This closes that gap:
 * enrol → find her in My Beneficiaries → open her profile → see her ANC series.
 *
 * ### Mothers AND children live in TWO separate stores, not one
 * CHILD_REGISTRATION (CR-020) is a standalone twin of the mother's dynamic-form stack, right down
 * to its own Room table (`child_registration_drafts` via [ChildFormDraftDao], distinct from
 * `dynamic_form_drafts` via [DynamicFormDraftDao]) and its own encrypted payload namespace
 * (`child_form_draft_payload_*` via [childFormDraftPayloadKey], distinct from
 * [dynamicFormDraftPayloadKey]). See `sakhi-form-clone-drift` in project memory.
 *
 * An earlier version of this class assumed CHILD_REGISTRATION rows would show up in
 * [DynamicFormDraftDao] alongside mother rows — they never do, because
 * `RoomChildFormDraftRepository` writes exclusively to [ChildFormDraftDao]. The `formCode ==
 * CHILD_REGISTRATION_FORM_CODE` branch of that old filter was therefore dead code: a child could be
 * registered successfully and still never appear in My Beneficiaries or resolve on the profile
 * screen, with no error anywhere to point at why. Fixed by reading both DAOs and both payload
 * namespaces explicitly rather than assuming they're one store.
 *
 * ### Where the data comes from
 * The same hybrid split each enrolment flow writes: sync metadata in Room ([DynamicFormDraftDao] /
 * [ChildFormDraftDao]), and the answers themselves in the encrypted [SecureKeyValueStore] under
 * each flow's own key. Names are PII, so they are never read from plain SQLite.
 *
 * ### Best-effort by design
 * A draft whose payload cannot be read is skipped rather than surfacing a half-built row. That
 * costs one list entry; a crash on the Sakhi's main screen would cost her the whole app.
 */
@Singleton
class LocalEnrolmentBeneficiarySource @Inject constructor(
  private val draftDao: DynamicFormDraftDao,
  private val childDraftDao: ChildFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val scheduleRepository: VisitScheduleRepository,
  private val formsRepository: FormsRepository,
  private val statusOverrideStore: LocalBeneficiaryStatusOverrideStore,
) {

  /**
   * Locally enrolled mothers and children, newest first.
   *
   * Each DAO already orders its own rows `createdAtEpochMillis DESC`; the two lists are merged on
   * that same field rather than concatenated, so a mother and a child enrolled minutes apart still
   * interleave correctly instead of showing as "every mother, then every child".
   */
  suspend fun getLocalBeneficiaries(today: LocalDate = LocalDate.now()): List<Beneficiary> {
    val mothers = draftDao.getAll()
      .filter { it.formCode == MOTHER_REGISTRATION_FORM_CODE }
      .mapNotNull { draft ->
        runCatching { draft.toMotherBeneficiary(today) }.getOrNull()
          ?.let { draft.createdAtEpochMillis to it }
      }
    val children = childDraftDao.getAll()
      .filter { it.formCode == CHILD_REGISTRATION_FORM_CODE }
      .mapNotNull { draft ->
        runCatching { draft.toChildBeneficiary(today) }.getOrNull()
          ?.let { draft.createdAtEpochMillis to it }
      }
    return (mothers + children).sortedByDescending { it.first }.map { it.second }
  }

  /**
   * One locally enrolled mother or child, or null if [localBeneficiaryId] is not a local draft.
   *
   * Needed by the profile screen: a local beneficiary's id is a UUID, which the static profile
   * records know nothing about, so without this every tap from the list into a real enrolment
   * would land on the error state. Checks the mother store first, then the child store — the two
   * id spaces never collide (each is a freshly minted UUID), so this is a lookup, not a guess.
   */
  suspend fun findLocalBeneficiary(
    localBeneficiaryId: String,
    today: LocalDate = LocalDate.now(),
  ): Beneficiary? {
    draftDao.getByLocalBeneficiaryId(localBeneficiaryId)
      ?.takeIf { it.formCode == MOTHER_REGISTRATION_FORM_CODE }
      ?.let { return runCatching { it.toMotherBeneficiary(today) }.getOrNull() }

    return childDraftDao.getByLocalBeneficiaryId(localBeneficiaryId)
      ?.takeIf { it.formCode == CHILD_REGISTRATION_FORM_CODE }
      ?.let { runCatching { it.toChildBeneficiary(today) }.getOrNull() }
  }

  /** The mobile number as answered, for the profile's contact row. Checks the mother store first,
   * then the child store, matching [findLocalBeneficiary]'s lookup order. */
  suspend fun answersFor(localBeneficiaryId: String): FormAnswers? {
    if (draftDao.getByLocalBeneficiaryId(localBeneficiaryId) != null) {
      return readMotherPayload(localBeneficiaryId)?.answers
    }
    if (childDraftDao.getByLocalBeneficiaryId(localBeneficiaryId) != null) {
      return readChildPayload(localBeneficiaryId)?.answers
    }
    return null
  }

  private suspend fun DynamicFormDraftEntity.toMotherBeneficiary(today: LocalDate): Beneficiary? {
    val payload = readMotherPayload(localBeneficiaryId) ?: return null
    return buildBeneficiary(
      localBeneficiaryId = localBeneficiaryId,
      isChild = false,
      answers = payload.answers,
      registrationDate = payload.registrationDate(),
      today = today,
      remoteBeneficiaryId = remoteBeneficiaryId,
    )
  }

  private suspend fun ChildFormDraftEntity.toChildBeneficiary(today: LocalDate): Beneficiary? {
    val payload = readChildPayload(localBeneficiaryId) ?: return null
    return buildBeneficiary(
      localBeneficiaryId = localBeneficiaryId,
      isChild = true,
      answers = payload.answers,
      registrationDate = payload.registrationDate(),
      today = today,
      remoteBeneficiaryId = remoteBeneficiaryId,
    )
  }

  /** The mapping shared by both a mother's and a child's draft, once each has resolved its own
   * DAO/payload store down to a plain [FormAnswers] — everything past that point only branches on
   * [isChild], never on which store the row came from. */
  private suspend fun buildBeneficiary(
    localBeneficiaryId: String,
    isChild: Boolean,
    answers: FormAnswers,
    registrationDate: LocalDate?,
    today: LocalDate,
    remoteBeneficiaryId: String?,
  ): Beneficiary {
    val schedules = scheduleRepository.getActiveForBeneficiary(localBeneficiaryId)

    // The visit she is working towards: the earliest one still open. Null once every visit is
    // done, or before a schedule has been generated.
    val nextVisit = schedules.minByOrNull { it.scheduledDate }

    return Beneficiary(
      id = localBeneficiaryId,
      name = if (isChild) answers.childName() else answers.fullName(),
      type = if (isChild) BeneficiaryType.INFANT else BeneficiaryType.MOTHER,
      // Baseline risk from the enrollment answers themselves (CR-034) — age, obstetric history,
      // last-delivery outcome and sickle cell status, per Registration_PW_D column I. Before
      // CR-034 this was hardcoded LOW, which made a high-risk pregnancy indistinguishable from a
      // low-risk one on this list. A completed visit's clinical outcome can raise it further
      // (CR-026); it never lowers this baseline. Recomputed on read rather than stored so an
      // edited answer can never leave a stale tag behind.
      //
      // FLAGGED: EnrollmentRiskAssessment.baselineRiskLevel is built entirely against the PW
      // obstetric-history answer set and does not apply to a child's answers — there is no risk
      // model for CHILD_REGISTRATION yet. LOW is a placeholder default until a real rule exists,
      // not a computed result; do not read a child's LOW here as "assessed low risk."
      riskLevel = if (isChild) {
        RiskLevel.LOW
      } else {
        EnrollmentRiskAssessment.baselineRiskLevel(answers, registrationDate ?: today)
      },
      // A freshly enrolled beneficiary defaults to ACTIVE; a beneficiary whose closure
      // submission has already succeeded (or is queued offline) carries a local optimistic
      // override instead — see LocalBeneficiaryStatusOverrideStore's doc for why this isn't
      // just always ACTIVE any more.
      status = statusOverrideStore.getStatus(localBeneficiaryId) ?: BeneficiaryStatus.ACTIVE,
      visitState = VisitState.OPEN,
      pada = answers.padaLabel(if (isChild) CHILD_REGISTRATION_FORM_CODE else MOTHER_REGISTRATION_FORM_CODE),
      // Falls back to today so the card still renders for a beneficiary enrolled before CR-022,
      // whose schedule was never generated.
      scheduleDate = nextVisit?.scheduledDate ?: today,
      visitLabel = nextVisit?.visitCode.orEmpty().ifBlank { NO_VISIT_LABEL },
      daysRemaining = nextVisit?.daysRemaining(today) ?: 0,
      // CHILD_REGISTRATION collects its own `mobile_number` answer too — directly on the schema
      // for the direct-registration path, and copied from the linked mother's record (see
      // MotherPrefill.MOBILE_NUMBER) on the registered-mother path — so it's read from this row's
      // own answers exactly like the mother flow, instead of being hardcoded blank. This is also
      // exactly what ChildRegistrationSubmissionMapper already sends the backend as `phone`, so
      // the profile card now agrees with what was actually submitted.
      phoneNumber = answers.valueOf(QuestionCode.MOBILE_NUMBER).orEmpty(),
      journeyCompletedIn = null,
      remoteBeneficiaryId = remoteBeneficiaryId,
      registrationDate = registrationDate,
    )
  }

  private fun readMotherPayload(localBeneficiaryId: String): DynamicFormDraftPayload? {
    val json = secureStore.getString(dynamicFormDraftPayloadKey(localBeneficiaryId)) ?: return null
    return runCatching {
      dynamicFormDraftGson.fromJson(json, DynamicFormDraftPayload::class.java)
    }.getOrNull()
  }

  private fun readChildPayload(localBeneficiaryId: String): ChildFormDraftPayload? {
    val json = secureStore.getString(childFormDraftPayloadKey(localBeneficiaryId)) ?: return null
    return runCatching {
      childFormDraftGson.fromJson(json, ChildFormDraftPayload::class.java)
    }.getOrNull()
  }

  /**
   * The date this beneficiary was registered, as stored alongside her answers. Used as the
   * reference date for the age-based baseline risk rule (CR-034) so a record read months later
   * still grades her age AS OF registration — reading it off the clock would silently push a
   * 34-year-old over the >=35 threshold with no new information. Null when the stored string is
   * unparseable, in which case the caller falls back to today.
   */
  private fun DynamicFormDraftPayload.registrationDate(): LocalDate? =
    runCatching { LocalDate.parse(registrationDateIso) }.getOrNull()

  private fun ChildFormDraftPayload.registrationDate(): LocalDate? =
    runCatching { LocalDate.parse(registrationDateIso) }.getOrNull()

  /** Counts to the window close, matching the profile's rule — the deadline is when it shuts. */
  private fun VisitScheduleEntity.daysRemaining(today: LocalDate): Int =
    ChronoUnit.DAYS.between(today, windowEndDate).coerceAtLeast(0).toInt()

  /**
   * The pada as the Sakhi picked it in the form.
   *
   * Geography questions store a `geographyUnitId`, so the raw answer is a UUID. The names live in
   * the same place the form's own dropdown reads them from — [FormVersion.geography] on the active
   * version — so they are resolved rather than suppressed. [FormsRepository] serves that from cache
   * when offline, which is the normal case here.
   *
   * [formCode] picks which form's active-version geography array to resolve against — mother and
   * child questions share the same `question_code`s (e.g. [GeographyQuestionCodes.PADA]) but each
   * form ships its own geography array, so resolving a child's pada id against the mother form's
   * array would silently fail to match.
   *
   * Falls back to a dash only when the id cannot be resolved: better a dash than a UUID on a
   * clinical card.
   */
  private suspend fun FormAnswers.padaLabel(formCode: String): String =
    resolveGeography(GeographyQuestionCodes.PADA, formCode) ?: UNKNOWN_PADA

  /**
   * Resolves one geography answer to its display name, or null.
   *
   * [formCode] defaults to MOTHER_REGISTRATION to preserve the existing external caller
   * ([org.armman.sakhi.data.beneficiaryprofile.ScheduleBackedBeneficiaryProfileRepository], which
   * only resolves village for mother profiles today) — pass it explicitly for anything else.
   */
  suspend fun FormAnswers.resolveGeography(
    questionCode: String,
    formCode: String = MOTHER_REGISTRATION_FORM_CODE,
  ): String? {
    val answer = valueOf(questionCode)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // A free-text answer (or a schema that switches away from ids) needs no lookup.
    if (!looksLikeAnId(answer)) return answer

    val units = formsRepository.getActiveVersion(formCode)?.geography
    return units?.firstOrNull { it.geographyUnitId == answer }?.name
  }

  /**
   * The mother's self-reported chronic conditions (Q58) plus a positive sickle cell finding (Q60),
   * resolved to the same display labels the enrollment form itself showed — feeds the profile
   * screen's Diagnosis chips (reported bug: the chips never appeared because nothing read these
   * two answers back out after enrollment; [ScheduleBackedBeneficiaryProfileRepository] left
   * `diagnoses` at its default empty list).
   *
   * Mirrors exactly which answers count as a real finding per [EnrollmentRiskAssessment] so the
   * two can never disagree: Q58's "no known condition"/"don't know" codes are excluded (the same
   * [MotherRegistrationQuestionCodes.ValueCode.NON_CONDITION_CODES] set used there), and Q60 only
   * counts an actual SCD/SCT result — "not tested", "normal" and "don't know" are not diagnoses.
   *
   * Mother-only: CHILD_REGISTRATION has neither question, so a child's [FormAnswers] simply holds
   * neither code and this returns an empty list.
   *
   * Falls back to the raw `value_code` when the active schema no longer lists a matching option —
   * fail-open, same as every other code-to-label lookup in this app — rather than silently
   * dropping a positive finding just because its label couldn't be found.
   */
  suspend fun FormAnswers.diagnosisLabels(): List<String> {
    val conditionCodes = multiValueOf(MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS)
      .filter { it !in MotherRegistrationQuestionCodes.ValueCode.NON_CONDITION_CODES }
    val sickleCellCode = valueOf(MotherRegistrationQuestionCodes.SICKLE_CELL_STATUS)?.takeIf {
      it == MotherRegistrationQuestionCodes.ValueCode.SICKLE_CELL_DISEASE ||
        it == MotherRegistrationQuestionCodes.ValueCode.SICKLE_CELL_TRAIT
    }
    if (conditionCodes.isEmpty() && sickleCellCode == null) return emptyList()

    val fields = formsRepository.getActiveVersion(MOTHER_REGISTRATION_FORM_CODE)?.schemaJson.orEmpty()
    fun label(questionCode: String, code: String): String =
      fields.firstOrNull { it.questionCode == questionCode }
        ?.options?.firstOrNull { it.valueCode == code }?.label
        ?: code

    return conditionCodes.map { label(MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS, it) } +
      listOfNotNull(sickleCellCode?.let { label(MotherRegistrationQuestionCodes.SICKLE_CELL_STATUS, it) })
  }

  /**
   * 2026-08-06: this used to read ONLY [QuestionCode.FIRST_NAME]/[MIDDLE_NAME]/[LAST_NAME] — the
   * live schema moved to ONE combined `beneficiary_name` question the same day, those three
   * answers went permanently blank, and every freshly enrolled woman showed as "Unnamed
   * beneficiary" on this list (the bug that got reported). Now prefers
   * [BeneficiaryNameQuestionCodes.combinedNameAnswer] — the ONE place that knows the combined
   * field's possible codes — before falling back to the split questions, same priority
   * [org.armman.sakhi.data.forms.DynamicFormSubmissionMapper] uses for `pii.fullName`.
   */
  private fun FormAnswers.fullName(): String =
    BeneficiaryNameQuestionCodes.combinedNameAnswer(this) ?: listOfNotNull(
      valueOf(QuestionCode.FIRST_NAME)?.trim()?.takeIf { it.isNotBlank() },
      valueOf(QuestionCode.MIDDLE_NAME)?.trim()?.takeIf { it.isNotBlank() },
      valueOf(QuestionCode.LAST_NAME)?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { UNNAMED }

  /**
   * CHILD_REGISTRATION's `name_of_the_child` answer, as registered — the child's own name, not
   * "Baby of <mother>". The direct-registration path has no mother link at all, so a mother-derived
   * label isn't available for every child anyway; showing the registered name as-is is consistent
   * across both registration paths.
   */
  private fun FormAnswers.childName(): String =
    valueOf(QuestionCode.NAME_OF_THE_CHILD)?.trim()?.takeIf { it.isNotBlank() } ?: UNNAMED

  companion object {
    /**
     * True when a geography answer is a `geographyUnitId` rather than a typed name — a UUID, or
     * any long unbroken token.
     *
     * Only ids need resolving against the active version's geography array; a free-text answer, or
     * a schema that later switches away from ids, passes straight through.
     */
    private fun looksLikeAnId(value: String): Boolean =
      UUID_PATTERN.matches(value) ||
        (value.length >= ID_LIKE_MIN_LENGTH && value.none { it.isWhitespace() })

    private const val MOTHER_REGISTRATION_FORM_CODE = "MOTHER_REGISTRATION"
    private const val CHILD_REGISTRATION_FORM_CODE = "CHILD_REGISTRATION"

    private const val UNKNOWN_PADA = "—"
    private const val NO_VISIT_LABEL = "—"
    private const val UNNAMED = "Unnamed beneficiary"

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** Longer than any real pada name, and a single unbroken token — an id by any other name. */
    private const val ID_LIKE_MIN_LENGTH = 24
  }
}
