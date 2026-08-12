import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.kapt)
  alias(libs.plugins.hilt)
}

// Per-developer API base URL override (e.g. a teammate's ngrok tunnel while auth-service has no
// stable dev/staging host yet). Set API_BASE_URL in local.properties (gitignored, never
// committed); falls back to the placeholder prod host for CI/release builds where it's absent.
val localProperties = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}
val apiBaseUrl: String = localProperties.getProperty("API_BASE_URL")
  ?: "https://api.arogyasakhi.armman.org/api/v1/"

android {
  namespace = "org.armman.sakhi"
  compileSdk = 34

  defaultConfig {
    applicationId = "org.armman.sakhi"
    minSdk = 29
    targetSdk = 34
    versionCode = 1
    versionName = "0.0.1"
    buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
  }

  signingConfigs {
    // Team-share builds: signed with the local debug keystore so the release APK is
    // installable. Production/Play releases use a proper keystore via CI (see .claude/CLAUDE.md).
    create("teamShare") {
      storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("teamShare")
    }
  }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  testOptions {
    unitTests {
      // android.util.Log is a stub in JVM unit tests: every call throws
      // "Method d in android.util.Log not mocked" unless stubs return defaults. Repository and
      // sync-executor code logs on its normal paths, so without this the tests exercising those
      // paths fail on the logging rather than on anything they assert.
      isReturnDefaultValues = true
    }
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  // Per-app locales (Choose Language) need AppCompat below API 33.
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigation.compose)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.material3)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)

  implementation(libs.hilt.android)
  kapt(libs.hilt.compiler)
  implementation(libs.hilt.navigation.compose)

  implementation(libs.retrofit)
  implementation(libs.retrofit.gson)
  implementation(libs.okhttp.logging)
  implementation(libs.coroutines.android)
  implementation(libs.androidx.security.crypto)

  // GoRules local rule evaluation (CR-032 Milestone 3) — runs published ANC/PP/NN/INC/CCV/HR
  // decision graphs fully on-device. Exact API surface unconfirmed until first Gradle sync;
  // see data/rules/ZenRuleEvaluator.kt for the isolation seam if method names differ.
  // implementation(libs.gorules.zen.engine) // TEMP disabled — isolating kapt metadata-2.1.0 crash

  // Local persistence for the enrollment offline queue (drafts + sync status).
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  kapt(libs.room.compiler)

  // Background sync for queued enrollments (connectivity-restored + periodic retry).
  implementation(libs.work.runtime.ktx)
  implementation(libs.hilt.work)
  kapt(libs.androidx.hilt.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.coroutines.test)
  // Test-only: lets AuthInterceptorTest assert on real request headers without hand-faking
  // OkHttp's Interceptor.Chain/Call interfaces (heavier and less trustworthy than a real client
  // hitting a local mock server).
  testImplementation(libs.okhttp.mockwebserver)
}
