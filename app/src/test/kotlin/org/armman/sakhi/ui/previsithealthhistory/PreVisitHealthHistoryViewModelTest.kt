package org.armman.sakhi.ui.previsithealthhistory

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.previsithealth.BeneficiaryNotSyncedException
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistory
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistoryRepository
import org.armman.sakhi.data.previsithealth.RiskFactorTrend
import org.armman.sakhi.data.previsithealth.TrendValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CR-016a unit tests — spec: docs/test-cases/visit-form.md (PVH-1..7). PVH-8 (not-synced skip)
 * added alongside the real [org.armman.sakhi.data.previsithealth.RemotePreVisitHealthHistoryRepository]
 * wiring — see [org.armman.sakhi.data.previsithealth.RemotePreVisitHealthHistoryRepositoryTest]
 * for the mapping logic that repository owns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreVisitHealthHistoryViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeProfileRepository(var error: Exception? = null) : BeneficiaryProfileRepository {
    override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
      error?.let { throw it }
      return PROFILES[id] ?: throw NoSuchElementException("Unknown id: $id")
    }
  }

  private class FakeHistoryRepository(var error: Exception? = null) : PreVisitHealthHistoryRepository {
    override suspend fun getHealthHistory(beneficiaryId: String, visitId: String): PreVisitHealthHistory {
      error?.let { throw it }
      return HISTORIES[beneficiaryId]
        ?: throw NoSuchElementException("No history for: $beneficiaryId")
    }
  }

  private lateinit var profileRepository: FakeProfileRepository
  private lateinit var historyRepository: FakeHistoryRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    profileRepository = FakeProfileRepository()
    historyRepository = FakeHistoryRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(
    beneficiaryId: String? = "b01",
    visitId: String? = "v2",
  ): PreVisitHealthHistoryViewModel {
    val args = buildMap<String, String> {
      beneficiaryId?.let { put(PreVisitHealthHistoryViewModel.NAV_ARG_BENEFICIARY_ID, it) }
      visitId?.let { put(PreVisitHealthHistoryViewModel.NAV_ARG_VISIT_ID, it) }
    }
    val viewModel = PreVisitHealthHistoryViewModel(
      profileRepository,
      historyRepository,
      SavedStateHandle(args),
    )
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `loads beneficiary header and summary`() = runTest {
    val state = createViewModel().uiState.value

    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals("Sunita Sharma", state.profile?.name)
    assertEquals("1 Dec 2025", state.profile?.lmp)
    assertEquals("1 Sep 2026", state.profile?.edd)
  }

  @Test
  fun `shows only chronic condition chips that are actually flagged`() = runTest {
    val flagged = createViewModel(beneficiaryId = "b01").uiState.value.profile?.diagnoses
    assertEquals(listOf("Sickle Cell", "Chronic Diabetes"), flagged)

    val none = createViewModel(beneficiaryId = "b06").uiState.value.profile?.diagnoses
    assertTrue(none.isNullOrEmpty())
  }

  @Test
  fun `surfaces at most 5 risk-factor cards, only for flagged factors`() = runTest {
    val history = createViewModel().uiState.value.history

    assertTrue((history?.riskFactors?.size ?: 0) <= 5)
    assertEquals(2, history?.riskFactors?.size)
    assertTrue(history?.riskFactors?.all { it.riskLevel != null } == true)
    assertTrue(history?.nonRiskVitals?.all { it.riskLevel == null } == true)
  }

  @Test
  fun `each risk-factor card shows last-2-visits plus todays reference value`() = runTest {
    val anaemia = createViewModel().uiState.value.history?.riskFactors
      ?.first { it.factorName == "Anaemia" }

    assertEquals(3, anaemia?.values?.size)
    assertEquals("Last Visit", anaemia?.values?.last()?.label)
    assertTrue(anaemia?.values?.last()?.abnormal == true)
  }

  @Test
  fun `see profile emits navigate to profile event`() = runTest {
    val viewModel = createViewModel()
    viewModel.onSeeProfile()

    assertEquals(PreVisitHealthHistoryEvent.NavigateToProfile, viewModel.events.first())
  }

  @Test
  fun `start visit emits navigate to visit form event`() = runTest {
    val viewModel = createViewModel()
    viewModel.onStartVisit()

    assertEquals(PreVisitHealthHistoryEvent.NavigateToVisitForm, viewModel.events.first())
  }

  @Test
  fun `missing nav args sets error state`() = runTest {
    val state = createViewModel(beneficiaryId = null).uiState.value
    assertTrue(state.hasError)
  }

  @Test
  fun `unknown beneficiary sets error state`() = runTest {
    val state = createViewModel(beneficiaryId = "ghost").uiState.value
    assertTrue(state.hasError)
  }

  @Test
  fun `not-synced beneficiary skips straight to visit form instead of showing an error`() = runTest {
    historyRepository.error = BeneficiaryNotSyncedException("local-only-id")

    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    // No error surfaced — this is a normal offline state, not a failure.
    assertFalse(state.hasError)
    assertEquals(PreVisitHealthHistoryEvent.NavigateToVisitForm, viewModel.events.first())
  }

  private companion object {
    val PROFILES: Map<String, BeneficiaryProfile> = mapOf(
      "b01" to BeneficiaryProfile(
        id = "b01",
        name = "Sunita Sharma",
        type = BeneficiaryType.MOTHER,
        ageLabel = "25",
        village = "Rampur",
        pada = "Chausa",
        husbandName = "Akash Sharma",
        mobileNumber = "987654321",
        status = BeneficiaryStatus.ACTIVE,
        riskLevel = RiskLevel.HIGH,
        lmp = "1 Dec 2025",
        edd = "1 Sep 2026",
        diagnoses = listOf("Sickle Cell", "Chronic Diabetes"),
      ),
      "b06" to BeneficiaryProfile(
        id = "b06",
        name = "Asha Pawar",
        type = BeneficiaryType.MOTHER,
        ageLabel = "22",
        village = "Rampur",
        pada = "Chausa",
        husbandName = "Ravi Pawar",
        mobileNumber = "987654322",
        status = BeneficiaryStatus.ACTIVE,
        riskLevel = RiskLevel.LOW,
        lmp = "1 Feb 2026",
        edd = "1 Nov 2026",
        diagnoses = emptyList(),
      ),
    )

    val HISTORIES: Map<String, PreVisitHealthHistory> = mapOf(
      "b01" to PreVisitHealthHistory(
        riskFactors = listOf(
          RiskFactorTrend(
            factorName = "Anaemia",
            measureLabel = "Hemoglobin",
            riskLevel = RiskLevel.HIGH,
            values = listOf(
              TrendValue("6 July", "9.4", abnormal = false),
              TrendValue("6 August", "8.5", abnormal = false),
              TrendValue("Last Visit", "6.5", abnormal = true),
            ),
          ),
          RiskFactorTrend(
            factorName = "Hypertension",
            measureLabel = "Blood Pressure",
            riskLevel = RiskLevel.HIGH,
            values = listOf(
              TrendValue("6 July", "60/120", abnormal = false),
              TrendValue("6 August", "70/130", abnormal = true),
              TrendValue("Last Visit", "80/140", abnormal = true),
            ),
          ),
        ),
        nonRiskVitals = listOf(
          RiskFactorTrend(
            factorName = "Weight",
            measureLabel = "Weight",
            riskLevel = null,
            values = listOf(
              TrendValue("6 July", "70.2 kg", abnormal = false),
              TrendValue("6 August", "60.2 kg", abnormal = true),
              TrendValue("Last Visit", "70.2 kg", abnormal = false),
            ),
          ),
        ),
      ),
      "b06" to PreVisitHealthHistory(riskFactors = emptyList(), nonRiskVitals = emptyList()),
    )
  }
}
