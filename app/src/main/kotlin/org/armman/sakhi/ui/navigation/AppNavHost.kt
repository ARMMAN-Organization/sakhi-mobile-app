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
import org.armman.sakhi.ui.enrollment.EnrollmentScreen
import org.armman.sakhi.ui.forms.DynamicMotherRegistrationScreen
import org.armman.sakhi.ui.home.HomeScreen
import org.armman.sakhi.ui.login.LoginScreen
import org.armman.sakhi.ui.previsithealthhistory.PreVisitHealthHistoryScreen
import org.armman.sakhi.ui.profile.ProfileScreen
import org.armman.sakhi.ui.visitform.VisitFormScreen
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
  const val ENROLLMENT = "enrollment"
  /** CR-018: dynamic, backend-schema-driven Pregnant Woman enrollment — the real path from the
   * entry selector now, replacing the static Consent/Personal Info/Health History steps. */
  const val MOTHER_REGISTRATION = "enrollment/mother-registration"
  const val PRE_VISIT_HEALTH_HISTORY = "previsit-health-history/{beneficiaryId}/{visitId}/{label}"
  const val VISIT_FORM = "visit-form/{beneficiaryId}/{visitId}/{label}"

  /** Builds a login route, optionally showing the post-logout success banner. */
  fun login(loggedOut: Boolean = false) = "login?loggedOut=$loggedOut"

  /** Builds the route for a single pada's visit list. */
  fun padaVisits(pada: String) = "visit-tracker/${Uri.encode(pada)}"

  /** Builds the route for a single beneficiary's profile. */
  fun beneficiaryProfile(id: String) = "beneficiary/${Uri.encode(id)}"

  /** Builds the route for the Pre-Visit Health History screen (FR-S-4.6). */
  fun preVisitHealthHistory(beneficiaryId: String, visitId: String, label: String) =
    "previsit-health-history/${Uri.encode(beneficiaryId)}/${Uri.encode(visitId)}/${Uri.encode(label)}"

  /** Builds the route for a single visit's Visit Form. */
  fun visitForm(beneficiaryId: String, visitId: String, label: String) =
    "visit-form/${Uri.encode(beneficiaryId)}/${Uri.encode(visitId)}/${Uri.encode(label)}"
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
        onRegisterNew = { navController.navigate(Routes.ENROLLMENT) },
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
    composable(Routes.ENROLLMENT) {
      EnrollmentScreen(
        // popBackStack from COMPLETE also lands Home — stepper is a single destination.
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
        onPregnantWomanSelected = { navController.navigate(Routes.MOTHER_REGISTRATION) },
      )
    }
    composable(Routes.MOTHER_REGISTRATION) {
      DynamicMotherRegistrationScreen(
        onBack = { navController.popBackStack() },
        onSubmitted = {
          // Same destination the static flow's COMPLETE step used to land on — back to Home,
          // with the whole Enrollment sub-graph cleared so system back doesn't re-enter it.
          navController.navigate(Routes.HOME) {
            popUpTo(Routes.ENROLLMENT) { inclusive = true }
          }
        },
      )
    }
    composable(
      route = Routes.BENEFICIARY_PROFILE,
      arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) {
      BeneficiaryProfileScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
        onStartVisit = { beneficiaryId, visit ->
          val route = if (visit.hasPreVisitHistory) {
            Routes.preVisitHealthHistory(beneficiaryId, visit.id, visit.label)
          } else {
            Routes.visitForm(beneficiaryId, visit.id, visit.label)
          }
          navController.navigate(route)
        },
      )
    }
    composable(
      route = Routes.PRE_VISIT_HEALTH_HISTORY,
      arguments = listOf(
        navArgument("beneficiaryId") { type = NavType.StringType },
        navArgument("visitId") { type = NavType.StringType },
        navArgument("label") { type = NavType.StringType },
      ),
    ) { backStackEntry ->
      val beneficiaryId = backStackEntry.arguments?.getString("beneficiaryId").orEmpty()
      val visitId = backStackEntry.arguments?.getString("visitId").orEmpty()
      val label = backStackEntry.arguments?.getString("label").orEmpty()
      PreVisitHealthHistoryScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
        onSeeProfile = { navController.navigate(Routes.beneficiaryProfile(beneficiaryId)) },
        onStartVisit = {
          // Pre-Visit screen is a dead end for back-nav — replace it so
          // system back from the Visit Form returns to Beneficiary Profile,
          // not back into this read-only screen.
          navController.navigate(Routes.visitForm(beneficiaryId, visitId, label)) {
            popUpTo(Routes.PRE_VISIT_HEALTH_HISTORY) { inclusive = true }
          }
        },
      )
    }
    composable(
      route = Routes.VISIT_FORM,
      arguments = listOf(
        navArgument("beneficiaryId") { type = NavType.StringType },
        navArgument("visitId") { type = NavType.StringType },
        navArgument("label") { type = NavType.StringType },
      ),
    ) {
      VisitFormScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
      )
    }
  }
}
