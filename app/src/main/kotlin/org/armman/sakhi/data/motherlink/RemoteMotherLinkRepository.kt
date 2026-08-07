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
private const val KEY_MOTHER_CONSENT_CACHE = "mother_consent_cache"
private const val KEY_MOTHER_SOCIO_DEMOGRAPHICS_CACHE = "mother_socio_demographics_cache"

private const val CASE_TYPE_MOTHER = "MOTHER"
private const val STATUS_ACTIVE = "ACTIVE"
private const val CONSENT_GIVEN = "GIVEN"

/**
 * Real [MotherLinkRepository] backed by `GET /beneficiaries` (+ `/beneficiaries/:id` for consent and
 * socio-demographics).
 *
 * Same resilience shape as [org.armman.sakhi.data.forms.RemoteFormsRepository]: prefer a live fetch,
 * persist every success to [SecureKeyValueStore], fall back to memory then disk when the fetch
 * fails. A Sakhi who has opened the picker once can link a mother offline for the rest of the day,
 * which matters because enrollment is routinely done in the field with no signal.
 *
 * Consent and socio-demographics get the same disk-cache treatment, keyed per `motherId` — see
 * [MotherDetailsWarmer], which proactively fills that cache for *every* registered mother while
 * online (login + reconnect) so a Sakhi doesn't have to have manually opened each one first for it
 * to be there later offline.
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

  // Guards read-modify-write of the two persisted per-motherId maps below. Cheap, in-memory JSON
  // string ops on a store capped at ~50 mothers (the picker's own limit) - a single mutex for both
  // maps is simpler than two, and contention here is negligible either way.
  private val detailsMutex = Mutex()

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
    val live = fetchConsent(motherId)
    if (live != null) {
      detailsMutex.withLock { putPersistedConsent(motherId, live) }
      return live
    }
    // Offline, 404, malformed body - fall back to whatever was cached the last time this mother's
    // consent was successfully fetched (a manual selection, or MotherDetailsWarmer). Never fails
    // the selection either way.
    return detailsMutex.withLock { readPersistedConsentMap()[motherId] }
  }

  override suspend fun getMotherSocioDemographics(motherId: String): MotherSocioDemographics? {
    val live = fetchSocioDemographics(motherId)
    if (live != null) {
      detailsMutex.withLock { putPersistedSocioDemographics(motherId, live) }
      return live
    }
    return detailsMutex.withLock { readPersistedSocioDemographicsMap()[motherId] }
  }

  private suspend fun fetchConsent(motherId: String): LinkedMotherConsent? {
    val records = try {
      beneficiaryApi.detail(motherId)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
        ?.consentRecords
    } catch (e: Exception) {
      // Offline, 404, malformed body - consent simply isn't inherited live; the cache fallback in
      // getMotherConsent() takes over. Never fails the selection.
      null
    } ?: return null

    // The backend returns only the latest record; `any` rather than `first` so an unexpected
    // multi-record response still reads as "consent was given" only when one actually says so.
    return LinkedMotherConsent(
      consentGiven = records.any { it.consentStatus.equals(CONSENT_GIVEN, ignoreCase = true) },
    )
  }

  private suspend fun fetchSocioDemographics(motherId: String): MotherSocioDemographics? {
    val data = try {
      beneficiaryApi.detail(motherId)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
    } catch (e: Exception) {
      null
    } ?: return null

    val socio = data.socioDemographics
    return MotherSocioDemographics(
      address = data.pii?.address?.trim()?.takeIf { it.isNotBlank() },
      mobileNumber = data.pii?.mobileNumber?.trim()?.takeIf { it.isNotBlank() },
      phoneOwner = socio?.phoneOwner?.toDomain(),
      mobileNetworkAvailability = socio?.mobileNetworkAvailability?.toDomain(),
      educationLevel = socio?.educationLevel?.toDomain(),
      partnerEducationLevel = socio?.partnerEducationLevel?.toDomain(),
      partnerOccupation = socio?.partnerOccupation?.toDomain(),
      yearsInVillage = socio?.yearsInVillage,
      migrationPattern = socio?.migrationPattern?.toDomain(),
      monthlyIncome = socio?.monthlyIncome?.toDomain(),
      religion = socio?.religion?.toDomain(),
      socialCategory = socio?.socialCategory?.toDomain(),
      familyMembersCount = socio?.familyMembersCount,
      childrenUnder5Count = socio?.childrenUnder5Count,
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

  private fun readPersistedConsentMap(): Map<String, LinkedMotherConsent> {
    val json = store.getString(KEY_MOTHER_CONSENT_CACHE) ?: return emptyMap()
    return try {
      motherLinkGson.fromJson(json, object : TypeToken<Map<String, LinkedMotherConsent>>() {}.type)
    } catch (e: JsonSyntaxException) {
      emptyMap()
    }
  }

  private fun putPersistedConsent(motherId: String, consent: LinkedMotherConsent) {
    val next = readPersistedConsentMap() + (motherId to consent)
    store.putString(KEY_MOTHER_CONSENT_CACHE, motherLinkGson.toJson(next))
  }

  private fun readPersistedSocioDemographicsMap(): Map<String, MotherSocioDemographics> {
    val json = store.getString(KEY_MOTHER_SOCIO_DEMOGRAPHICS_CACHE) ?: return emptyMap()
    return try {
      motherLinkGson.fromJson(json, object : TypeToken<Map<String, MotherSocioDemographics>>() {}.type)
    } catch (e: JsonSyntaxException) {
      emptyMap()
    }
  }

  private fun putPersistedSocioDemographics(motherId: String, socioDemographics: MotherSocioDemographics) {
    val next = readPersistedSocioDemographicsMap() + (motherId to socioDemographics)
    store.putString(KEY_MOTHER_SOCIO_DEMOGRAPHICS_CACHE, motherLinkGson.toJson(next))
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

/** Null when either half of the pair is missing — an incomplete resolved lookup carries neither a
 * category to look option labels up by nor a label to match against, so it cannot prefill anything. */
private fun ResolvedLookupDto.toDomain(): MotherLookupAnswer? {
  val category = categoryCode?.trim()?.takeIf { it.isNotBlank() } ?: return null
  val resolvedLabel = label?.trim()?.takeIf { it.isNotBlank() } ?: return null
  return MotherLookupAnswer(categoryCode = category, label = resolvedLabel)
}
