package org.armman.sakhi

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.armman.sakhi.ui.navigation.AppNavHost
import org.armman.sakhi.ui.theme.ArogyaTheme

// AppCompatActivity (not ComponentActivity) so per-app locales work below API 33.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      ArogyaTheme { AppNavHost() }
    }
  }
}
