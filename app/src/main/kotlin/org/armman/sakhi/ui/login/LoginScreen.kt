package org.armman.sakhi.ui.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppIcons
import org.armman.sakhi.ui.components.AppLogo
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.StatusBanner
import org.armman.sakhi.ui.components.StatusBannerVariant
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White

/** How long the "logged out successfully" banner stays visible before auto-dismissing. */
private const val LOGOUT_BANNER_TIMEOUT_MS = 4_000L

/**
 * Login screen per the "Arogya Sakhi - Revamp" Figma (mobile 375x812):
 * logo, title, Username + Password fields, pill Login button, and a
 * success banner shown when the user arrives here right after logging out.
 * The banner auto-dismisses after [LOGOUT_BANNER_TIMEOUT_MS] or as soon as
 * the user starts interacting with the form.
 */
@Composable
fun LoginScreen(
  onLoginSuccess: () -> Unit,
  showLogoutBanner: Boolean = false,
  viewModel: LoginViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var passwordVisible by remember { mutableStateOf(false) }

  // Once dismissed (timeout elapsed or user interacted) the banner stays gone.
  // rememberSaveable survives config changes so it never reappears on rotation.
  var logoutBannerDismissed by rememberSaveable { mutableStateOf(false) }
  val logoutBannerVisible = shouldShowLogoutBanner(showLogoutBanner, logoutBannerDismissed)

  // Auto-dismiss after the timeout. Guarded by the dismissed flag so it won't
  // restart on an activity recreate once the banner is already gone.
  LaunchedEffect(showLogoutBanner) {
    if (showLogoutBanner && !logoutBannerDismissed) {
      delay(LOGOUT_BANNER_TIMEOUT_MS)
      logoutBannerDismissed = true
    }
  }

  // Hide as soon as the user interacts with the form.
  LaunchedEffect(state.username, state.password) {
    if (userHasInteractedWithLoginForm(state.username, state.password)) {
      logoutBannerDismissed = true
    }
  }

  LaunchedEffect(state.loginSucceeded) {
    if (state.loginSucceeded) {
      viewModel.onLoginHandled()
      onLoginSuccess()
    }
  }

  Surface(color = White, modifier = Modifier.fillMaxSize()) {
    Box(
      // `safeDrawingPadding()` covers system bars + display cutout + IME, and consumes those insets
      // for everything below it. A nested `.imePadding()` would therefore be a no-op — do not
      // re-add one (guarded by WindowInsetsConfigTest). The `verticalScroll` below is what makes
      // the shrunken content reachable once the keyboard takes the bottom inset.
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding(),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = Dimens.ScreenPadding),
      ) {
        Spacer(Modifier.height(72.dp))
        AppLogo(
          height = Dimens.LoginLogoHeight,
          contentDescription = stringResource(R.string.login_logo_content_description),
        )
        Spacer(Modifier.height(32.dp))
        Text(
          text = stringResource(R.string.login_title),
          style = MaterialTheme.typography.headlineMedium,
          color = NeutralG400,
        )
        Spacer(Modifier.height(32.dp))
        AppTextField(
          value = state.username,
          onValueChange = viewModel::onUsernameChanged,
          label = stringResource(R.string.login_username_label),
          placeholder = stringResource(R.string.login_username_placeholder),
          errorText = state.usernameError?.let { stringResource(it) },
          enabled = !state.isSubmitting,
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
          ),
        )
        Spacer(Modifier.height(Dimens.ItemSpacing))
        AppTextField(
          value = state.password,
          onValueChange = viewModel::onPasswordChanged,
          label = stringResource(R.string.login_password_label),
          placeholder = stringResource(R.string.login_password_placeholder),
          errorText = state.passwordError?.let { stringResource(it) },
          enabled = !state.isSubmitting,
          visualTransformation = if (passwordVisible) {
            VisualTransformation.None
          } else {
            PasswordVisualTransformation()
          },
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
          keyboardActions = KeyboardActions(onDone = { viewModel.onLoginClicked() }),
          trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
              Icon(
                imageVector = if (passwordVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                contentDescription = stringResource(
                  if (passwordVisible) {
                    R.string.login_password_hide_content_description
                  } else {
                    R.string.login_password_show_content_description
                  },
                ),
                tint = NeutralG400,
              )
            }
          },
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
          text = stringResource(R.string.login_button),
          onClick = viewModel::onLoginClicked,
          loading = state.isSubmitting,
        )
        // Banner sits a fixed gap below the button, in the normal content flow, per Figma.
        // It must NOT be aligned to the screen's bottom edge (e.g. Box + Alignment.BottomCenter):
        // that anchors it to the physical bottom of the device rather than to the button, so on
        // screens taller than the Figma frame it visually drifts away from the button toward the
        // bottom of the screen.
        val bannerModifier = Modifier.fillMaxWidth()
        when {
          state.loginError != null -> {
            Spacer(Modifier.height(96.dp))
            StatusBanner(
              message = stringResource(state.loginError!!),
              variant = StatusBannerVariant.Error,
              modifier = bannerModifier,
            )
          }
          logoutBannerVisible -> {
            Spacer(Modifier.height(96.dp))
            StatusBanner(
              message = stringResource(R.string.login_logout_success),
              variant = StatusBannerVariant.Success,
              modifier = bannerModifier,
            )
          }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}
