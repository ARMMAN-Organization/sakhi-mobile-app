package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visit.VisitType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PadaVisitsViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeVisitRepository(
    var visits: List<Visit> = FIXTURE,
    var error: Exception? = null,
  ) : VisitRepository {
    override suspend fun getTodaysVisits(): List<Visit> {
      error?.let { throw it }
      return visits
    }
  }

  private lateinit var repository: FakeVisitRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeVisitRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(pada: String = "Jamsar"): PadaVisitsViewModel {
    val viewModel = PadaVisitsViewModel(
      repository,
      SavedStateHandle(mapOf(PadaVisitsViewModel.NAV_ARG_PADA to pada)),
    )
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `loads only the requested pada with correct tab counts`() {
    val viewModel = createViewModel("Jamsar")
    val state = viewModel.uiState.value

    assertEquals("Jamsar", state.pada)
    assertEquals(2, state.openCount)
    assertEquals(1, state.referralCount)
    // Default tab is OPEN.
    assertEquals(listOf("v1", "v2"), state.visits.map { it.id })
  }

  @Test
  fun `tab switch filters by visit type`() {
    val viewModel = createViewModel("Jamsar")

    viewModel.onTabSelected(VisitType.REFERRAL_FOLLOWUP)
    assertEquals(listOf("v4"), viewModel.uiState.value.visits.map { it.id })

    viewModel.onTabSelected(VisitType.OPEN)
    assertEquals(2, viewModel.uiState.value.visits.size)
  }

  @Test
  fun `search filters names within the selected tab`() {
    val viewModel = createViewModel("Jamsar")

    viewModel.onSearchQueryChanged("sUnItA")
    assertEquals(listOf("v1"), viewModel.uiState.value.visits.map { it.id })

    // Meena is in the referral tab, so no result while OPEN is selected.
    viewModel.onSearchQueryChanged("Meena")
    assertTrue(viewModel.uiState.value.visits.isEmpty())

    viewModel.onTabSelected(VisitType.REFERRAL_FOLLOWUP)
    assertEquals(listOf("v4"), viewModel.uiState.value.visits.map { it.id })
  }

  @Test
  fun `pada with zero visits of one type shows empty list and zero count`() {
    val viewModel = createViewModel("Kelghar")
    val state = viewModel.uiState.value

    assertEquals(1, state.openCount)
    assertEquals(0, state.referralCount)

    viewModel.onTabSelected(VisitType.REFERRAL_FOLLOWUP)
    assertTrue(viewModel.uiState.value.visits.isEmpty())
  }

  @Test
  fun `visitsByType holds both tabs simultaneously with counts intact`() {
    val viewModel = createViewModel("Jamsar")
    val state = viewModel.uiState.value

    assertEquals(listOf("v1", "v2"), state.visitsByType[VisitType.OPEN]?.map { it.id })
    assertEquals(listOf("v4"), state.visitsByType[VisitType.REFERRAL_FOLLOWUP]?.map { it.id })

    // Search filters both lists; counts stay from the unfiltered pada lists.
    viewModel.onSearchQueryChanged("Sunita")
    val filtered = viewModel.uiState.value
    assertEquals(listOf("v1"), filtered.visitsByType[VisitType.OPEN]?.map { it.id })
    assertTrue(filtered.visitsByType[VisitType.REFERRAL_FOLLOWUP].orEmpty().isEmpty())
    assertEquals(2, filtered.openCount)
    assertEquals(1, filtered.referralCount)
  }

  @Test
  fun `repository failure sets error and retry recovers`() {
    repository.error = IOException("offline")
    val viewModel = createViewModel("Jamsar")
    assertTrue(viewModel.uiState.value.hasError)

    repository.error = null
    viewModel.loadVisits()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(!state.hasError)
    assertEquals(2, state.openCount)
  }

  private companion object {
    val FIXTURE = listOf(
      visit("v1", "Sunita Sharma", VisitType.OPEN, "Jamsar"),
      visit("v2", "Riya Verma", VisitType.OPEN, "Jamsar"),
      visit("v4", "Meena Gavit", VisitType.REFERRAL_FOLLOWUP, "Jamsar"),
      visit("v6", "Abha Mhatre", VisitType.OPEN, "Kelghar"),
    )

    fun visit(id: String, name: String, type: VisitType, pada: String) = Visit(
      id = id,
      beneficiaryId = "b-$id",
      beneficiaryName = name,
      beneficiaryType = BeneficiaryType.MOTHER,
      riskLevel = RiskLevel.LOW,
      visitType = type,
      isEnding = false,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 4, 24),
      visitLabel = "ANC 3",
      daysRemaining = 2,
      phoneNumber = "+911234567890",
    )
  }
}
