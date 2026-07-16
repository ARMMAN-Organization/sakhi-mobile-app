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
    versionName = "0.1.0"
    buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
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

  testImplementation(libs.junit)
  testImplementation(libs.coroutines.test)
}
