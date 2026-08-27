package org.armman.sakhi.ui.visittracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.visittracker.PadaRepository
import org.armman.sakhi.data.visittracker.PadaSummary
import org.armman.sakhi.data.visittracker.PadaVisitBucket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * M3: [PadaSelectionViewModel] now consumes [PadaRepository]'s pre-aggregated per-pada counts
 * directly — replaces the old fixture-and-aggregate test against a flat [org.armman.sakhi.data.visit.Visit]
 * list, which no longer exists in this ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PadaSelectionViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakePadaRepository(
    var summaries: List<PadaSummary> = DEFAULT_DATA,
    var error: Exception? = null,
  ) : PadaRepository {
    override suspend fun getPadaSummaries(): List<PadaSummary> {
      error?.let { throw it }
      return summaries
    }
  }

  private lateinit var repository: FakePadaRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakePadaRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(): PadaSelectionViewModel {
    val viewModel = PadaSelectionViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `loadVisits populates padaCards directly from repository summaries`() {
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertEquals(listOf("Test Pada", "Ambewadi Pada"), state.padaCards.map { it.padaName })
    assertEquals(2, state.totalPadas)
    assertEquals(false, state.isLoading)
    assertEquals(false, state.hasError)
  }

  @Test
  fun `each card exposes the open and referral follow-up buckets exactly as the repository returned them`() {
    val viewModel = createViewModel()
    val testPada = viewModel.uiState.value.padaCards.first { it.padaName == "Test Pada" }

    assertEquals(4, testPada.visitsRemainingCount)
    assertEquals(3, testPada.open.womenCount)
    assertEquals(1, testPada.open.womenOverdueCount)
    assertEquals(2, testPada.open.childCount)
    assertEquals(1, testPada.referralFollowUp.womenCount)
    assertEquals(1, testPada.referralFollowUp.childCount)
  }

  @Test
  fun `search filters by pada name case-insensitively and restores on clear`() {
    val viewModel = createViewModel()

    viewModel.onSearchQueryChanged("ambe")
    assertEquals(listOf("Ambewadi Pada"), viewModel.uiState.value.padaCards.map { it.padaName })

    viewModel.onSearchQueryChanged("zzz")
    assertTrue(viewModel.uiState.value.padaCards.isEmpty())

    viewModel.onSearchQueryChanged("")
    assertEquals(2, viewModel.uiState.value.padaCards.size)
  }

  @Test
  fun `repository failure sets error and retry recovers`() {
    repository.error = IOException("offline")
    val viewModel = createViewModel()
    assertTrue(viewModel.uiState.value.hasError)

    repository.error = null
    viewModel.loadVisits()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(!viewModel.uiState.value.hasError)
    assertEquals(2, viewModel.uiState.value.padaCards.size)
  }

  private companion object {
    val DEFAULT_DATA = listOf(
      PadaSummary(
        padaId = "pada-1",
        padaName = "Test Pada",
        villageName = "Test Village",
        open = PadaVisitBucket(womenCount = 3, womenOverdueCount = 1, childCount = 2, childOverdueCount = 0),
        referralFollowUp = PadaVisitBucket(womenCount = 1, womenOverdueCount = 0, childCount = 1, childOverdueCount = 0),
        visitsRemainingCount = 4,
      ),
      PadaSummary(
        padaId = "pada-2",
        padaName = "Ambewadi Pada",
        villageName = "Ambewadi",
        open = PadaVisitBucket(womenCount = 1, womenOverdueCount = 0, childCount = 1, childOverdueCount = 0),
        referralFollowUp = PadaVisitBucket(womenCount = 1, womenOverdueCount = 0, childCount = 0, childOverdueCount = 0),
        visitsRemainingCount = 3,
      ),
    )
  }
}
