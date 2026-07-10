package org.armman.sakhi.ui.profile

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.BuildConfig
import org.armman.sakhi.R
import org.armman.sakhi.data.profile.SakhiProfile
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.MenuRow
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sakhi profile per Figma p42 (mobile) / p43 (tablet): profile card with
 * Choose Language, menu list, Logout and version caption.
 */
@Composable
fun ProfileScreen(
  onBack: () -> Unit,
  onLoggedOut: () -> Unit,
  viewModel: ProfileViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  // One-shot: apply the chosen locale app-wide (persisted by autoStoreLocales).
  LaunchedEffect(state.applyLanguageTag) {
    state.applyLanguageTag?.let { tag ->
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
      viewModel.onLanguageApplied()
    }
  }
  // One-shot: session cleared -> navigate to login with the logout banner.
  LaunchedEffect(state.loggedOut) {
    if (state.loggedOut) {
      viewModel.onLogoutHandled()
      onLoggedOut()
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.profile_back_title),
        subtitle = today,
        onBack = onBack,
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
          ) { CircularProgressIndicator() }
          state.hasError -> ProfileError(onRetry = viewModel::loadProfile)
          else -> state.profile?.let { profile ->
            ProfileContent(
              profile = profile,
              isTablet = isTablet,
              onChooseLanguage = viewModel::onChooseLanguage,
              onLogout = viewModel::onLogout,
            )
          }
        }
      }
    }
  }

  if (state.showLanguageDialog) {
    LanguageDialog(
      onSelected = viewModel::onLanguageSelected,
      onDismiss = viewModel::onDismissLanguageDialog,
    )
  }
}

@Composable
private fun ProfileContent(
  profile: SakhiProfile,
  isTablet: Boolean,
  onChooseLanguage: () -> Unit,
  onLogout: () -> Unit,
) {
  // Logout + version are pinned to the screen bottom (per design); the card
  // and menu scroll above them on short screens.
  Column(modifier = Modifier.fillMaxSize().padding(Dimens.ItemSpacing)) {
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState()),
    ) {
      ProfileCard(profile, isTablet, onChooseLanguage)
      Column(modifier = Modifier.padding(top = Dimens.ScreenPadding)) {
        MenuRow(
          icon = painterResource(R.drawable.ic_book_open_text),
          title = stringResource(R.string.profile_learn_more),
          subtitle = stringResource(R.string.profile_learn_more_sub),
          onClick = { /* no-op: knowledge base not built yet */ },
        )
        MenuRow(
          icon = painterResource(R.drawable.ic_database),
          title = stringResource(R.string.profile_restore_data),
          subtitle = stringResource(R.string.profile_restore_data_sub),
          onClick = { /* no-op: data restore not built yet */ },
        )
        MenuRow(
          icon = painterResource(R.drawable.ic_file_text),
          title = stringResource(R.string.profile_update_forms),
          subtitle = stringResource(R.string.profile_update_forms_sub),
          onClick = { /* no-op: form updates not built yet */ },
        )
        MenuRow(
          icon = painterResource(R.drawable.ic_restore),
          title = stringResource(R.string.profile_check_app_updates),
          subtitle = stringResource(R.string.profile_check_app_updates_sub),
          onClick = { /* no-op: app updates not built yet */ },
        )
      }
    }
    PrimaryButton(
      text = stringResource(R.string.profile_logout),
      onClick = onLogout,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    Text(
      text = stringResource(R.string.profile_version, BuildConfig.VERSION_NAME),
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .padding(top = Dimens.ItemSpacing),
    )
  }
}

/** Profile card: avatar + name + ID + Choose Language; fields per form factor. */
@Composable
private fun ProfileCard(
  profile: SakhiProfile,
  isTablet: Boolean,
  onChooseLanguage: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(White)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(Dimens.AvatarSize)
          .background(NeutralG50, CircleShape),
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_woman),
          contentDescription = null,
          tint = NeutralG400,
          modifier = Modifier.size(24.dp),
        )
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = Dimens.ItemSpacing),
      ) {
        Text(
          text = profile.name,
          // Tablet design uses the serif face for the name; mobile uses Cabin.
          style = if (isTablet) SerifTitleLarge else MaterialTheme.typography.titleLarge,
          color = NeutralG400,
        )
        if (!isTablet) {
          Text(
            text = stringResource(R.string.profile_id, profile.sakhiId),
            style = MaterialTheme.typography.bodyMedium,
            color = NeutralG200,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
      SecondaryButton(
        text = stringResource(R.string.profile_choose_language),
        onClick = onChooseLanguage,
        height = Dimens.SmallButtonHeight,
      )
    }
    if (isTablet) {
      TabletFields(profile)
    } else {
      MobileFields(profile)
    }
  }
}

@Composable
private fun MobileFields(profile: SakhiProfile) {
  Column(modifier = Modifier.padding(top = Dimens.ItemSpacing)) {
    FieldRow(R.drawable.ic_briefcase, profile.projectName)
    FieldRow(R.drawable.ic_credit_card, profile.cardNumber)
    FieldRow(R.drawable.ic_phone, profile.mobileNumber)
    FieldRow(R.drawable.ic_bank, profile.maskedBankAccount)
  }
}

@Composable
private fun FieldRow(icon: Int, value: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(top = Dimens.ItemSpacing),
  ) {
    Icon(
      painter = painterResource(icon),
      contentDescription = null,
      tint = NeutralG400,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      color = NeutralG400,
      modifier = Modifier.padding(start = Dimens.SmallSpacing),
    )
  }
}

/** Tablet: labeled 3-column grid (Project / Card / Mobile, then Account). */
@Composable
private fun TabletFields(profile: SakhiProfile) {
  Row(modifier = Modifier.fillMaxWidth().padding(top = Dimens.ScreenPadding)) {
    LabeledField(
      icon = R.drawable.ic_briefcase,
      label = stringResource(R.string.profile_label_project),
      value = profile.projectName,
      modifier = Modifier.weight(1f),
    )
    LabeledField(
      icon = R.drawable.ic_credit_card,
      label = stringResource(R.string.profile_label_card),
      value = profile.cardNumber,
      modifier = Modifier.weight(1f),
    )
    LabeledField(
      icon = R.drawable.ic_phone,
      label = stringResource(R.string.profile_label_mobile),
      value = profile.mobileNumber,
      modifier = Modifier.weight(1f),
    )
  }
  LabeledField(
    icon = R.drawable.ic_bank,
    label = stringResource(R.string.profile_label_account),
    value = profile.maskedBankAccount,
    modifier = Modifier.padding(top = Dimens.ItemSpacing),
  )
}

@Composable
private fun LabeledField(
  icon: Int,
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = NeutralG200,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = NeutralG200,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      color = NeutralG400,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun ProfileError(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.profile_error_load),
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
