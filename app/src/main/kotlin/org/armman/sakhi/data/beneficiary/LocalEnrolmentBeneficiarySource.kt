package org.armman.sakhi.data.beneficiary

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment
import org.armman.sakhi.data.forms.DynamicFormDraftDao
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.BeneficiaryNameQuestionCodes
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyQuestionCodes
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
}

/**
 * Reads beneficiaries the Sakhi has enrolled on this device (CR-022g).
 *
 * ### Why this exists
 * Until now the beneficiary list came only from `StaticBeneficiaryRepository`'s fourteen seeded
 * records, so a woman the Sakhi actually enrolled never appeared anywhere in the app — and with
 * her went the only route to the visit schedule generated for her. This closes that gap:
 * enrol → find her in My Beneficiaries → open her profile → see her ANC series.
 *
 * ### Where the data comes from
 * The same hybrid split the enrolment flow writes: sync metadata in Room ([DynamicFormDraftDao]),
 * and the answers themselves in the encrypted [SecureKeyValueStore]. Names are PII, so they are
 * never read from plain SQLite.
 *
 * ### Best-effort by design
 * A draft whose payload cannot be read is skipped rather than surfacing a half-built row. That
 * costs one list entry; a crash on the Sakhi's main screen would cost her the whole app.
 */
@Singleton
class LocalEnrolmentBeneficiarySource @Inject constructor(
  private val draftDao: DynamicFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val scheduleRepository: VisitScheduleRepository,
  private val formsRepository: FormsRepository,
) {

  /** Locally enrolled mothers, newest first. */
  suspend fun getLocalBeneficiaries(today: LocalDate = LocalDate.now()): List<Beneficiary> =
    draftDao.getAll()
      .filter { it.formCode == MOTHER_REGISTRATION_FORM_CODE }
      .mapNotNull { draft -> runCatching { draft.toBeneficiary(today) }.getOrNull() }

  /**
   * One locally enrolled mother, or null if [localBeneficiaryId] is not a local draft.
   *
   * Needed by the profile screen: a local beneficiary's id is a UUID, which the static profile
   * records know nothing about, so without this every tap from the list into a real enrolment
   * would land on the error state.
   */
  suspend fun findLocalBeneficiary(
    localBeneficiaryId: String,
    today: LocalDate = LocalDate.now(),
  ): Beneficiary? {
    val draft = draftDao.getByLocalBeneficiaryId(localBeneficiaryId) ?: return null
    if (draft.formCode != MOTHER_REGISTRATION_FORM_CODE) return null
    return runCatching { draft.toBeneficiary(today) }.getOrNull()
  }

  /** The mobile number as answered, for the profile's contact row. */
  suspend fun answersFor(localBeneficiaryId: String): FormAnswers? =
    draftDao.getByLocalBeneficiaryId(localBeneficiaryId)?.let { readAnswers(it.localBeneficiaryId) }

  private suspend fun DynamicFormDraftEntity.toBeneficiary(today: LocalDate): Beneficiary? {
    val payload = readPayload(localBeneficiaryId) ?: return null
    val answers = payload.answers
    val schedules = scheduleRepository.getActiveForBeneficiary(localBeneficiaryId)

    // The visit she is working towards: the earliest one still open. Null once every visit is
    // done, or before a schedule has been generated.
    val nextVisit = schedules.minByOrNull { it.scheduledDate }

    return Beneficiary(
      id = localBeneficiaryId,
      name = answers.fullName(),
      type = BeneficiaryType.MOTHER,
      // Baseline risk from the enrollment answers themselves (CR-034) — age, obstetric history,
      // last-delivery outcome and sickle cell status, per Registration_PW_D column I. Before
      // CR-034 this was hardcoded LOW, which made a high-risk pregnancy indistinguishable from a
      // low-risk one on this list. A completed visit's clinical outcome can raise it further
      // (CR-026); it never lowers this baseline. Recomputed on read rather than stored so an
      // edited answer can never leave a stale tag behind.
      riskLevel = EnrollmentRiskAssessment.baselineRiskLevel(answers, payload.registrationDate() ?: today),
      status = BeneficiaryStatus.ACTIVE,
      visitState = VisitState.OPEN,
      pada = answers.padaLabel(),
      // Falls back to today so the card still renders for a beneficiary enrolled before CR-022,
      // whose schedule was never generated.
      scheduleDate = nextVisit?.scheduledDate ?: today,
      visitLabel = nextVisit?.visitCode.orEmpty().ifBlank { NO_VISIT_LABEL },
      daysRemaining = nextVisit?.daysRemaining(today) ?: 0,
      phoneNumber = answers.valueOf(QuestionCode.MOBILE_NUMBER).orEmpty(),
      journeyCompletedIn = null,
    )
  }

  private fun readAnswers(localBeneficiaryId: String): FormAnswers? =
    readPayload(localBeneficiaryId)?.answers

  private fun readPayload(localBeneficiaryId: String): DynamicFormDraftPayload? {
    val json = secureStore.getString(dynamicFormDraftPayloadKey(localBeneficiaryId)) ?: return null
    return runCatching {
      dynamicFormDraftGson.fromJson(json, DynamicFormDraftPayload::class.java)
    }.getOrNull()
  }

  /**
   * The date this woman was registered, as stored alongside her answers. Used as the reference date
   * for the age-based baseline risk rule (CR-034) so a record read months later still grades her age
   * AS OF registration — reading it off the clock would silently push a 34-year-old over the >=35
   * threshold with no new information. Null when the stored string is unparseable, in which case the
   * caller falls back to today.
   */
  private fun DynamicFormDraftPayload.registrationDate(): LocalDate? =
    runCatching { LocalDate.parse(registrationDateIso) }.getOrNull()

  /** Counts to the window close, matching the profile's rule — the deadline is when it shuts. */
  private fun VisitScheduleEntity.daysRemaining(today: LocalDate): Int =
    ChronoUnit.DAYS.between(today, windowEndDate).coerceAtLeast(0).toInt()

  /**
   * The pada as something a Sakhi can read, or a dash.
   *
   * Geography questions store an **id**, not a label, and the backend's `active-version` currently
   * ships no geography array to resolve ids against. Printing the raw answer put a UUID on the
   * profile card — it looked like a rendering fault and leaked an internal identifier onto a
   * clinical screen. Anything id-shaped is suppressed until CR-024 resolves geography labels
   * properly; a genuinely free-text answer still shows through.
   */
  /**
   * The pada as the Sakhi picked it in the form.
   *
   * Geography questions store a `geographyUnitId`, so the raw answer is a UUID. The names live in
   * the same place the form's own dropdown reads them from — [FormVersion.geography] on the active
   * version — so they are resolved rather than suppressed. [FormsRepository] serves that from cache
   * when offline, which is the normal case here.
   *
   * Falls back to a dash only when the id cannot be resolved: better a dash than a UUID on a
   * clinical card.
   */
  private suspend fun FormAnswers.padaLabel(): String =
    resolveGeography(GeographyQuestionCodes.PADA) ?: UNKNOWN_PADA

  /** Resolves one geography answer to its display name, or null. */
  suspend fun FormAnswers.resolveGeography(questionCode: String): String? {
    val answer = valueOf(questionCode)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // A free-text answer (or a schema that switches away from ids) needs no lookup.
    if (!looksLikeAnId(answer)) return answer

    val units = formsRepository.getActiveVersion(MOTHER_REGISTRATION_FORM_CODE)?.geography
    return units?.firstOrNull { it.geographyUnitId == answer }?.name
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

    private const val UNKNOWN_PADA = "—"
    private const val NO_VISIT_LABEL = "—"
    private const val UNNAMED = "Unnamed beneficiary"

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** Longer than any real pada name, and a single unbroken token — an id by any other name. */
    private const val ID_LIKE_MIN_LENGTH = 24
  }
}
