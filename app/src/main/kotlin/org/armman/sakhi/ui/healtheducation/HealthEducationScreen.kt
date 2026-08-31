package org.armman.sakhi.ui.healtheducation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.data.healtheducation.HealthEducationMediaType
import org.armman.sakhi.data.healtheducation.HealthEducationTopic
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White

/**
 * CR-M3-06 requirement #5 — post-submission Health Education screen. REWRITTEN 2026-08-28 against
 * backend's confirmed "Learn More" contract — see [HealthEducationViewModel]'s class doc.
 */
@Composable
fun HealthEducationScreen(
  onDone: () -> Unit,
  viewModel: HealthEducationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(title = "Health Education", subtitle = "", onBack = onDone)
      Surface(color = White, modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
          state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
          state.cards.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No health education messages for this visit.", color = NeutralG400)
          }
          else -> Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(Dimens.ScreenPadding),
          ) {
            state.cards.forEach { card -> HealthEducationTopicCard(card.topic) }
          }
        }
      }
      PrimaryButton(
        text = "Continue",
        onClick = onDone,
        modifier = Modifier.padding(Dimens.ScreenPadding),
      )
    }
  }
}

@Composable
private fun HealthEducationTopicCard(topic: HealthEducationTopic) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(Dimens.CardRadius),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing / 2),
      modifier = Modifier.padding(Dimens.ScreenPadding),
    ) {
      LearnMoreBadge(topicCode = topic.topicCode)
      Text(text = topic.topicName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      // Media (image/audio/video) is optional per requirement #8 — the only topic seeded today
      // (`COMING_SOON`) has a null contentUrl, and the backend team has not confirmed a CDN/size
      // policy yet (see the CR-M3-06 backend-request follow-up), so this always falls back to
      // text-only rather than attempting to render anything from `contentUrl`.
      if (topic.mediaType != HealthEducationMediaType.QNA_TEXT && topic.contentUrl == null) {
        Text(
          text = "Media for this topic isn't available yet — showing text only.",
          style = MaterialTheme.typography.labelSmall,
          color = NeutralG400,
        )
      }
    }
  }
}

@Composable
private fun LearnMoreBadge(topicCode: String) {
  val label = if (topicCode == org.armman.sakhi.data.healtheducation.HealthEducationDefaults.COMING_SOON_TOPIC_CODE) {
    "Learn More — coming soon"
  } else {
    "Learn More"
  }
  Surface(color = White, shape = RoundedCornerShape(50)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ItemSpacing / 4),
    )
  }
}
