package org.armman.sakhi.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.StatusBanner
import org.armman.sakhi.ui.components.StatusBannerVariant
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White

/**
 * Login screen per the "Arogya Sakhi - Revamp" Figma (mobile 375x812):
 * logo, title, Username + Password fields, pill Login button, and a
 * success banner shown when the user arrives here right after logging out.
 */
@Composable
fun LoginScreen(
  onLoginSuccess: () -> Unit,
  showLogoutBanner: Boolean = false,
  viewModel: LoginViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(state.loginSucceeded) {
    if (state.loginSucceeded) {
      viewModel.onLoginHandled()
      onLoginSuccess()
    }
  }

  Surface(color = White, modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .imePadding(),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = Dimens.ScreenPadding),
      ) {
        Spacer(Modifier.height(72.dp))
        Image(
          painter = painterResource(R.drawable.logo_arogya_sakhi),
          contentDescription = stringResource(R.string.login_logo_content_description),
          modifier = Modifier.size(width = 140.dp, height = 156.dp),
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
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
          keyboardActions = KeyboardActions(onDone = { viewModel.onLoginClicked() }),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
          text = stringResource(R.string.login_button),
          onClick = viewModel::onLoginClicked,
          loading = state.isSubmitting,
        )
        // Reserve space so the bottom banner never overlaps the button on small screens.
        Spacer(Modifier.height(96.dp))
      }

      val bannerModifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding)
      when {
        state.loginError != null -> StatusBanner(
          message = stringResource(state.loginError!!),
          variant = StatusBannerVariant.Error,
          modifier = bannerModifier,
        )
        showLogoutBanner -> StatusBanner(
          message = stringResource(R.string.login_logout_success),
          variant = StatusBannerVariant.Success,
          modifier = bannerModifier,
        )
      }
    }
  }
}
