# sakhi-mobile-app

Arogya Sakhi field-worker Android app — Kotlin + Jetpack Compose, offline-first. **Self-contained**
(no shared module dependency). Standards: [`.claude/CLAUDE.md`](./.claude/CLAUDE.md).

## Stack
Kotlin · Jetpack Compose · Navigation Compose · Hilt (DI) · Retrofit/OkHttp.
The design system (theme, colours, components) lives inside this app under
`ui/theme` and `ui/components`.

## Run in Android Studio
1. **File → Open** → select this folder.
2. Wait for **Gradle Sync** (it generates the Gradle wrapper and downloads deps).
3. Pick an emulator or device (Android 10+/API 29+) → click **▶ Run**.

The app launches to the Home screen.

## Structure
```
app/src/main/kotlin/<package>/
  MainActivity.kt        Compose entry + theme
  ui/theme/              colours, typography, theme
  ui/components/         shared UI (PrimaryButton)
  ui/navigation/         NavHost + routes
  ui/home/               Home screen
  di/                    Hilt modules (network)
```
