package org.armman.sakhi.ui.visittracker

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
class PadaSelectionViewModelTest {
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

  private fun createViewModel(): PadaSelectionViewModel {
    val viewModel = PadaSelectionViewModel(repository)
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `one card per pada, sorted alphabetically`() {
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertEquals(listOf("Jamsar", "Kelghar"), state.padaCards.map { it.pada })
    assertEquals(2, state.totalPadas)
  }

  @Test
  fun `aggregates are exact for the fixture`() {
    val viewModel = createViewModel()
    val jamsar = viewModel.uiState.value.padaCards.first { it.pada == "Jamsar" }

    assertEquals(2, jamsar.openWomen)
    assertEquals(1, jamsar.openWomenEnding)
    assertEquals(1, jamsar.openChildren)
    assertEquals(1, jamsar.referralWomen)
    assertEquals(1, jamsar.referralChildren)
    assertEquals(5, jamsar.remainingVisits)
  }

  @Test
  fun `search filters padas case-insensitively and restores on clear`() {
    val viewModel = createViewModel()

    viewModel.onSearchQueryChanged("kelG")
    assertEquals(listOf("Kelghar"), viewModel.uiState.value.padaCards.map { it.pada })

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
    val FIXTURE = listOf(
      visit("v1", "Sunita", VisitType.OPEN, "Jamsar", BeneficiaryType.MOTHER, ending = true),
      visit("v2", "Riya", VisitType.OPEN, "Jamsar", BeneficiaryType.MOTHER),
      visit("v3", "Baby of Riya", VisitType.OPEN, "Jamsar", BeneficiaryType.INFANT),
      visit("v4", "Meena", VisitType.REFERRAL_FOLLOWUP, "Jamsar", BeneficiaryType.MOTHER),
      visit("v5", "Baby of Meena", VisitType.REFERRAL_FOLLOWUP, "Jamsar", BeneficiaryType.INFANT),
      visit("v6", "Abha", VisitType.OPEN, "Kelghar", BeneficiaryType.MOTHER),
    )

    fun visit(
      id: String,
      name: String,
      type: VisitType,
      pada: String,
      beneficiaryType: BeneficiaryType,
      ending: Boolean = false,
    ) = Visit(
      id = id,
      beneficiaryId = "b-$id",
      beneficiaryName = name,
      beneficiaryType = beneficiaryType,
      riskLevel = RiskLevel.LOW,
      visitType = type,
      isEnding = ending,
      pada = pada,
      scheduleDate = LocalDate.of(2026, 4, 24),
      visitLabel = "ANC 3",
      daysRemaining = 2,
      phoneNumber = "+911234567890",
    )
  }
}
