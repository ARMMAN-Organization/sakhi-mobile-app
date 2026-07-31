package org.armman.sakhi.ui.childregistration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.armman.sakhi.R
import org.armman.sakhi.data.motherlink.LinkedMother
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.components.AppTextInputField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.RiskModerate
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val FieldShape = RoundedCornerShape(8.dp)
private val DateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

/**
 * Replaces the generic renderer for `mother_beneficiary_id` on the registered-mother path (CR-031).
 *
 * The schema types this field `number`/`required` and the app cannot change that (publishing a
 * schema is CR-022, pending backend), so the override is keyed on `question_code` here — the same
 * self-retiring pattern as `hiddenByDirectPathFallback`. Once the schema gains a real
 * `beneficiary_ref` input type this whole file moves behind that type instead and the special-case
 * disappears.
 *
 * The Sakhi picks a **name**; the stored answer is the beneficiary UUID. This is the first field in
 * the app where the displayed value differs from the submitted one, which is exactly why it cannot
 * be a plain `select`: `select` options come from a static lookup category, whereas mothers are live,
 * per-Sakhi data from a different service.
 */
@Composable
fun MotherLinkField(
  label: String,
  /** Resolved display name of the linked mother, or null when nothing is linked yet. */
  selectedLabel: String?,
  mothers: List<LinkedMother>,
  isLoading: Boolean,
  /** True when nothing could be fetched and nothing was cached — the Sakhi must come online once. */
  loadFailed: Boolean,
  onSelect: (LinkedMother) -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var sheetOpen by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG400,
      modifier = Modifier.padding(bottom = 4.dp),
    )
    Box(
      contentAlignment = Alignment.CenterStart,
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.SmallButtonHeight)
        .background(White, FieldShape)
        .border(1.dp, NeutralG75, FieldShape)
        .clickable { sheetOpen = true }
        .padding(horizontal = Dimens.ItemSpacing),
    ) {
      Text(
        // Never the UUID: an id the Sakhi cannot verify is worse than a prompt.
        text = selectedLabel ?: stringResource(R.string.child_reg_mother_link_placeholder),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selectedLabel == null) NeutralG100 else NeutralG400,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }

  if (sheetOpen) {
    MotherPickerDialog(
      mothers = mothers,
      isLoading = isLoading,
      loadFailed = loadFailed,
      onSelect = {
        onSelect(it)
        sheetOpen = false
      },
      onRetry = onRetry,
      onDismiss = { sheetOpen = false },
    )
  }
}

@Composable
private fun MotherPickerDialog(
  mothers: List<LinkedMother>,
  isLoading: Boolean,
  loadFailed: Boolean,
  onSelect: (LinkedMother) -> Unit,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  // Client-side substring filter. The backend's `name=` param is an exact HMAC-hash match, so it
  // cannot do this — see BeneficiaryApi.
  val filtered = remember(mothers, query) {
    if (query.isBlank()) {
      mothers
    } else {
      mothers.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
    }
  }

  // A Dialog gets its own window, and that window keeps decor-fits-system-windows ON even after
  // MainActivity turns it off for the Activity window — so IME insets stop at the dialog boundary
  // and the search field below would sit under the keyboard. This dialog is the only one in the app
  // with a text input, hence the only one that needs the override; the confirmation/language
  // dialogs are deliberately left alone. Guarded by WindowInsetsConfigTest.
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(decorFitsSystemWindows = false),
  ) {
    Surface(
      shape = RoundedCornerShape(Dimens.CardRadius),
      color = White,
      // imePadding on the Surface (not on the Column inside it): the dialog window centres this
      // card, so padding applied OUTSIDE the card lifts it clear of the keyboard, whereas padding
      // inside would just make the card taller. safeDrawingPadding is not needed — the dialog
      // window already keeps the card clear of the system bars.
      modifier = Modifier.imePadding(),
    ) {
      Column(
        modifier = Modifier.padding(Dimens.ItemSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
      ) {
        Text(
          text = stringResource(R.string.child_reg_mother_link_title),
          style = MaterialTheme.typography.titleMedium,
          color = NeutralG400,
        )

        if (mothers.isNotEmpty()) {
          AppTextInputField(
            label = stringResource(R.string.child_reg_mother_link_search_label),
            placeholder = stringResource(R.string.child_reg_mother_link_search_label),
            value = query,
            onValueChange = { query = it },
          )
        }

        when {
          isLoading -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(120.dp),
          ) { CircularProgressIndicator(color = Primary) }

          // Offline with a cold cache is a different problem from having no mothers, and needs a
          // different instruction — telling an offline Sakhi she has no beneficiaries would be both
          // wrong and alarming.
          loadFailed -> EmptyState(
            message = stringResource(R.string.child_reg_mother_link_offline),
            actionLabel = stringResource(R.string.child_reg_mother_link_retry),
            onAction = onRetry,
          )

          mothers.isEmpty() -> EmptyState(
            message = stringResource(R.string.child_reg_mother_link_none),
            actionLabel = null,
            onAction = null,
          )

          filtered.isEmpty() -> EmptyState(
            message = stringResource(R.string.child_reg_mother_link_no_match),
            actionLabel = null,
            onAction = null,
          )

          else -> LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
          ) {
            items(filtered, key = LinkedMother::id) { mother ->
              MotherRow(mother = mother, onClick = { onSelect(mother) })
            }
          }
        }
      }
    }
  }
}

/**
 * One selectable mother. Shows name, age, and registration date together because names collide in
 * the real data (the live sample already has "test test test" twice) — name alone is not enough for
 * the Sakhi to tell two beneficiaries apart.
 */
@Composable
private fun MotherRow(mother: LinkedMother, onClick: () -> Unit) {
  // Resolved before the layout: `stringResource` is a composable call and cannot be made from
  // inside a plain lambda such as `ifBlank { … }`.
  val unnamed = stringResource(R.string.child_reg_mother_link_unnamed)
  val ageLabel = mother.dateOfBirth
    ?.let { ChronoUnit.YEARS.between(it, LocalDate.now()) }
    ?.takeIf { it >= 0 }
    ?.let { stringResource(R.string.child_reg_mother_link_age_years, it.toInt()) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = Dimens.SmallSpacing),
    verticalArrangement = Arrangement.spacedBy(Dimens.LabelValueGap),
  ) {
    Text(
      text = mother.fullName.ifBlank { unnamed },
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      color = NeutralG400,
    )
    Text(
      text = subtitleOf(mother, ageLabel),
      style = MaterialTheme.typography.bodySmall,
      color = NeutralG200,
    )
    if (mother.deliveryNotRecorded) {
      // Per decision D1 these mothers stay selectable — every mother in the current environment is
      // still in the ANC phase, so filtering them out would empty the picker. The badge sets the
      // expectation that delivery answers won't prefill.
      Text(
        text = stringResource(R.string.child_reg_mother_link_no_delivery),
        style = MaterialTheme.typography.labelSmall,
        color = RiskModerate,
      )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NeutralG50))
  }
}

/** Plain (non-composable) so the already-resolved [ageLabel] is all it needs. A mother with neither
 * a usable DOB nor a registration date yields an empty line rather than a stray separator. */
private fun subtitleOf(mother: LinkedMother, ageLabel: String?): String =
  listOfNotNull(ageLabel, mother.registrationDate?.format(DateFormat)).joinToString(" · ")

@Composable
private fun EmptyState(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.ItemSpacing),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
    )
    if (actionLabel != null && onAction != null) {
      SecondaryButton(text = actionLabel, onClick = onAction)
    }
  }
}

/** Hint shown under a field whose value came from the linked mother's record, so the Sakhi knows it
 * was not typed by her and can be corrected. Cleared for that field the moment she edits it. */
@Composable
fun MotherPrefillHint(modifier: Modifier = Modifier) {
  Row(modifier = modifier) {
    Text(
      text = stringResource(R.string.child_reg_mother_prefill_hint),
      style = MaterialTheme.typography.labelSmall,
      color = Primary,
    )
  }
}
