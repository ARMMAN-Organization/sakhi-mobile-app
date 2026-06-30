package org.armman.sakhi.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial state is Idle`() {
    val viewModel = HomeViewModel()
    assertEquals(HomeUiState.Idle, viewModel.uiState.value)
  }

  @Test
  fun `sync moves to Synced on success`() = runTest(dispatcher) {
    val viewModel = HomeViewModel()
    viewModel.onSyncClicked()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(HomeUiState.Synced, viewModel.uiState.value)
  }
}
