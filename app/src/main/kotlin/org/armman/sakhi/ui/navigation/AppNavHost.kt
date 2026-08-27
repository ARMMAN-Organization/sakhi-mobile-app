package org.armman.sakhi.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.armman.sakhi.ui.adhocform.AdHocFormScreen
import org.armman.sakhi.ui.beneficiaries.BeneficiariesScreen
import org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileScreen
import org.armman.sakhi.ui.childregistration.DynamicChildRegistrationScreen
import org.armman.sakhi.ui.delivery.DeliveryChildRegistrationScreen
import org.armman.sakhi.ui.delivery.DeliverySessionScreen
import org.armman.sakhi.ui.enrollment.EnrollmentScreen
import org.armman.sakhi.ui.forms.DynamicMotherRegistrationScreen
import org.armman.sakhi.ui.home.HomeScreen
import org.armman.sakhi.ui.login.LoginScreen
import org.armman.sakhi.ui.previsithealthhistory.PreVisitHealthHistoryScreen
import org.armman.sakhi.ui.profile.ProfileScreen
import org.armman.sakhi.ui.visitform.DynamicVisitFormScreen
import org.armman.sakhi.ui.visittracker.PadaSelectionScreen
import org.armman.sakhi.ui.visittracker.PadaVisitsScreen

object Routes {
  const val LOGIN = "login?loggedOut={loggedOut}"
  const val HOME = "home"
  const val BENEFICIARIES = "beneficiaries"
  const val VISIT_TRACKER = "visit-tracker"
  const val PADA_VISITS = "visit-tracker/{padaId}/{padaName}"
  const val PROFILE = "profile"
  const val BENEFICIARY_PROFILE = "beneficiary/{id}"
  const val ENROLLMENT = "enrollment"
  /** CR-018: dynamic, backend-schema-driven Pregnant Woman enrollment — the real path from the
   * entry selector now, replacing the static Consent/Personal Info/Health History steps. */
  const val MOTHER_REGISTRATION = "enrollment/mother-registration"
  /** CR-020: dynamic, backend-schema-driven Children Register — the "Child" path from the entry
   * selector, a standalone fork of the mother-registration flow. */
  const val CHILD_REGISTRATION = "enrollment/child-registration"
  const val PRE_VISIT_HEALTH_HISTORY = "previsit-health-history/{beneficiaryId}/{visitId}/{label}"
  const val VISIT_FORM = "visit-form/{beneficiaryId}/{visitId}/{label}"
  /** Ad-hoc forms (Referral / Referral Follow-up / ANC Closure / Child Closure / Beneficiary
   * Reopen) opened directly from a beneficiary's profile — not tied to a scheduled visit, so
   * (unlike [VISIT_FORM]) there is no `visitId` segment; [formCode] picks which of the five.
   * `visitName` is an optional query param (same "login?loggedOut={loggedOut}" pattern as
   * [LOGIN]) — only ever non-blank for Referral, where the Sakhi picks "which visit is this
   * referral for" from a dialog on the profile screen before this route is even navigated to; see
   * [org.armman.sakhi.ui.adhocform.AdHocFormViewModel]'s `visit_name` prefill. */
  const val AD_HOC_FORM = "ad-hoc-form/{beneficiaryId}/{formCode}?visitName={visitName}"
  /** CR-042: the Delivery Event Session's entry step (`DELIVERY_VISIT` form) — opened directly
   * from a beneficiary's profile via a fresh or in-progress delivery session. [sessionUuid] is
   * minted once by the profile screen on first tap and re-passed on every resume so a
   * process-death-and-relaunch doesn't accidentally start a second session; see
   * [org.armman.sakhi.ui.delivery.DeliverySessionViewModel.sessionUuid]'s doc. */
  const val DELIVERY_SESSION = "delivery-session/{beneficiaryId}/{sessionUuid}"
  /** CR-042: the Delivery Event Session's `CHILD_REGISTRATION` step — opened directly from a
   * beneficiary's profile via [org.armman.sakhi.ui.beneficiaryprofile.DeliveryButtonState
   * .ChildRegistrationPending]'s [sessionUuid]. One screen instance handles the whole
   * twin/triplet sequence itself (see [org.armman.sakhi.ui.delivery
   * .DeliveryChildRegistrationViewModel]'s class doc) — this route is only ever entered once per
   * session, not once per child. */
  const val DELIVERY_CHILD_REGISTRATION = "delivery-child-registration/{beneficiaryId}/{sessionUuid}"

  /** Builds a login route, optionally showing the post-logout success banner. */
  fun login(loggedOut: Boolean = false) = "login?loggedOut=$loggedOut"

  /** Builds the route for a single pada's visit list. [padaName] travels alongside the id
   * purely for the screen's heading — the API itself is padaId-scoped. */
  fun padaVisits(padaId: String, padaName: String) =
    "visit-tracker/${Uri.encode(padaId)}/${Uri.encode(padaName)}"

  /** Builds the route for a single beneficiary's profile. */
  fun beneficiaryProfile(id: String) = "beneficiary/${Uri.encode(id)}"

  /** Builds the route for the Pre-Visit Health History screen (FR-S-4.6). */
  fun preVisitHealthHistory(beneficiaryId: String, visitId: String, label: String) =
    "previsit-health-history/${Uri.encode(beneficiaryId)}/${Uri.encode(visitId)}/${Uri.encode(label)}"

  /** Builds the route for a single visit's Visit Form. */
  fun visitForm(beneficiaryId: String, visitId: String, label: String) =
    "visit-form/${Uri.encode(beneficiaryId)}/${Uri.encode(visitId)}/${Uri.encode(label)}"

  /** Builds the route for one ad-hoc form. [visitName] is blank for every ad-hoc form except
   * Referral (see [AD_HOC_FORM]'s doc). */
  fun adHocForm(beneficiaryId: String, formCode: String, visitName: String = "") =
    "ad-hoc-form/${Uri.encode(beneficiaryId)}/${Uri.encode(formCode)}?visitName=${Uri.encode(visitName)}"

  /** Builds the route for the Delivery Event Session's entry step. */
  fun deliverySession(beneficiaryId: String, sessionUuid: String) =
    "delivery-session/${Uri.encode(beneficiaryId)}/${Uri.encode(sessionUuid)}"

  /** Builds the route for the Delivery Event Session's `CHILD_REGISTRATION` step. */
  fun deliveryChildRegistration(beneficiaryId: String, sessionUuid: String) =
    "delivery-child-registration/${Uri.encode(beneficiaryId)}/${Uri.encode(sessionUuid)}"
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
        onSeeVisits = { padaId, padaName ->
          navController.navigate(Routes.padaVisits(padaId, padaName))
        },
        onProfile = { navController.navigate(Routes.PROFILE) },
      )
    }
    composable(
      route = Routes.PADA_VISITS,
      arguments = listOf(
        navArgument("padaId") { type = NavType.StringType },
        navArgument("padaName") { type = NavType.StringType },
      ),
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
        onChildSelected = { navController.navigate(Routes.CHILD_REGISTRATION) },
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
        // "Start Visit Form" on the success screen → the woman just enrolled. Her visits are on her
        // profile (there is no per-beneficiary visit destination), and the profile resolves local
        // draft ids. The sub-graph is cleared the same way, so back from the profile lands Home
        // rather than re-entering a submitted form.
        onStartVisitForm = { beneficiaryId ->
          navController.navigate(Routes.beneficiaryProfile(beneficiaryId)) {
            popUpTo(Routes.ENROLLMENT) { inclusive = true }
          }
        },
        // Consent refused: same exit as a completed enrollment. Clearing the sub-graph matters more
        // here — system back must not re-enter a form the beneficiary declined.
        onConsentRefused = {
          navController.navigate(Routes.HOME) {
            popUpTo(Routes.ENROLLMENT) { inclusive = true }
          }
        },
      )
    }
    composable(Routes.CHILD_REGISTRATION) {
      DynamicChildRegistrationScreen(
        onBack = { navController.popBackStack() },
        onSubmitted = {
          // Back to Home, clearing the whole Enrollment sub-graph so system back doesn't re-enter
          // it — same behaviour as the mother-registration path.
          navController.navigate(Routes.HOME) {
            popUpTo(Routes.ENROLLMENT) { inclusive = true }
          }
        },
        // "See Visit Form" on the success screen → the child just enrolled. Same destination and
        // sub-graph-clearing behavior as the mother-registration path's onStartVisitForm.
        onStartVisitForm = { beneficiaryId ->
          navController.navigate(Routes.beneficiaryProfile(beneficiaryId)) {
            popUpTo(Routes.ENROLLMENT) { inclusive = true }
          }
        },
        // Consent refused: same exit as a completed registration. Clearing the sub-graph matters
        // more here — system back must not re-enter a form the beneficiary declined.
        onConsentRefused = {
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
        onStartAdHocForm = { beneficiaryId, formCode, visitName ->
          navController.navigate(Routes.adHocForm(beneficiaryId, formCode, visitName.orEmpty()))
        },
        // CR-042: Delivery Event Session — see Routes.DELIVERY_SESSION's own doc for the
        // sessionUuid contract (minted fresh vs. re-passed on resume).
        onStartDelivery = { beneficiaryId, sessionUuid ->
          navController.navigate(Routes.deliverySession(beneficiaryId, sessionUuid))
        },
        // CR-042: hand off straight into an already-generated visit (PP1, or a same-session NN
        // once reachable) — see DeliveryButtonState.ResumeVisit's own doc for why this bypasses
        // the Delivery Event Session screen entirely.
        onResumeDeliveryVisit = { beneficiaryId, localScheduleUuid, label ->
          navController.navigate(Routes.visitForm(beneficiaryId, localScheduleUuid, label))
        },
        // CR-042: continue an in-progress CHILD_REGISTRATION step — see
        // DeliveryButtonState.ChildRegistrationPending's own doc for the sessionUuid contract.
        onContinueChildRegistration = { beneficiaryId, sessionUuid ->
          navController.navigate(Routes.deliveryChildRegistration(beneficiaryId, sessionUuid))
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
      DynamicVisitFormScreen(
        onBack = { navController.popBackStack() },
        onProfile = { navController.navigate(Routes.PROFILE) },
      )
    }
    composable(
      route = Routes.AD_HOC_FORM,
      arguments = listOf(
        navArgument("beneficiaryId") { type = NavType.StringType },
        navArgument("formCode") { type = NavType.StringType },
        navArgument("visitName") {
          type = NavType.StringType
          defaultValue = ""
        },
      ),
    ) {
      AdHocFormScreen(
        onBack = { navController.popBackStack() },
      )
    }
    composable(
      route = Routes.DELIVERY_SESSION,
      arguments = listOf(
        navArgument("beneficiaryId") { type = NavType.StringType },
        navArgument("sessionUuid") { type = NavType.StringType },
      ),
    ) {
      DeliverySessionScreen(
        onBack = { navController.popBackStack() },
        onNavigateToVisit = { beneficiaryId, localScheduleUuid ->
          // PP1 is the only visit this screen can currently hand off into (see
          // DeliverySessionViewModel's class doc) — literal label matches this file's other
          // literal-label call sites (e.g. PADA_VISITS's visit list) rather than an extra lookup
          // just for a display string. Clears DELIVERY_SESSION from the back stack so system back
          // from PP1 returns to the profile, not back into the just-submitted delivery form.
          navController.navigate(Routes.visitForm(beneficiaryId, localScheduleUuid, "PP1")) {
            popUpTo(Routes.DELIVERY_SESSION) { inclusive = true }
          }
        },
        // CR-042: at least one live-born child — go straight into CHILD_REGISTRATION for the same
        // session rather than back to the profile. Also clears DELIVERY_SESSION from the back
        // stack, same rationale as the PP1 hand-off just above.
        onNavigateToChildRegistration = { beneficiaryId, sessionUuid ->
          navController.navigate(Routes.deliveryChildRegistration(beneficiaryId, sessionUuid)) {
            popUpTo(Routes.DELIVERY_SESSION) { inclusive = true }
          }
        },
      )
    }
    composable(
      route = Routes.DELIVERY_CHILD_REGISTRATION,
      arguments = listOf(
        navArgument("beneficiaryId") { type = NavType.StringType },
        navArgument("sessionUuid") { type = NavType.StringType },
      ),
    ) {
      DeliveryChildRegistrationScreen(
        onBack = { navController.popBackStack() },
        onNavigateToVisit = { beneficiaryId, localScheduleUuid ->
          // Every child is registered and the session moved to PP1 — same pop-the-session-route
          // convention as DELIVERY_SESSION's own hand-off into PP1, so system back from PP1 returns
          // to the profile, not back into a just-finished child registration screen.
          navController.navigate(Routes.visitForm(beneficiaryId, localScheduleUuid, "PP1")) {
            popUpTo(Routes.DELIVERY_CHILD_REGISTRATION) { inclusive = true }
          }
        },
      )
    }
  }
}
