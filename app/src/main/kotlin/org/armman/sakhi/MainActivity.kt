package org.armman.sakhi

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.armman.sakhi.ui.navigation.AppNavHost
import org.armman.sakhi.ui.theme.ArogyaTheme

// AppCompatActivity (not ComponentActivity) so per-app locales work below API 33.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Turns off decor-fits-system-windows so the platform dispatches window insets (system bars,
    // display cutout AND the IME) into the Compose hierarchy. Every screen root already applies
    // `safeDrawingPadding()`, whose inset set includes the IME — but until the insets are actually
    // dispatched they all resolve to 0dp, which is why the keyboard covered the focused field on
    // every screen. Must be called before setContent, and paired with
    // android:windowSoftInputMode="adjustResize" in the manifest.
    //
    // Both bar styles are pinned to `light` rather than left as the default `auto`: `auto` follows
    // the *platform* DayNight theme, but ArogyaTheme is a hardcoded `lightColorScheme` and every
    // screen draws on White or BackgroundLavender (#F1EDF9). On a device in system dark mode `auto`
    // would switch to light-on-dark bar icons over our permanently light background, making the
    // status bar illegible. `light` = light background, therefore dark icons.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )

    setContent {
      ArogyaTheme { AppNavHost() }
    }
  }
}
