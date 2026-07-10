package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Tabs + horizontally swipeable content, kept in two-way sync:
 * tapping a tab animates the pager; a completed swipe reports the new tab.
 * Shared behavior for every tabbed screen in the app.
 */
@Composable
fun AppTabPager(
  tabs: List<String>,
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
  tabRowModifier: Modifier = Modifier,
  pageContent: @Composable (page: Int) -> Unit,
) {
  val pagerState = rememberPagerState(initialPage = selectedIndex) { tabs.size }
  val currentSelected by rememberUpdatedState(selectedIndex)
  val currentOnTabSelected by rememberUpdatedState(onTabSelected)

  // Tab tap -> animate the pager to that page.
  LaunchedEffect(selectedIndex) {
    if (pagerState.currentPage != selectedIndex) {
      pagerState.animateScrollToPage(selectedIndex)
    }
  }
  // Swipe settled -> report the new tab (guarded against feedback loops).
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect { page ->
      if (page != currentSelected) currentOnTabSelected(page)
    }
  }

  Column(modifier = modifier) {
    AppTabRow(
      tabs = tabs,
      selectedIndex = selectedIndex,
      onTabSelected = onTabSelected,
      modifier = tabRowModifier,
    )
    HorizontalPager(
      state = pagerState,
      verticalAlignment = Alignment.Top,
      modifier = Modifier.fillMaxSize(),
    ) { page ->
      pageContent(page)
    }
  }
}
