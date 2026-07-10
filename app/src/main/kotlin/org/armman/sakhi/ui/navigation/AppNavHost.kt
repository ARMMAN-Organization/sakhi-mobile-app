package org.armman.sakhi.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.armman.sakhi.ui.beneficiaries.BeneficiariesScreen
import org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileScreen
import org.armman.sakhi.ui.home.HomeScreen
import org.armman.sakhi.ui.login.LoginScreen
import org.armman.sakhi.ui.profile.ProfileScreen
import org.armman.sakhi.ui.visittracker.PadaSelectionScreen
import org.armman.sakhi.ui.visittracker.PadaVisitsScreen

object Routes {
  const val LOGIN = "login?loggedOut={loggedOut}"
  const val HOME = "home"
  const val BENEFICIARIES = "beneficiaries"
  const val VISIT_TRACKER = "visit-tracker"
  const val PADA_VISITS = "visit-tracker/{pada}"
  const val PROFILE = "profile"
  const val BENEFICIARY_PROFILE = "beneficiary/{id}"

  /** Builds a login route, optionally showing the post-logout success banner. */
  fun login(loggedOut: Boolean = false) = "login?loggedOut=$loggedOut"

  /** Builds the route for a single pada's visit list. */
  fun padaVisits(pada: String) = "visit-tracker/${Uri.encode(pada)}"

  /** Builds the route for a single beneficiary's profile. */
  fun beneficiaryProfile(id: String) = "beneficiary/${Uri.encode(id)}"
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
        onSeeVisitTracker = { navController.navigate(Routes.VISIT_TRACKER) },
        onProfile = { navController.navigate(Routes.PROFILE) },
      )
    }
    composable(Routes.PROFILE) {
      ProfileScreen(
        onBack = { navController.popBackStack() },
        onLoggedOut = {
          navController.navigate(Routes.login(loggedOut = true)) {
            // Logout must clear the entire back stack — back cannot re-enter.
            popUpTo(0) { inclusive = true }
          }
        },
      )
    }
    composable(Routes.BENEFICIARIES) {
      BeneficiariesScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
        onSeeProfile = { id -> navController.navigate(Routes.beneficiaryProfile(id)) },
      )
    }
    composable(Routes.VISIT_TRACKER) {
      PadaSelectionScreen(
        onBack = { navController.popBackStack() },
        onSeeVisits = { pada -> navController.navigate(Routes.padaVisits(pada)) },
        onProfile = { navController.navigate(Routes.PROFILE) },
      )
    }
    composable(
      route = Routes.PADA_VISITS,
      arguments = listOf(navArgument("pada") { type = NavType.StringType }),
    ) {
      PadaVisitsScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
        onSeeProfile = { id -> navController.navigate(Routes.beneficiaryProfile(id)) },
      )
    }
    composable(
      route = Routes.BENEFICIARY_PROFILE,
      arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) {
      BeneficiaryProfileScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
      )
    }
  }
}
