package org.armman.sakhi.ui.beneficiaries

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryRepository
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiary.VisitState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiariesViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeBeneficiaryRepository(
    var beneficiaries: List<Beneficiary> = DEFAULT_DATA,
    var error: Exception? = null,
  ) : BeneficiaryRepository {
    override suspend fun getBeneficiaries(): List<Beneficiary> {
      error?.let { throw it }
      return beneficiaries
    }
  }

  private lateinit var repository: FakeBeneficiaryRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeBeneficiaryRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(): BeneficiariesViewModel {
    val viewModel = BeneficiariesViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `initial load shows all Active beneficiaries with default filters`() {
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertEquals(BeneficiaryStatus.ACTIVE, state.selectedTab)
    assertEquals(VisitSubTab.ALL, state.selectedSubTab)
    assertEquals(listOf("a1", "a2", "a3"), state.beneficiaries.map { it.id })
    assertTrue(state.selectedPadas.isEmpty())
    assertTrue(state.selectedRisks.isEmpty())
  }

  @Test
  fun `tab switch shows only that status`() {
    val viewModel = createViewModel()

    viewModel.onTabSelected(BeneficiaryStatus.JOURNEY_COMPLETE)
    assertEquals(listOf("j1", "j2"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onTabSelected(BeneficiaryStatus.CLOSED)
    assertEquals(listOf("c1"), viewModel.uiState.value.beneficiaries.map { it.id })
  }

  @Test
  fun `sub-tabs filter by visit state and ALL shows everything`() {
    val viewModel = createViewModel()

    viewModel.onSubTabSelected(VisitSubTab.OPEN)
    assertEquals(listOf("a1"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onSubTabSelected(VisitSubTab.PENDING_REFERRAL)
    assertEquals(listOf("a2"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onSubTabSelected(VisitSubTab.MISSED)
    assertEquals(listOf("a3"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onSubTabSelected(VisitSubTab.ALL)
    assertEquals(3, viewModel.uiState.value.beneficiaries.size)
  }

  @Test
  fun `search is case-insensitive and restores on clear`() {
    val viewModel = createViewModel()

    viewModel.onSearchQueryChanged("sUnItA")
    assertEquals(listOf("a1"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onSearchQueryChanged("no such name")
    assertTrue(viewModel.uiState.value.beneficiaries.isEmpty())

    viewModel.onSearchQueryChanged("")
    assertEquals(3, viewModel.uiState.value.beneficiaries.size)
  }

  @Test
  fun `pada filter restricts and clear resets`() {
    val viewModel = createViewModel()

    viewModel.onTogglePada("Jamsar")
    viewModel.onApplyFilters()
    assertEquals(listOf("a1"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onTogglePada("Kelghar")
    viewModel.onApplyFilters()
    assertEquals(listOf("a1", "a2"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onClearFilter(OpenFilter.PADA)
    assertEquals(3, viewModel.uiState.value.beneficiaries.size)
  }

  @Test
  fun `risk filter restricts and clear resets`() {
    val viewModel = createViewModel()

    viewModel.onToggleRisk(RiskLevel.HIGH)
    viewModel.onApplyFilters()
    assertEquals(listOf("a1"), viewModel.uiState.value.beneficiaries.map { it.id })

    viewModel.onClearFilter(OpenFilter.RISK)
    assertEquals(3, viewModel.uiState.value.beneficiaries.size)
  }

  @Test
  fun `combined filters use AND semantics`() {
    val viewModel = createViewModel()

    viewModel.onSubTabSelected(VisitSubTab.OPEN)
    viewModel.onSearchQueryChanged("Sunita")
    viewModel.onTogglePada("Jamsar")
    viewModel.onToggleRisk(RiskLevel.HIGH)
    viewModel.onApplyFilters()
    assertEquals(listOf("a1"), viewModel.uiState.value.beneficiaries.map { it.id })

    // Same combination but a pada that doesn't match -> empty.
    viewModel.onTogglePada("Jamsar")
    viewModel.onTogglePada("Kelghar")
    viewModel.onApplyFilters()
    assertTrue(viewModel.uiState.value.beneficiaries.isEmpty())
  }

  @Test
  fun `month filter applies on Journey Complete only`() {
    val viewModel = createViewModel()

    viewModel.onTabSelected(BeneficiaryStatus.JOURNEY_COMPLETE)
    viewModel.onMonthSelected(YearMonth.of(2026, 4))
    assertEquals(listOf("j1"), viewModel.uiState.value.beneficiaries.map { it.id })

    // Switching to Active ignores the month selection entirely.
    viewModel.onTabSelected(BeneficiaryStatus.ACTIVE)
    assertEquals(3, viewModel.uiState.value.beneficiaries.size)
  }

  @Test
  fun `sub-tab resets on tab switch while pada and risk persist`() {
    val viewModel = createViewModel()

    viewModel.onSubTabSelected(VisitSubTab.OPEN)
    viewModel.onTogglePada("Jamsar")
    viewModel.onApplyFilters()
    viewModel.onTabSelected(BeneficiaryStatus.JOURNEY_COMPLETE)
    viewModel.onTabSelected(BeneficiaryStatus.ACTIVE)

    val state = viewModel.uiState.value
    assertEquals(VisitSubTab.ALL, state.selectedSubTab)
    assertEquals(setOf("Jamsar"), state.selectedPadas)
  }

  @Test
  fun `repository failure sets error and retry recovers`() {
    repository.error = IOException("offline")
    val viewModel = createViewModel()
    assertTrue(viewModel.uiState.value.hasError)

    repository.error = null
    viewModel.loadBeneficiaries()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(!state.hasError)
    assertEquals(3, state.beneficiaries.size)
  }

  @Test
  fun `zero-result combination is success with empty list`() {
    val viewModel = createViewModel()

    viewModel.onSearchQueryChanged("Sunita")
    viewModel.onToggleRisk(RiskLevel.LOW)
    viewModel.onApplyFilters()

    val state = viewModel.uiState.value
    assertTrue(!state.hasError)
    assertTrue(state.beneficiaries.isEmpty())
  }

  @Test
  fun `listsByTab holds all three statuses simultaneously`() {
    val viewModel = createViewModel()
    val lists = viewModel.uiState.value.listsByTab

    assertEquals(listOf("a1", "a2", "a3"), lists[BeneficiaryStatus.ACTIVE]?.map { it.id })
    assertEquals(listOf("j1", "j2"), lists[BeneficiaryStatus.JOURNEY_COMPLETE]?.map { it.id })
    assertEquals(listOf("c1"), lists[BeneficiaryStatus.CLOSED]?.map { it.id })
  }

  @Test
  fun `search and pada filters apply to every tab list at once`() {
    val viewModel = createViewModel()

    viewModel.onTogglePada("Jamsar")
    viewModel.onApplyFilters()
    val lists = viewModel.uiState.value.listsByTab

    assertEquals(listOf("a1"), lists[BeneficiaryStatus.ACTIVE]?.map { it.id })
    assertEquals(listOf("j1"), lists[BeneficiaryStatus.JOURNEY_COMPLETE]?.map { it.id })
    assertTrue(lists[BeneficiaryStatus.CLOSED].orEmpty().isEmpty())
  }

  @Test
  fun `month filter affects only the Journey Complete list`() {
    val viewModel = createViewModel()

    viewModel.onMonthSelected(YearMonth.of(2026, 4))
    val lists = viewModel.uiState.value.listsByTab

    assertEquals(3, lists[BeneficiaryStatus.ACTIVE]?.size)
    assertEquals(listOf("j1"), lists[BeneficiaryStatus.JOURNEY_COMPLETE]?.map { it.id })
    assertEquals(1, lists[BeneficiaryStatus.CLOSED]?.size)
  }

  @Test
  fun `sub-tab filter affects only the Active list`() {
    val viewModel = createViewModel()

    viewModel.onSubTabSelected(VisitSubTab.OPEN)
    val lists = viewModel.uiState.value.listsByTab

    assertEquals(listOf("a1"), lists[BeneficiaryStatus.ACTIVE]?.map { it.id })
    assertEquals(2, lists[BeneficiaryStatus.JOURNEY_COMPLETE]?.size)
    assertEquals(1, lists[BeneficiaryStatus.CLOSED]?.size)
  }

  @Test
  fun `pada and month options are derived from data`() {
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertEquals(listOf("Jamsar", "Kelghar", "Savarpada"), state.padaOptions)
    assertEquals(
      listOf(YearMonth.of(2026, 4), YearMonth.of(2026, 3)),
      state.monthOptions,
    )
  }

  private companion object {
    val DEFAULT_DATA = listOf(
      beneficiary("a1", "Sunita Sharma", BeneficiaryStatus.ACTIVE, RiskLevel.HIGH, VisitState.OPEN, "Jamsar"),
      beneficiary("a2", "Riya Verma", BeneficiaryStatus.ACTIVE, RiskLevel.MODERATE, VisitState.PENDING_REFERRAL, "Kelghar"),
      beneficiary("a3", "Kavita Patil", BeneficiaryStatus.ACTIVE, RiskLevel.LOW, VisitState.MISSED, "Savarpada"),
      beneficiary("j1", "Lata Wagh", BeneficiaryStatus.JOURNEY_COMPLETE, RiskLevel.LOW, null, "Jamsar", YearMonth.of(2026, 4)),
      beneficiary("j2", "Savita More", BeneficiaryStatus.JOURNEY_COMPLETE, RiskLevel.MILD, null, "Kelghar", YearMonth.of(2026, 3)),
      beneficiary("c1", "Rekha Jadhav", BeneficiaryStatus.CLOSED, RiskLevel.MODERATE, null, "Savarpada"),
    )

    fun beneficiary(
      id: String,
      name: String,
      status: BeneficiaryStatus,
      risk: RiskLevel,
      visitState: VisitState?,
      pada: String,
      completedIn: YearMonth? = null,
    ) = Beneficiary(
      id = id,
      name = name,
      type = BeneficiaryType.MOTHER,
      riskLevel = risk,
      status = status,
      visitState = visitState,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 4, 24),
      visitLabel = "ANC 3",
      daysRemaining = 2,
      phoneNumber = "+911234567890",
      journeyCompletedIn = completedIn,
    )
  }
}
