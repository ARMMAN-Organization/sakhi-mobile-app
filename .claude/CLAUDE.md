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

## Dependencies
Talks only to the API Gateway over HTTPS (base URL via BuildConfig).

## Deployment
Signed APK/AAB via CI (Play Integrity). Independent release cycle.
