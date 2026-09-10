# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`oboetotte` is an Android Todo app developed entirely from a smartphone: code is written by Claude Code, pushed to GitHub, built by GitHub Actions (no local Android Studio / SDK involved), and the resulting APK is installed on-device for testing. Kotlin + Jetpack Compose, single `app` module, no backend — data is stored on-device only (Room is wired up but not yet used for actual persistence).

## Commands

```
./gradlew assembleDebug   # build the debug APK (what CI runs)
./gradlew test            # JVM unit tests
./gradlew lint            # Android lint
```

There is no local Android SDK in most dev sandboxes for this repo, and this specific execution environment cannot reach `dl.google.com` (the Android Maven repo) at all — even Gradle plugin resolution fails here, so `./gradlew` cannot be run to completion locally. Validate changes by reading them carefully and letting GitHub Actions build them; do not assume a local Gradle run is possible.

## CI / build pipeline

`.github/workflows/android-build.yml` runs on every push and PR (any branch) plus manual `workflow_dispatch`. It runs `./gradlew assembleDebug` on `ubuntu-latest` and uploads `app/build/outputs/apk/debug/app-debug.apk` as the `app-debug` artifact. That artifact is the way to get an installable APK onto a phone — download it from the Actions run, transfer it to the device, and install with "unknown sources" enabled.

## Architecture notes

- Package/applicationId: `com.toshi0907.oboetotte`, minSdk 26, target/compileSdk 35.
- Kotlin 2.0.21 + AGP 8.7.3. **Compose requires the separate `org.jetbrains.kotlin.plugin.compose` Gradle plugin** (applied in root `build.gradle.kts` and `app/build.gradle.kts`) — since Kotlin 2.0 the old `composeOptions.kotlinCompilerExtensionVersion` approach no longer works and fails the build with "Compose Compiler Gradle plugin is required". Keep both plugin declarations in sync if bumping the Kotlin version.
- Room + KSP dependencies are declared in `app/build.gradle.kts` (`androidx.room:room-runtime`/`room-ktx`, `ksp` compiler) for the planned on-device persistence layer, but no `Entity`/`Dao`/`Database` classes exist yet — `MainActivity.kt` currently just renders a placeholder Compose screen (`SetupCompleteScreen`).
- Theming lives in `app/src/main/java/com/toshi0907/oboetotte/ui/theme/` (`Color.kt`, `Theme.kt`, `Type.kt`) following the standard Compose Material3 template split.
- App icon is defined purely as adaptive-icon XML (`res/mipmap-anydpi-v26`, `res/drawable/ic_launcher_*`) with no raster fallback, since minSdk 26 already supports adaptive icons.
