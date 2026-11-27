# LEXORA — Project & Release Guidelines (GEMINI.md)

## Project Overview

- **App:** LEXORA for Android (`org.lexora.study`) — offline-first German vocabulary trainer with FSRS-6 spaced repetition, TestDaF academic dictionary, listening/practice modes, and local backup/restore.
- **Repository:** `husseinhasimov-dev/LEXORA`
- **Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM `2024.12.01`), Material 3, Android Gradle Plugin 8.7.3, Gradle 8.9, Java 17.
- **SDK Targets:** `minSdk = 26` (Android 8.0+), `compileSdk = 35`, `targetSdk = 35`.

## Mandatory Workflow After Every Change

After **every** code or resource change in this repository, follow this exact sequence before considering the task done:

1. **Update version (when shipping a new release):**
   - Edit `app/build.gradle.kts` and increment `versionCode` (and update `versionName` as appropriate).
   - If you are only refreshing the current build tag, keep the same `versionName` and `versionCode` (the publisher script updates the existing GitHub Release and replaces its assets).
2. **Build the APK locally & run tests:**
   - Windows:
     ```powershell
     .\gradlew.bat :app:assembleDebug
     .\gradlew.bat :app:testDebugUnitTest
     ```
   - macOS / Linux:
     ```bash
     ./gradlew :app:assembleDebug
     ./gradlew :app:testDebugUnitTest
     ```
   - Default deliverable variant is `debug` (`app/build/outputs/apk/debug/app-debug.apk`) so updates stay signature-compatible with existing LEXORA installations (unless a dedicated private release keystore is configured).
3. **Commit & push source changes to GitHub:**
   ```powershell
   git add <changed-files>
   git commit -m "<clear commit message>"
   git push origin main
   ```
4. **Build locally and publish the GitHub Release:**
   - Run `scripts/publish_release.py` with explicit, change-specific release notes (static or recycled notes are prohibited):
     ```powershell
     python scripts/publish_release.py --lang az -i "İlk dəyişiklik təsviri" -i "İkinci dəyişiklik təsviri"
     ```
   - By default, `scripts/publish_release.py` automatically runs the local Gradle build (`:app:assembleDebug`), copies the APK to `delivery/LEXORA-<versionName>.apk`, creates/updates tag `v<versionName>-b<versionCode>`, and uploads:
     - `LEXORA.apk`
     - `update-manifest.json`
   - If the APK was just built in step 2 and no files changed since, you may pass `--no-build` to reuse it without rebuilding twice:
     ```powershell
     python scripts/publish_release.py --no-build --lang az -i "Dəyişiklik təsviri"
     ```
   - Preview without calling GitHub:
     ```powershell
     python scripts/publish_release.py --dry-run --lang az -i "Test qeydi"
     ```

## Authentication & Security Rules

- **NEVER** hardcode or commit GitHub Personal Access Tokens (PATs), Firebase keys, keystores, `.github-token`, `local.properties`, `cloud.properties`, or built `.apk` files.
- `scripts/publish_release.py` resolves GitHub credentials automatically in this priority order:
  1. `GITHUB_TOKEN` or `GH_TOKEN` environment variable
  2. Gitignored `.github-token` file in the repository root
  3. Git Credential Manager (`git credential fill` for `github.com`)
  4. `~/.git-credentials`
- Never print or log tokens to stdout/stderr or commit logs.

## Local Build Prerequisites

- **JDK 17** (`JAVA_HOME` pointing to JDK 17)
- **Android SDK** with Platform 35 and Build Tools 34.0.0+
- **`local.properties`** (gitignored) in the repository root with `sdk.dir=<path-to-android-sdk>` (if missing, `scripts/publish_release.py` also auto-detects common SDK locations)
- **Python 3.10+** (standard library only — no third-party pip packages required)
- If a synced folder interferes with Gradle outputs, set `LEXORA_BUILD_DIR` to a local temporary path.

## Key Paths

- `app/build.gradle.kts` — Android config, `versionCode`, `versionName`, dependencies
- `app/src/main/java/org/lexora/study/` — Compose UI, ViewModels, SQLite DB, FSRS engine, notifications, transfer
- `app/src/main/assets/catalog.sqlite` — Offline German-English dictionary database
- `scripts/publish_release.py` — Local APK build & GitHub Release publisher
- `delivery/` — Gitignored local folder where freshly built `LEXORA-<versionName>.apk` copies are placed
