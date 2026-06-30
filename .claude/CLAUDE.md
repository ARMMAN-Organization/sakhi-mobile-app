# sakhi-mobile-app — instructions

Arogya Sakhi field-worker app. Kotlin + Jetpack Compose, offline-first, self-contained.

## Purpose
Lets community health workers enrol beneficiaries, run visit forms, classify risk, and work fully offline, syncing when connectivity returns.

## What belongs here
All app code: screens, navigation, view models, DI, theme and components.

## What must NEVER be added
Secrets or hardcoded keys → use the Android Keystore / BuildConfig + CI secrets.

## Standards
- Kotlin official style; detekt clean; small composables; files ≤ ~250 lines.
- UI in Compose; state in ViewModels (Hilt); coroutines/Flow for async.
- Offline-first: encrypt any local database (SQLCipher) with a Keystore-backed key.
- Handle loading/error/empty/success in every screen. Never log PII or tokens.
- All user-facing text in string resources (English + Marathi).

### Code-review checklist (enforce on every change)
- **Nullability:** no `!!`; keep types non-nullable where possible; use `?.`/`?:` deliberately.
- **Exceptions:** handle every one with a sealed UI-state or `Result`; never swallow. Use
  `viewModelScope` — never `GlobalScope`.
- **Screen state:** every screen must render loading / error / empty / success. New screens get a
  ViewModel exposing a `sealed interface …UiState`, collected via `collectAsStateWithLifecycle`.
- **No magic values:** strings → `strings.xml` (+ `values-mr/`); spacing/sizes → `Dimens`
  (`ui/theme/Dimens.kt`); colors → `Color.kt`. No inline `.dp`, literals, or hardcoded text.
- **Localization:** any string added to `values/strings.xml` MUST get a matching `values-mr/` entry
  in the same change.
- **DI / DIP:** depend on interfaces, bind concretes via Hilt (`@Binds`/`@Provides`); no `new`/
  direct construction of repositories or clients in UI/VM code.
- **Networking:** `OkHttpClient` always has connect/read/write timeouts; `HttpLoggingInterceptor`
  is `BODY` only under `BuildConfig.DEBUG`, `NONE` in release (never leak PII/tokens).
- **Tests:** every ViewModel/repository ships with `src/test` unit tests covering happy AND sad
  paths. Use `StandardTestDispatcher` + `Dispatchers.setMain` for coroutine code.
- **Imports:** fully-qualified, no wildcards. Prefer `when` over `if/else` chains. Comment
  intentional empty blocks (`/* no-op */` or `TODO`).
- **Accessibility/responsiveness:** content descriptions on actionable/visual elements; support
  varied screen sizes as UI grows.

Before finishing a change, run `./gradlew detekt :app:testDebugUnitTest` and keep it green.

## Dependencies
Talks only to the API Gateway over HTTPS (base URL via BuildConfig).

## Deployment
Signed APK/AAB via CI (Play Integrity). Independent release cycle.
