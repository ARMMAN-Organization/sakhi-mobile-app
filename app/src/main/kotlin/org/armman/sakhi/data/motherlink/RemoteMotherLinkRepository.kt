package org.armman.sakhi.data.motherlink

import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_MOTHER_LINK_CACHE = "mother_link_cache"

private const val CASE_TYPE_MOTHER = "MOTHER"
private const val STATUS_ACTIVE = "ACTIVE"
private const val CONSENT_GIVEN = "GIVEN"

/**
 * Real [MotherLinkRepository] backed by `GET /beneficiaries` (+ `/beneficiaries/:id` for consent).
 *
 * Same resilience shape as [org.armman.sakhi.data.forms.RemoteFormsRepository]: prefer a live fetch,
 * persist every success to [SecureKeyValueStore], fall back to memory then disk when the fetch
 * fails. A Sakhi who has opened the picker once can link a mother offline for the rest of the day,
 * which matters because enrollment is routinely done in the field with no signal.
 *
 * `caseType`/`status` are sent as query params **and** re-filtered locally: the backend's list
 * handler applies no token-derived scoping today (risk R2), and a service that ignored the params
 * would otherwise put CHILD rows — or another Sakhi's beneficiaries — into a picker of mothers.
 * Filtering twice is cheap; a wrong `motherBeneficiaryId` on a real case is not.
 */
@Singleton
class RemoteMotherLinkRepository @Inject constructor(
  private val beneficiaryApi: BeneficiaryApi,
  private val store: SecureKeyValueStore,
) : MotherLinkRepository {

  private val mutex = Mutex()
  private var cached: List<LinkedMother>? = null

  override suspend fun getRegisteredMothers(): List<LinkedMother>? = mutex.withLock {
    val fetched = fetchMothers()
    if (fetched != null) {
      // Persist even when empty: a Sakhi whose last mother was closed must stop seeing a stale row.
      store.putString(KEY_MOTHER_LINK_CACHE, motherLinkGson.toJson(fetched))
      cached = fetched
      return fetched
    }
    cached?.let { return it }
    val persisted = readPersisted()
    if (persisted != null) cached = persisted
    persisted
  }

  override suspend fun getMotherConsent(motherId: String): LinkedMotherConsent? {
    val records = try {
      beneficiaryApi.detail(motherId)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
        ?.consentRecords
    } catch (e: Exception) {
      // Offline, 404, malformed body — consent simply isn't inherited. Never fails the selection.
      null
    } ?: return null

    // The backend returns only the latest record; `any` rather than `first` so an unexpected
    // multi-record response still reads as "consent was given" only when one actually says so.
    return LinkedMotherConsent(
      consentGiven = records.any { it.consentStatus.equals(CONSENT_GIVEN, ignoreCase = true) },
    )
  }

  private suspend fun fetchMothers(): List<LinkedMother>? {
    val rows = try {
      beneficiaryApi.list(caseType = CASE_TYPE_MOTHER, status = STATUS_ACTIVE)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
    } catch (e: Exception) {
      null
    } ?: return null

    return rows.mapNotNull(::toLinkedMother)
  }

  /** Null for any row that cannot be a selectable mother — missing id, wrong caseType/status, or no
   * `pii` block at all. Order is preserved (the backend already sorts `createdAt desc`). */
  private fun toLinkedMother(dto: BeneficiaryListItemDto): LinkedMother? {
    val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
    if (!dto.caseType.equals(CASE_TYPE_MOTHER, ignoreCase = true)) return null
    if (!dto.currentStatus.equals(STATUS_ACTIVE, ignoreCase = true)) return null
    val pii = dto.pii ?: return null
    return LinkedMother(
      id = id,
      fullName = pii.fullName.orEmpty().trim(),
      dateOfBirth = pii.dateOfBirth.toLocalDateOrNull(),
      currentPhase = dto.currentPhase.orEmpty(),
      registrationDate = dto.registrationDate.toLocalDateOrNull(),
      stateId = pii.stateId,
      districtId = pii.districtId,
      talukaId = pii.talukaId,
      villageId = pii.villageId,
      padaId = pii.padaId,
      phcId = pii.phcId,
      healthSubCentreId = pii.healthSubCentreId,
    )
  }

  private fun readPersisted(): List<LinkedMother>? {
    val json = store.getString(KEY_MOTHER_LINK_CACHE) ?: return null
    return try {
      motherLinkGson.fromJson(json, object : TypeToken<List<LinkedMother>>() {}.type)
    } catch (e: JsonSyntaxException) {
      null
    }
  }
}

/**
 * `"2001-07-29T00:00:00.000Z"` → `2001-07-29`, and null for anything unparseable.
 *
 * The date part is taken verbatim rather than parsed as an instant on purpose: these are calendar
 * dates the backend stores at midnight UTC, so converting through a local timezone would shift a
 * DOB by a day west of UTC. A malformed value yields null instead of throwing — a mother row with a
 * bad DOB must stay selectable.
 */
private fun String?.toLocalDateOrNull(): LocalDate? {
  val datePart = this?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: return null
  return runCatching { LocalDate.parse(datePart) }.getOrNull()
}
