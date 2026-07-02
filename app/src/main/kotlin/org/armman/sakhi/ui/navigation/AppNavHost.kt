package org.armman.sakhi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.armman.sakhi.ui.home.HomeScreen

object Routes {
  const val HOME = "home"
}

/** Top-level navigation graph for the Sakhi app. */
@Composable
fun AppNavHost() {
  val navController = rememberNavController()
  NavHost(navController = navController, startDestination = Routes.HOME) {
    composable(Routes.HOME) { HomeScreen() }
  }
}
