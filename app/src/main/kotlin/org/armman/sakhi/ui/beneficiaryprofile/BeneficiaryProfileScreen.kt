package org.armman.sakhi.ui.beneficiaryprofile

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisit
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisitAction
import org.armman.sakhi.data.reopen.ReopenRequestReason
import org.armman.sakhi.ui.beneficiaryprofile.components.IdentityCard
import org.armman.sakhi.ui.beneficiaryprofile.components.LastVisitStatsCard
import org.armman.sakhi.ui.beneficiaryprofile.components.VisitHistoryCard
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.FullScreenLoadingOverlay
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.components.StatusBanner
import org.armman.sakhi.ui.components.StatusBannerVariant
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.SerifTitle
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Beneficiary Profile detail per the "Beneficiary Profile Page" Figma board
 * (Mother + Child variants, mobile + tablet). CR-014a: identity card, state,
 * diagnosis and Last Visit Stats. The See Visits list + form actions land in
 * CR-014b; Edit is a stub ("coming soon") until the edit flow exists.
 */
@Composable
fun BeneficiaryProfileScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  onStartVisit: (beneficiaryId: String, visit: ProfileVisit) -> Unit = { _, _ -> },
  /** CR-Referral-01: opens the referral follow-up form for [visit]'s linked referral — only
   * ever invoked when [ProfileVisit.referralIncomplete] is true (see [ProfileContent]'s
   * routing). Default no-op keeps existing previews/tests that don't care about this working
   * unchanged. */
  onReferralFollowUp: (beneficiaryId: String, visit: ProfileVisit) -> Unit = { _, _ -> },
  /** Ad-hoc forms (Referral / Referral Follow-up / ANC Closure / Child Closure / Beneficiary
   * Reopen) opened directly from this profile - [formCode] is one of the five ad-hoc form codes.
   * [visitName] is only ever non-blank for Referral, once the Sakhi has picked "which visit is
   * this referral for" from the dialog [Footer] shows — see [AdHocFormViewModel]'s `visit_name`
   * prefill. Default no-op keeps existing previews/tests that don't care about this working
   * unchanged. */
  onStartAdHocForm: (beneficiaryId: String, formCode: String, visitName: String?) -> Unit = { _, _, _ -> },
  /** CR-042: opens the Delivery Event Session's entry step (`DELIVERY_VISIT` form) for
   * [beneficiaryId], either fresh or resuming — see [Footer]'s doc for how [DeliveryButtonState]
   * decides which. [sessionUuid] is minted once by this screen on a fresh start and re-passed as
   * stored on every resume (never re-minted), per [org.armman.sakhi.ui.delivery
   * .DeliverySessionViewModel.sessionUuid]'s doc. Default no-op keeps existing previews/tests that
   * don't care about this working unchanged. */
  onStartDelivery: (beneficiaryId: String, sessionUuid: String) -> Unit = { _, _ -> },
  /** CR-042: opens an already-generated visit (PP1, or a same-session NN once reachable) directly
   * via the existing Visit Form route — the hand-off [DeliveryButtonState.ResumeVisit] describes.
   * [label] is the schedule row's own `visitCode` (e.g. "PP1"). Default no-op keeps existing
   * previews/tests that don't care about this working unchanged. */
  onResumeDeliveryVisit: (beneficiaryId: String, localScheduleUuid: String, label: String) -> Unit = { _, _, _ -> },
  /** CR-042: opens [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationScreen] for the SAME
   * [sessionUuid] [DeliveryButtonState.ChildRegistrationPending] carries — see that state's own doc.
   * Default no-op keeps existing previews/tests that don't care about this working unchanged. */
  onContinueChildRegistration: (beneficiaryId: String, sessionUuid: String) -> Unit = { _, _ -> },
  /** CR-Registration-Edit Phase 1: IdentityCard's Edit pill — opens
   * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryFieldEditScreen] for this beneficiary's
   * MOTHER_REGISTRATION/CHILD_REGISTRATION fields. Default no-op keeps existing previews/tests
   * that don't care about this working unchanged. */
  onEditIdentity: (beneficiaryId: String, type: BeneficiaryType) -> Unit = { _, _ -> },
  viewModel: BeneficiaryProfileViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val context = LocalContext.current

  // Reruns on every fresh entry into composition, including a pop-back from Start Visit — so a
  // visit just marked COMPLETED (or any other change made downstream) shows up immediately instead
  // of the stale snapshot this retained ViewModel loaded the first time it was created. See
  // BeneficiaryProfileViewModel's doc for why this isn't in its init{} instead.
  LaunchedEffect(Unit) { viewModel.loadProfile() }
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }
  val comingSoon = stringResource(R.string.beneficiary_profile_coming_soon)
  val onComingSoon = { Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show() }

  // Fire-and-forget Toasts for BeneficiaryProfileViewModel.submitReopenRequest's outcome — mirrors
  // how every other submit flow in this app surfaces its result (e.g. AdHocFormScreen's own
  // events collection), just via a Toast rather than navigation since Reopen has nowhere to
  // navigate to afterwards.
  LaunchedEffect(Unit) {
    viewModel.events.collectLatest { event ->
      when (event) {
        is BeneficiaryProfileEvent.ReopenRequested ->
          Toast.makeText(context, "Reopen request submitted", Toast.LENGTH_SHORT).show()
        is BeneficiaryProfileEvent.ReopenFailed ->
          Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.beneficiary_profile_back_title),
        subtitle = today,
        onBack = onBack,
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> LoadError(onRetry = viewModel::loadProfile)
          else -> state.profile?.let { profile ->
            ProfileContent(
              profile = profile,
              isTablet = isTablet,
              onComingSoon = { onComingSoon() },
              canStartVisit = state.canStartVisit,
              hasPendingReopenRequest = state.hasPendingReopenRequest,
              hasRejectedReopenRequest = state.hasRejectedReopenRequest,
              hasRejectedLmpChangeRequest = state.hasRejectedLmpChangeRequest,
              isReopenEligible = state.isReopenEligible,
              deliveryButtonState = state.deliveryButtonState,
              onStartVisit = { visit -> onStartVisit(profile.id, visit) },
              onReferralFollowUp = { visit -> onReferralFollowUp(profile.id, visit) },
              onStartAdHocForm = { formCode, visitName -> onStartAdHocForm(profile.id, formCode, visitName) },
              onReopen = viewModel::submitReopenRequest,
              onStartDelivery = { sessionUuid -> onStartDelivery(profile.id, sessionUuid) },
              onResumeDeliveryVisit = { localScheduleUuid, label ->
                onResumeDeliveryVisit(profile.id, localScheduleUuid, label)
              },
              onContinueChildRegistration = { sessionUuid ->
                onContinueChildRegistration(profile.id, sessionUuid)
              },
              onEditIdentity = { onEditIdentity(profile.id, profile.type) },
            )
          }
        }
      }
    }
    FullScreenLoadingOverlay(visible = state.isSubmittingReopen)
  }
}

@Composable
private fun ProfileContent(
  profile: BeneficiaryProfile,
  isTablet: Boolean,
  canStartVisit: Boolean,
  hasPendingReopenRequest: Boolean,
  hasRejectedReopenRequest: Boolean,
  hasRejectedLmpChangeRequest: Boolean,
  isReopenEligible: Boolean,
  deliveryButtonState: DeliveryButtonState,
  onComingSoon: () -> Unit,
  onStartVisit: (ProfileVisit) -> Unit,
  onReferralFollowUp: (ProfileVisit) -> Unit,
  onStartAdHocForm: (formCode: String, visitName: String?) -> Unit,
  onReopen: (ReopenRequestReason) -> Unit,
  onStartDelivery: (sessionUuid: String) -> Unit,
  onResumeDeliveryVisit: (localScheduleUuid: String, label: String) -> Unit,
  onContinueChildRegistration: (sessionUuid: String) -> Unit,
  onEditIdentity: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    // Scrollable body; the Delivery/Closure footer stays pinned below it.
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(Dimens.ItemSpacing),
    ) {
      IdentityCard(profile = profile, isTablet = isTablet, onEdit = onEditIdentity)
      // Task 4 (LMP/Reopen/Referral/Audit task list): a persistent banner, not a one-time Toast --
      // detected on every profile load, so it must stay visible for as long as the server still
      // reports the rejection, same rationale as the Reopen-rejection banner in Footer below.
      // MOTHER-only (LMP is never asked of an infant profile), and independent of
      // ACTIVE/CLOSED status -- unlike Reopen, an LMP correction has no status gating of its own.
      if (profile.type == BeneficiaryType.MOTHER && hasRejectedLmpChangeRequest) {
        StatusBanner(
          message = "Your LMP correction request was rejected. Please check with your Supervisor.",
          variant = StatusBannerVariant.Error,
          modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
        )
      }
      LastVisitStatsCard(
        stats = profile.lastVisitStats,
        isTablet = isTablet,
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      )
      // CR-022f: the section header always renders. Before, an empty list hid the whole section,
      // which left a beneficiary with no generated schedule looking identical to one whose profile
      // simply had no visits area — indistinguishable from a rendering bug.
      Text(
        text = stringResource(R.string.beneficiary_profile_see_visits),
        style = SerifTitle,
        color = NeutralG400,
        modifier = Modifier.padding(top = Dimens.ScreenPadding),
      )
      if (profile.visits.isEmpty()) {
        Text(
          text = stringResource(R.string.beneficiary_profile_no_visits),
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG400,
          modifier = Modifier.padding(top = Dimens.ItemSpacing),
        )
      } else {
        profile.visits.forEach { visit ->
          VisitHistoryCard(
            visit = visit,
            isTablet = isTablet,
            // CR-016: Start Visit / Fill Form now open the Visit Form flow;
            // See Data / Referral remain stubbed until their own CRs land.
            // CR-Referral-01: a completed visit with a pending referral follow-up reuses the
            // FILL_FORM action (see ProfileVisitMapper), but must route to the referral follow-up
            // form instead — checked first, since referralIncomplete never coincides with an
            // OPEN visit's own START_VISIT/FILL_FORM meaning.
            onAction = {
              val opensVisitForm = visit.action == ProfileVisitAction.START_VISIT ||
                visit.action == ProfileVisitAction.FILL_FORM
              if (visit.referralIncomplete) {
                onReferralFollowUp(visit)
              } else if (opensVisitForm && canStartVisit) {
                // CR-022g: the Visit Form is still backed by seeded data that only recognises its
                // own ids, so opening it for a Sakhi's own enrolment throws and lands her on an
                // error screen. Until CR-026 gives it real data, say "coming soon" instead of
                // breaking.
                onStartVisit(visit)
              } else {
                onComingSoon()
              }
            },
            modifier = Modifier.padding(top = Dimens.ItemSpacing),
          )
        }
      }
    }
    Footer(
      beneficiaryType = profile.type,
      status = profile.status,
      visits = profile.visits,
      hasPendingReopenRequest = hasPendingReopenRequest,
      hasRejectedReopenRequest = hasRejectedReopenRequest,
      isReopenEligible = isReopenEligible,
      deliveryButtonState = deliveryButtonState,
      onComingSoon = onComingSoon,
      onStartAdHocForm = onStartAdHocForm,
      onReopen = onReopen,
      onStartDelivery = onStartDelivery,
      onResumeDeliveryVisit = onResumeDeliveryVisit,
      onContinueChildRegistration = onContinueChildRegistration,
    )
  }
}

/**
 * Pinned form actions. Closure Form routes to ANC_CLOSURE_VISIT for a mother or
 * CHILD_CLOSURE_VISIT for an infant, per [BeneficiaryType].
 *
 * Delivery (CR-042) branches on [deliveryButtonState]:
 * - [DeliveryButtonState.NotStarted] — mints a fresh session id and opens the `DELIVERY_VISIT`
 *   form via [onStartDelivery].
 * - [DeliveryButtonState.ResumeDeliveryForm] — reopens that SAME session id (see the state's own
 *   doc for why this currently can't happen in practice).
 * - [DeliveryButtonState.ChildRegistrationPending] — opens
 *   [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationScreen] via [onContinueChildRegistration]
 *   for the same session id it carries.
 * - [DeliveryButtonState.ResumeVisit] — hands off straight to the Visit Form via
 *   [onResumeDeliveryVisit], skipping this screen's own (non-existent) visit UI.
 * - [DeliveryButtonState.Completed] — disabled "Delivery Recorded", unchanged from before CR-042's
 *   session-aware button existed.
 * - [DeliveryButtonState.NotApplicable] — no Delivery button at all (non-MOTHER profile).
 *
 * Referral / Referral Follow-up have no other natural entry point on this screen or elsewhere yet
 * (`ReferralRepository` has no consuming screen -- see its own doc). Rather than leave two of the
 * five ad-hoc forms with no way to open them at all, a second row is added here as the
 * least-bad net-new entry point -- a judgement call, flagged for product/design review rather
 * than assumed correct.
 *
 * Reopen is deliberately NOT one of the five schema-driven ad-hoc forms any more here: unlike
 * Closure, `POST /reopen-requests` takes a small fixed enum (`requestReason`), not a dynamic
 * form's worth of answers, and is a distinct backend call from `POST /forms/BENEFICIARY_REOPEN
 * _VISIT/submissions`. Rather than send a Reopen through the generic ad-hoc-form screen and then
 * ALSO call `POST /reopen-requests` from the coordinator (as Closure does for its own two form
 * codes), Reopen gets its own tiny reason-picker dialog here -- reusing the same
 * dialog-then-submit shape [ReferralVisitPickerDialog] already established for Referral -- and
 * calls [onReopen] directly. This is a judgment call flagged for product/design review: it means
 * a Reopen is no longer captured as an ad-hoc form submission/draft at all, only as a
 * `POST /reopen-requests` row.
 *
 * Task-6 (rejection half): when [hasRejectedReopenRequest] is true, a [StatusBanner] renders above
 * the Reopen button telling the Sakhi her previous request was rejected -- the Reopen button
 * itself is untouched (still driven by [isReopenEligible]/[hasPendingReopenRequest] exactly as
 * before), since a rejection doesn't change eligibility, it just means she may want to try again.
 */
@Composable
private fun Footer(
  beneficiaryType: BeneficiaryType,
  status: BeneficiaryStatus,
  visits: List<ProfileVisit>,
  hasPendingReopenRequest: Boolean,
  hasRejectedReopenRequest: Boolean,
  isReopenEligible: Boolean,
  deliveryButtonState: DeliveryButtonState,
  onComingSoon: () -> Unit,
  onStartAdHocForm: (formCode: String, visitName: String?) -> Unit,
  onReopen: (ReopenRequestReason) -> Unit,
  onStartDelivery: (sessionUuid: String) -> Unit,
  onResumeDeliveryVisit: (localScheduleUuid: String, label: String) -> Unit,
  onContinueChildRegistration: (sessionUuid: String) -> Unit,
) {
  val closureFormCode = if (beneficiaryType == BeneficiaryType.INFANT) {
    AD_HOC_FORM_CODE_CHILD_CLOSURE
  } else {
    AD_HOC_FORM_CODE_ANC_CLOSURE
  }
  // "Which visit is this referral for?" (spec row 2: "Autopopulate visit name from which visit
  // referral is flagged") — the Referral button has no visit context of its own (it's a
  // profile-level action, not opened from inside a specific visit), so the Sakhi is asked to pick
  // one from her visit history before the form opens. Blank/no visits at all still opens the form
  // (via "Skip") rather than trapping her — `visit_name` just stays unprefilled in that case.
  var showVisitPicker by remember { mutableStateOf(false) }
  var showReopenReasonPicker by remember { mutableStateOf(false) }
  Column {
    HorizontalDivider(color = NeutralG50)
    // Delivery only applies to a mother's own journey; Closure is hidden once she's already
    // CLOSED (closing her again is meaningless -- it used to stay visible and re-open the
    // closure form indefinitely). For a non-mother beneficiary who is CLOSED, neither button
    // has anything to show, so skip the row entirely rather than leaving an empty gap.
    val showDelivery = beneficiaryType == BeneficiaryType.MOTHER
    val showClosure = status != BeneficiaryStatus.CLOSED
    if (showDelivery || showClosure) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        modifier = Modifier.fillMaxWidth().padding(Dimens.ItemSpacing),
      ) {
        // Delivery only applies to a mother's own journey (recording her delivery outcome) — an
        // infant's profile has no delivery event of its own. Per PRD: "Delivery - This will be
        // shown only in case of pregnant women."
        if (showDelivery) {
          DeliveryButton(
            state = deliveryButtonState,
            onStartDelivery = onStartDelivery,
            onResumeDeliveryVisit = onResumeDeliveryVisit,
            onContinueChildRegistration = onContinueChildRegistration,
            modifier = Modifier.weight(1f),
          )
        }
        if (showClosure) {
          SecondaryButton(
            text = stringResource(R.string.beneficiary_profile_closure_form),
            onClick = { onStartAdHocForm(closureFormCode, null) },
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
    // Referral / Referral Follow-up hidden — not in this sprint scope (temporary; see CR for
    // restoring). Reopen (CR-Closure-02) is re-enabled below on its own, since its data layer
    // (submission + supervisor routing) is built and tested independently of Referral's.
    if (false) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.fillMaxWidth().padding(
        start = Dimens.ItemSpacing,
        end = Dimens.ItemSpacing,
        bottom = Dimens.ItemSpacing,
      ),
    ) {
      SecondaryButton(
        text = "Referral",
        onClick = {
          if (visits.isEmpty()) onStartAdHocForm(AD_HOC_FORM_CODE_REFERRAL, null) else showVisitPicker = true
        },
        modifier = Modifier.weight(1f),
      )
      SecondaryButton(
        text = "Referral Follow-up",
        onClick = { onStartAdHocForm(AD_HOC_FORM_CODE_REFERRAL_FOLLOWUP, null) },
        modifier = Modifier.weight(1f),
      )
    }
    }
    // CR-Closure-02: only offered once the beneficiary is actually CLOSED and, per the SRS
    // Beneficiary Reopen form / design-discussion transcript, not for a death/miscarriage/abortion
    // closure — see BeneficiaryProfileViewModel.isReopenEligible's own doc for the exact rule and
    // its "unknown reason defaults to eligible" caveat.
    if (isReopenEligible) {
      // Task-6 (rejection half): a persistent banner, not a one-time Toast -- this is detected on
      // every profile load (same as hasPendingReopenRequest/hasApprovedReopenRequest), so it must
      // stay visible for as long as the server still reports the rejection, rather than firing
      // once and being lost if the Sakhi navigates away before seeing it.
      if (hasRejectedReopenRequest) {
        StatusBanner(
          message = "Your reopen request was rejected. You can submit a new request.",
          variant = StatusBannerVariant.Error,
          modifier = Modifier.fillMaxWidth().padding(
            start = Dimens.ItemSpacing,
            end = Dimens.ItemSpacing,
            bottom = Dimens.ItemSpacing,
          ),
        )
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        modifier = Modifier.fillMaxWidth().padding(
          start = Dimens.ItemSpacing,
          end = Dimens.ItemSpacing,
          bottom = Dimens.ItemSpacing,
        ),
      ) {
        SecondaryButton(
          // hasPendingReopenRequest: a second request while one is already PENDING would just be
          // noise for whichever supervisor reviews it -- disabled with different copy rather than
          // hidden, so the Sakhi can see her request was registered.
          text = if (hasPendingReopenRequest) "Reopen pending review" else "Reopen",
          onClick = { if (!hasPendingReopenRequest) showReopenReasonPicker = true },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
  if (showVisitPicker) {
    ReferralVisitPickerDialog(
      visits = visits,
      onDismiss = { showVisitPicker = false },
      onPicked = { visitName ->
        showVisitPicker = false
        onStartAdHocForm(AD_HOC_FORM_CODE_REFERRAL, visitName)
      },
    )
  }
  if (showReopenReasonPicker) {
    ReopenReasonPickerDialog(
      onDismiss = { showReopenReasonPicker = false },
      onPicked = { reason ->
        showReopenReasonPicker = false
        onReopen(reason)
      },
    )
  }
}

/** The Delivery footer button itself — pulled out of [Footer] since [DeliveryButtonState] now has
 * five distinct label/enabled/onClick combinations instead of the old boolean's two. See
 * [Footer]'s own doc for what each state means. */
@Composable
private fun DeliveryButton(
  state: DeliveryButtonState,
  onStartDelivery: (sessionUuid: String) -> Unit,
  onResumeDeliveryVisit: (localScheduleUuid: String, label: String) -> Unit,
  onContinueChildRegistration: (sessionUuid: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val deliveryFormLabel = stringResource(R.string.beneficiary_profile_delivery_form)
  val deliveryRecordedLabel = stringResource(R.string.beneficiary_profile_delivery_recorded)
  val childRegistrationLabel = stringResource(R.string.beneficiary_profile_delivery_continue_child_registration)
  val pp1Label = stringResource(R.string.beneficiary_profile_delivery_continue_pp1)
  val nnLabel = stringResource(R.string.beneficiary_profile_delivery_continue_nn)

  val (text, enabled, onClick) = when (state) {
    DeliveryButtonState.NotStarted ->
      Triple(deliveryFormLabel, true) { onStartDelivery(UUID.randomUUID().toString()) }
    is DeliveryButtonState.ResumeDeliveryForm ->
      Triple(deliveryFormLabel, true) { onStartDelivery(state.sessionUuid) }
    is DeliveryButtonState.ChildRegistrationPending ->
      Triple(childRegistrationLabel, true) { onContinueChildRegistration(state.sessionUuid) }
    is DeliveryButtonState.ResumeVisit -> {
      val label = if (state.label.startsWith("NN")) nnLabel else pp1Label
      Triple(label, true) { onResumeDeliveryVisit(state.localScheduleUuid, state.label) }
    }
    DeliveryButtonState.Completed, DeliveryButtonState.NotApplicable ->
      Triple(deliveryRecordedLabel, false) {}
  }

  SecondaryButton(
    text = text,
    onClick = onClick,
    enabled = enabled,
    modifier = modifier,
  )
}

/** "Which visit is this referral for?" — see [Footer]'s doc. A plain list of every visit already
 * on the profile ([ProfileVisit.label], e.g. "ANC 3" or "Enrollment"), newest first (matching
 * [BeneficiaryProfile.visits]' own order) — no filtering by [ProfileVisitState], since a referral
 * can legitimately be flagged from a visit that's already COMPLETED, not just one still OPEN. */
@Composable
private fun ReferralVisitPickerDialog(
  visits: List<ProfileVisit>,
  onDismiss: () -> Unit,
  onPicked: (visitName: String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Which visit is this referral for?") },
    text = {
      Column {
        visits.forEach { visit ->
          Text(
            text = visit.label,
            style = MaterialTheme.typography.bodyLarge,
            color = NeutralG400,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPicked(visit.label) }
              .padding(vertical = Dimens.ItemSpacing),
          )
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Skip") }
    },
  )
}

/** "Why is this beneficiary being reopened?" -- see [Footer]'s doc for why Reopen gets its own
 * dialog rather than opening BENEFICIARY_REOPEN_VISIT as a schema-driven ad-hoc form. A plain list
 * of [ReopenRequestReason]'s three fixed values (`POST /reopen-requests`'s own enum, not a
 * lookup-category fetch -- see that enum's doc), same list-of-rows shape as
 * [ReferralVisitPickerDialog]. */
@Composable
private fun ReopenReasonPickerDialog(
  onDismiss: () -> Unit,
  onPicked: (ReopenRequestReason) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Why is this beneficiary being reopened?") },
    text = {
      Column {
        ReopenRequestReason.entries.forEach { reason ->
          Text(
            text = reason.displayLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = NeutralG400,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPicked(reason) }
              .padding(vertical = Dimens.ItemSpacing),
          )
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

/** The four ad-hoc form codes the backend already recognises for
 * `GET/POST /forms/{formCode}/...` that this screen still opens as a schema-driven form. Kept
 * local to this screen (not a shared constants object) since this is currently the only place any
 * of them is referenced from the UI layer. `BENEFICIARY_REOPEN_VISIT` is no longer one of them --
 * see [Footer]'s doc. */
private const val AD_HOC_FORM_CODE_REFERRAL = "REFERRAL_VISIT"
private const val AD_HOC_FORM_CODE_REFERRAL_FOLLOWUP = "REFERRAL_FOLLOWUP_VISIT"
private const val AD_HOC_FORM_CODE_ANC_CLOSURE = "ANC_CLOSURE_VISIT"
private const val AD_HOC_FORM_CODE_CHILD_CLOSURE = "CHILD_CLOSURE_VISIT"

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
}

@Composable
private fun LoadError(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.beneficiary_profile_error_load),
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
    )
    PrimaryButton(
      text = stringResource(R.string.home_retry),
      onClick = onRetry,
      fullWidth = false,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
  }
}
