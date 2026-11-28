# Chinlern contributor guide

## Scope and roadmap

Before changing vocabulary storage, dictionary lookup, text learning, reading, sync, or backup, read [ROADMAP.md](ROADMAP.md). It records remaining work and design constraints from the direction agreed on 2026-11-24. Follow the user's current scope. When a task completes a roadmap item, remove it from the list and record any user-approved change of direction.

## Code map

The Android app is in `app/`. Paths below are relative to `app/src/main/java/com/example/chinlern/`.

- `MainActivity.kt` handles navigation and screen entry points. `WordCardUi.kt`, `WordDetailsSheet.kt`, `VocabularyLookup.kt`, and `dictionary/` cover shared cards, lookup, and offline dictionaries.
- `LearningEngine.kt` and `Fsrs.kt` handle vocabulary exercises and scheduling. `SharedWords.kt` handles shared learning progress across equivalent cards.
- `StudyStore.kt` is the local state façade. `data/ProfileStorage.kt` provides SQLite-backed profile storage. `LibraryBackup.kt`, `SyncSnapshot.kt`, and `CloudSync.kt` cover backups, snapshot merging, and Firebase sync.
- `texts/` contains the reader, document revisions, annotations, and text recall. `Pronunciation.kt` and `ExternalDictionary.kt` handle audio and external lookup, respectively.

## Design

Follow [DESIGN_STANDARD.md](DESIGN_STANDARD.md) for palette, icon controls, interface reuse, and the app’s use of political symbols and quotations.

Do not add extra labels that explain the UI unless the user explicitly asks for them.

## Compatibility

- Preserve vocabulary, progress, unfinished sessions, pack selections, and profile isolation across updates. Keep migrations restart-safe and preserve backup and sync compatibility when changing persisted data.
- Preserve the distinction between stable card identity and shared learning equivalence. Dictionary lookups must leave user wording, accepted answers, and learning progress intact. Dictionary pack updates follow [dictionary-source/README.md](dictionary-source/README.md).
- Keep reader spans and recall targets tied to the original revision and respect Unicode boundaries. Use Russian for new user-facing Android copy and follow existing resource and UI conventions.

## Build and verification

Use the checked-in Gradle wrapper, a Java 25 Gradle daemon, and the Android SDK components listed in [.github/workflows/android.yml](.github/workflows/android.yml). When a local `app/google-services.json` is absent, the checked-in `app/google-services.example.json` can serve as CI's build-only Firebase configuration. Preserve existing local Firebase configuration. Keep credentials and private device exports out of Git.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

Use the first command for Android build, unit, and lint checks. The second builds the instrumentation APK when device tests are relevant. Run data-resetting tests only on a disposable emulator. Use `adb devices -l` to identify the target, then use `adb -s <emulator-serial>` for installation and targeted instrumentation with `com.example.chinlern.test/com.example.chinlern.SafeTestRunner`. Keep the runner's device guard in place. Use a disposable Firebase emulator project for sync integration tests, following the local emulator setup in [README.md](README.md). Report which checks ran and any that remain unverified.

## Authorized phone testing

The user authorized preserving-data testing on the connected personal phone, debug APK updates without clearing data, private app-data backups under `/tmp/chinlern-phone-check/`, and UI hierarchy exports to that directory, including visible vocabulary and session text. Proceed without asking again for those exports.

Use the phone's explicit ADB serial, verify update compatibility, and install updates in place. Preserve vocabulary and progress. Never clear app data, uninstall the app to force an update, or run data-resetting fixtures on the phone. Keep backups and hierarchy exports private. Screenshot exports require separate authorization. The class-wide `PhoneSmokeTest` run includes screenshot capture, so select individual test methods whose behavior fits the existing authorization.
