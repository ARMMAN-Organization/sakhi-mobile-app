package org.armman.sakhi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.armman.sakhi.ui.beneficiaries.BeneficiariesScreen
import org.armman.sakhi.ui.home.HomeScreen
import org.armman.sakhi.ui.login.LoginScreen

object Routes {
  const val LOGIN = "login?loggedOut={loggedOut}"
  const val HOME = "home"
  const val BENEFICIARIES = "beneficiaries"

  /** Builds a login route, optionally showing the post-logout success banner. */
  fun login(loggedOut: Boolean = false) = "login?loggedOut=$loggedOut"
}

/** Top-level navigation graph for the Sakhi app. */
@Composable
fun AppNavHost() {
  val navController = rememberNavController()
  NavHost(navController = navController, startDestination = Routes.LOGIN) {
    composable(
      route = Routes.LOGIN,
      arguments = listOf(
        navArgument("loggedOut") {
          type = NavType.BoolType
          defaultValue = false
        },
      ),
    ) { backStackEntry ->
      LoginScreen(
        showLogoutBanner = backStackEntry.arguments?.getBoolean("loggedOut") ?: false,
        onLoginSuccess = {
          navController.navigate(Routes.HOME) {
            // Login must not remain on the back stack after authentication.
            popUpTo(Routes.LOGIN) { inclusive = true }
          }
        },
      )
    }
    composable(Routes.HOME) {
      HomeScreen(
        onAllBeneficiaries = { navController.navigate(Routes.BENEFICIARIES) },
      )
    }
    composable(Routes.BENEFICIARIES) {
      BeneficiariesScreen(onBack = { navController.popBackStack() })
    }
  }
}
