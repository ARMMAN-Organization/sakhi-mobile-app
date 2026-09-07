package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Blocking, full-screen loader shown while a form submission is in flight.
 *
 * Unlike [PrimaryButton]'s inline `loading` spinner — which only disables the button itself — this
 * covers the whole screen with a scrim and swallows all touch input, so the Sakhi can't edit fields,
 * navigate away, or double-submit while a save/sync call is pending. System back is disabled for the
 * same reason (`DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`); there
 * is no `onDismissRequest` path other than the caller flipping [visible] to false once the submission
 * resolves (success or error).
 *
 * Callers keep their existing `PrimaryButton(loading = ...)` wiring for the button's own spinner cue
 * and additionally render this at the screen root, e.g.:
 * ```
 * Box(modifier = Modifier.fillMaxSize()) {
 *   ScreenContent(...)
 *   FullScreenLoadingOverlay(visible = state.isSubmitting)
 * }
 * ```
 */
@Composable
fun FullScreenLoadingOverlay(visible: Boolean) {
  if (!visible) return
  Dialog(
    onDismissRequest = {},
    properties = DialogProperties(
      dismissOnBackPress = false,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      // A Dialog renders in its own Android window, so it already intercepts all touches from
      // reaching the screen behind it — no extra gesture handling needed here.
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.4f)),
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        strokeWidth = 4.dp,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
