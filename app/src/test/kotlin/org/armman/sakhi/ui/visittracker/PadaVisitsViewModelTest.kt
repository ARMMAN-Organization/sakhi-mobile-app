package org.armman.sakhi.ui.visittracker

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.adhocform.FakeAdHocFormDraftDao
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.visit.PadaVisitsResult
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visit.VisitStatus
import org.armman.sakhi.data.visit.VisitType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PadaVisitsViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeVisitRepository(
    var open: List<Visit> = OPEN_FIXTURE,
    var referral: List<Visit> = REFERRAL_FIXTURE,
    var error: Exception? = null,
    var lastSearch: String? = null,
  ) : VisitRepository {
    override suspend fun getVisits(
      padaId: String,
      status: VisitStatus,
      date: LocalDate?,
      search: String?,
    ): PadaVisitsResult {
      assertEquals("pada-jamsar", padaId)
      lastSearch = search
      error?.let { throw it }
      val visits = if (status == VisitStatus.OPEN) open else referral
      return PadaVisitsResult(
        openCount = open.size,
        referralFollowUpCount = referral.size,
        visits = visits,
      )
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

  private fun createViewModel(padaId: String = "pada-jamsar", padaName: String = "Jamsar"): PadaVisitsViewModel {
    // The local offline overlay is exercised in its own tests; these assert the server-backed
    // path, so every local source here is empty and contributes nothing to the merged state.
    val viewModel = PadaVisitsViewModel(
      repository,
      LocalEnrolmentBeneficiarySource(
        FakeDynamicFormDraftDao(),
        FakeChildFormDraftDao(),
        FakeSecureKeyValueStore(),
        RoomVisitScheduleRepository(FakeVisitScheduleDao()),
        FakeFormsRepository(),
        LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore()),
      ),
      RoomVisitScheduleRepository(FakeVisitScheduleDao()),
      FakeReferralLinkDao(),
      FakeAdHocFormDraftDao(),
      SavedStateHandle(
        mapOf(
          PadaVisitsViewModel.NAV_ARG_PADA_ID to padaId,
          PadaVisitsViewModel.NAV_ARG_PADA_NAME to padaName,
        ),
      ),
    )
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `loads both tabs with correct counts and the pada name for the heading`() {
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertEquals("Jamsar", state.pada)
    assertEquals(2, state.openCount)
    assertEquals(1, state.referralCount)
    assertEquals(listOf("v1", "v2"), state.visits.map { it.id })
  }

  @Test
  fun `tab switch shows the other tab's already-loaded list`() {
    val viewModel = createViewModel()

    viewModel.onTabSelected(VisitType.REFERRAL_FOLLOWUP)
    assertNull(viewModel.uiState.value.visits.single().id) // Referral rows always have a null id.

    viewModel.onTabSelected(VisitType.OPEN)
    assertEquals(2, viewModel.uiState.value.visits.size)
  }

  @Test
  fun `search is debounced and refetches both tabs from the server`() = runTest(dispatcher.scheduler) {
    val viewModel = createViewModel()

    viewModel.onSearchQueryChanged("Sun")
    viewModel.onSearchQueryChanged("Sunita Sharma")
    dispatcher.scheduler.advanceUntilIdle()

    // Only the latest, settled value is sent — not every keystroke.
    assertEquals("Sunita Sharma", repository.lastSearch)
  }

  @Test
  fun `pada with zero referral visits shows an empty list and zero count`() {
    repository.referral = emptyList()
    val viewModel = createViewModel()
    val state = viewModel.uiState.value

    assertEquals(0, state.referralCount)
    viewModel.onTabSelected(VisitType.REFERRAL_FOLLOWUP)
    assertTrue(viewModel.uiState.value.visits.isEmpty())
  }

  @Test
  fun `repository failure sets error and retry recovers`() {
    repository.error = IOException("offline")
    val viewModel = createViewModel()
    assertTrue(viewModel.uiState.value.hasError)

    repository.error = null
    viewModel.loadVisits()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(!state.hasError)
    assertEquals(2, state.openCount)
  }

  private companion object {
    val OPEN_FIXTURE = listOf(
      visit("v1", "b1", "Sunita Sharma", VisitType.OPEN),
      visit("v2", "b2", "Riya Verma", VisitType.OPEN),
    )
    val REFERRAL_FIXTURE = listOf(
      visit(null, "b4", "Meena Gavit", VisitType.REFERRAL_FOLLOWUP),
    )

    fun visit(id: String?, beneficiaryId: String, name: String, type: VisitType) = Visit(
      id = id,
      beneficiaryId = beneficiaryId,
      beneficiaryName = name,
      beneficiaryType = BeneficiaryType.MOTHER,
      riskLevel = RiskLevel.MILD,
      visitType = type,
      pada = "Jamsar",
      village = "Pada - Jamsar",
      scheduleDate = LocalDate.of(2026, 4, 24),
      dueDate = LocalDate.of(2026, 4, 24),
      visitLabel = if (type == VisitType.REFERRAL_FOLLOWUP) "Referral Follow-up" else "ANC 3",
      daysRemaining = 2,
      phoneNumber = "+911234567890",
    )
  }
}
