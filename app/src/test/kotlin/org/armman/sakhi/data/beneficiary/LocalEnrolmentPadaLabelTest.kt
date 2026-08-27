package org.armman.sakhi.data.beneficiary

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.dynamicFormDraftGson
import org.armman.sakhi.data.forms.dynamicFormDraftPayloadKey
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The pada is captured at registration, but as a `geographyUnitId` — so the profile card printed a
 * raw UUID where the name belongs. The names travel in the same place the form's own dropdown reads
 * them from: the active version's `geography` array. These tests pin the resolution.
 */
class LocalEnrolmentPadaLabelTest {

  private lateinit var draftDao: FakeDynamicFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var forms: FakeFormsRepository
  private lateinit var source: LocalEnrolmentBeneficiarySource

  @Before
  fun setUp() {
    draftDao = FakeDynamicFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    forms = FakeFormsRepository(
      geography = listOf(
        FormGeographyUnit(PADA_ID, "PADA", "Chausa"),
        FormGeographyUnit(VILLAGE_ID, "VILLAGE", "Rampur"),
      ),
    )
    source = LocalEnrolmentBeneficiarySource(
      draftDao,
      FakeChildFormDraftDao(),
      secureStore,
      RoomVisitScheduleRepository(FakeVisitScheduleDao()),
      forms,
    )
  }

  @Test
  fun `a pada id resolves to its name`() = runTest {
    saveWithPada(PADA_ID)

    assertEquals("Chausa", source.getLocalBeneficiaries().single().pada)
  }

  /**
   * Offline is the normal case here — [org.armman.sakhi.data.forms.FormsRepository] serves the last
   * cached version, which is the same array the Sakhi filled the form against.
   */
  @Test
  fun `resolution works from the cached form version with no network`() = runTest {
    saveWithPada(PADA_ID)

    assertEquals("Chausa", source.getLocalBeneficiaries().single().pada)
  }

  /** Better a dash than a UUID on a clinical card. */
  @Test
  fun `an id with no matching geography unit falls back to a dash`() = runTest {
    saveWithPada("9a9d5a2a-f624-46f2-9bb7-f5b9175c73f4")

    assertEquals("—", source.getLocalBeneficiaries().single().pada)
  }

  @Test
  fun `a missing geography array falls back to a dash rather than printing the id`() = runTest {
    forms.geography = emptyList()
    saveWithPada(PADA_ID)

    assertEquals("—", source.getLocalBeneficiaries().single().pada)
  }

  @Test
  fun `a blank pada shows a dash`() = runTest {
    saveWithPada("")

    assertEquals("—", source.getLocalBeneficiaries().single().pada)
  }

  /** A free-text answer needs no lookup — the filter targets ids, not short strings. */
  @Test
  fun `a typed pada name passes straight through`() = runTest {
    saveWithPada("Chausa")

    assertEquals("Chausa", source.getLocalBeneficiaries().single().pada)
  }

  @Test
  fun `a multi-word pada name is not mistaken for an id despite its length`() = runTest {
    saveWithPada("Chausa Wadi Upper Settlement")

    assertEquals("Chausa Wadi Upper Settlement", source.getLocalBeneficiaries().single().pada)
  }

  private suspend fun saveWithPada(pada: String) {
    draftDao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = "local-1",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 1_754_265_600_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
    secureStore.putString(
      dynamicFormDraftPayloadKey("local-1"),
      dynamicFormDraftGson.toJson(
        DynamicFormDraftPayload(
          answers = FormAnswers(
            singleValues = mapOf(
              "first_name" to "Sunita",
              "last_name" to "Pawar",
              GeographyQuestionCodes.PADA to pada,
              GeographyQuestionCodes.VILLAGE to VILLAGE_ID,
            ),
          ),
          registrationDateIso = LocalDate.of(2026, 1, 1).toString(),
        ),
      ),
    )
  }

  private companion object {
    const val PADA_ID = "eb329eab-b7a9-4f9e-a526-2f96a557b515"
    const val VILLAGE_ID = "73a53120-71b4-4915-bf72-35d947e333a7"
  }
}
