package org.armman.sakhi.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.dashboard.ActiveBeneficiaries
import org.armman.sakhi.data.dashboard.ActiveVisits
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  /** Controllable fake: returns [summary] or throws when [error] is set. */
  private class FakeDashboardRepository(
    var summary: DashboardSummary = defaultSummary(),
    var error: Exception? = null,
  ) : DashboardRepository {
    override suspend fun getSummary(): DashboardSummary {
      error?.let { throw it }
      return summary
    }

    companion object {
      fun defaultSummary(
        mothersTotal: Int = 21,
        infantsTotal: Int = 10,
      ) = DashboardSummary(
        sakhiName = "Test Sakhi",
        pendingUploadCount = 8,
        lastUploadedOn = LocalDate.of(2026, 4, 14),
        activeVisits = ActiveVisits(
          month = YearMonth.of(2026, 1),
          openCount = 12,
          endingCount = 2,
          pendingReferralCount = 5,
        ),
        activeBeneficiaries = ActiveBeneficiaries(
          mothersTotal = mothersTotal,
          mothersHighRisk = 0,
          infantsTotal = infantsTotal,
          infantsHighRisk = 0,
        ),
      )
    }
  }

  private lateinit var repository: FakeDashboardRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeDashboardRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial state is Loading`() {
    val viewModel = HomeViewModel(repository)
    assertEquals(HomeUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun `successful load exposes the summary`() = runTest(dispatcher) {
    val viewModel = HomeViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(state is HomeUiState.Success)
    assertEquals("Test Sakhi", (state as HomeUiState.Success).summary.sakhiName)
  }

  @Test
  fun `repository failure results in Error state`() = runTest(dispatcher) {
    repository.error = IOException("network down")
    val viewModel = HomeViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(HomeUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun `retry after error reloads and reaches Success`() = runTest(dispatcher) {
    repository.error = IOException("network down")
    val viewModel = HomeViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(HomeUiState.Error, viewModel.uiState.value)

    repository.error = null
    viewModel.loadSummary()
    assertEquals(HomeUiState.Loading, viewModel.uiState.value)
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value is HomeUiState.Success)
  }

  @Test
  fun `zero-count summary is still Success`() = runTest(dispatcher) {
    repository.summary = FakeDashboardRepository.defaultSummary(
      mothersTotal = 0,
      infantsTotal = 0,
    ).copy(pendingUploadCount = 0)
    val viewModel = HomeViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(state is HomeUiState.Success)
    assertEquals(0, (state as HomeUiState.Success).summary.pendingUploadCount)
  }
}
