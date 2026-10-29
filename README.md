# Chinlearn

Native Android vocabulary practice in Kotlin, built on the supplied Android Studio project. The interface and starter translations are in Russian, following the quiz sample. No account or paid API key is required.

## Use

1. Open **Слова** and select the words you want to practise. Sixteen beginner cards are included.
2. Open **Учить → Продолжить обучение** for recognition, recall, tones and listening.
3. Open **Написание** to draw with a finger/stylus or write on paper. Reveal the answer and assess yourself. This is not handwriting recognition or stroke-order validation.
4. If listening is unavailable, open **Настройки → Настройки голоса Android** and download a Chinese voice. You can choose Hanping instead, or temporarily turn off listening questions.
5. Use **Прогресс** to inspect difficulties. Finishing a cycle unlocks the due-review and new-cycle controls for that mode.

## Exercises

Nine knowledge directions: character → Russian; character → pinyin; Russian → pinyin; pinyin → Russian; Russian → character; pinyin → character; character → tones; audio → Russian; audio → character. Writing is tracked separately.

Pinyin accepts tone marks or digits, including ü/v/u:. Every non-neutral syllable needs its correct tone. Neutral tones accept an unmarked syllable or 5. Dictionary tones are used; spoken tone sandhi is explained on relevant cards. Meaning checks accept listed synonyms, ignore case/extra whitespace/е versus ё, and offer a user-confirmed synonym override.

Each word/direction tracks attempts, errors, consecutive correct answers and mastery. A mistake is scheduled after 4–7 other answers; previously mastered questions can fill the gap. With a tiny deck and no other task available the gap is shorter. After an error, two consecutive successful recalls are required. Directions with ambiguous pronunciation or meaning are excluded from affected knowledge prompts and totals. A new cycle retains historical statistics. Mastered skills receive review dates at intervals of 1, 3, 7, 15, then 30 days.

## Your own vocabulary

### Use an external LLM

Open **Набор → Добавить набор** (also available under **Слова → Импорт / экспорт**).

1. Choose **Правила для модели → Скопировать правила**.
2. Paste the rules into any external LLM and append your words and context.
3. Copy its response back to **Вставить ответ модели**, or choose its downloaded JSON file.
4. Check the preview, edit the pack name if needed, and tap **Создать набор**.

The app accepts plain JSON and a response with one JSON code block. Invalid content stays in the editor so you can correct it; per-card errors include the card number. Multiple code blocks, trailing text after plain JSON, invalid tones, and oversized input are rejected. The app does not send data to an LLM itself. Review model-generated meanings and pronunciation before practising.


Use **Набор → Добавить набор → Или выбрать файл** on the learning or words screen. Each file becomes a new named pack with independent word selection and progress, even if another pack contains the same words. Your existing vocabulary and results are retained in **Мои слова**. Select one or more packs with **Наборы для практики** on either **Учить** or **Слова**. **Управление набором** lets you choose a pack to rename or delete; the last pack cannot be deleted. **＋ Добавить** adds a word to the current pack.

Save a UTF-8 text file with a `.json` extension. Minimal example:

```json
{
  "name": "My first pack",
  "words": [
    {"hanzi": "水", "pinyin": "shui3", "meanings": ["вода"]},
    {"hanzi": "你好", "pinyin": "ni3 hao3", "meanings": ["привет", "здравствуйте"]}
  ]
}
```

Use a tone digit after each syllable (neutral tone: 5). Supply your own meanings; the app does not automatically translate words. If you prefer tone marks in the displayed pinyin, supply both `"pinyin": "shuǐ"` and `"numbered": "shui3"`.

Optional fields: `id`, `hint`, `alternatives` (accepted character or radical forms, for example `["氵"]` for a 水 radical card), and `distractors` (at least three distinct incorrect character choices). Without distractors the app uses other words in the pack and a small built-in fallback pool. Supply your own distractors for more carefully matched exercises. Alternate forms are shown in character prompts and answer review, and one accepted form is randomly used in character-choice questions. They are never used as wrong choices. Configure them under **Слова → Изменить → Другие формы слова / радикала**. Equivalence is set per card, not applied globally to ordinary words.

Older JSON arrays and files with `version` and `words` also work. A name can be entered before import. The whole file is validated before creating the pack. Importing the same file again creates another independent pack; it does not overwrite results. Limits: 2 MB and 2,000 words per file. Long lists provide search. Export saves the current pack, including its name.

JSON export contains vocabulary and accepted synonyms, not study results. Progress and unfinished questions persist locally, including typed answers, tone selections, feedback and drawing strokes. Android's standard backup rules also apply. Uninstalling the app can remove local data.

## Optional account sync

Open **Настройки → Аккаунт и синхронизация** to create an email/password account or sign in. Registration automatically sends a Firebase verification link in Russian. Follow the link and return to the app; verification is refreshed on return or with **Я подтвердил почту**. Failed email delivery leaves the created account usable locally, with a resend button and a one-minute resend cooldown. Existing unverified accounts must also verify before syncing. Use the same account on each device.

Sync is **off by default**, including for existing signed-in installations upgrading to this version. After verification, choose **Включить синхронизацию** and enter the separate access password supplied by the app owner. It is distinct from the Firebase account password. Opt-in persists locally until disabled or the account changes; enabling again requires the password. The opt-in preference is excluded from Android cloud backup and device transfer, and is never in a sync snapshot. Disabling closes listeners and cancels scheduled retries; a server request already committed cannot be undone. Local study remains available without an account, verification, or sync.

`SyncAccess.kt` reads a SHA-256 verifier from a generated build constant. The verifier is supplied through ignored local configuration and is not published in this repository. It is a local feature gate, not a server authorization secret against a modified app. Firestore authorization separately requires the account owner and a verified email token. The first account used on a device receives its existing local library. Additional accounts have separate local libraries; signing out retains each account's pending changes for its next sign-in. The original guest library remains available when signed out.

Packs, vocabulary, accepted synonyms, selections, knowledge and writing progress, and review dates sync. Voice settings, navigation and unfinished answers/drawings remain local. Practice works without a connection. Changes sync after a short delay while the app is running, when it returns to the foreground, or with **Синхронизировать сейчас**, only while sync is enabled and email is verified. Failed syncs preserve local data and retry with backoff; Android need not keep a stopped app running.

Sync stores compressed current state, not an ever-growing answer history. Independent pack changes merge. When both devices change the same pack since their last shared state, both versions survive: the cloud version keeps its identity and the other becomes **копия конфликта**. Progress is not automatically added together across those versions. A deletion conflicting with an edit preserves the edited version for review. If a device edits data during upload, those new changes are retained for the next sync. Server transactions prevent partial or conflicting snapshot uploads. Successful sync and its local baseline are saved together.

Storage is bounded to 8 MB compressed / 32 MB expanded per account, split into documents below Firestore's document limit. Exceeding this limit reports an error and retains local data. Snapshots overwrite prior chunks and remove surplus chunks; reading/uploading a changed library transfers the current snapshot, so very large libraries or many users may reach Firebase's bandwidth or operation quotas. There is no permanent activity log or paid Cloud Functions dependency.

### Firebase project setup

The Android application ID is `com.example.chinlern`. Place its Firebase configuration at `app/google-services.json`. Firebase client configuration identifiers are not administrative credentials, but this repository deliberately excludes the owner’s configuration. Never put a service-account private key in the Android app.

In Firebase Console:

1. Enable **Authentication → Sign-in method → Email/Password**.
2. Create the default **Cloud Firestore Standard edition** database, starting in Production mode. The free Spark plan is sufficient for initial use.
3. Open **Firestore → Rules**, replace the rules with the complete contents of `firestore.rules`, and **Publish**. These rules allow a signed-in user with a verified email to access only their own sync documents, validate document shape and size, and deny other paths.

With an authenticated Firebase CLI, deploy rules and the binary-payload index exemption with:

```sh
firebase deploy --only firestore --project YOUR_FIREBASE_PROJECT_ID
```

The files `firebase.json`, `firestore.rules`, and `firestore.indexes.json` are included. `.firebaserc` stays local. Rules are not deployed by building the Android app. Creating an account alone does not bypass missing Firestore rules. The app reports missing permissions and disabled sign-in providers in Settings.

The Firebase SDK initializes from `google-services.json`. Auth and Firestore use main Firebase modules, with BoM 34.16.0 for compatibility with AGP's bundled Kotlin compiler; BoM 35's Auth module requires newer Kotlin metadata. No Analytics SDK is added.

### Sync verification with local emulators

Start the Firebase Auth and Firestore emulators using a disposable demo project; this never uses the production database:

```sh
firebase emulators:start --only auth,firestore --project demo-chinlearn
python3 tools/test_firebase_rules.py
```

`SyncSnapshotTest` covers merges, deletions, repeat uploads, compression, and edits during an upload. `SyncStoreTest` covers account isolation, migration, restoring progress, drafts, and rejecting malformed state. `FirebaseSyncTest` exercises two clients signing into the same emulator account, downloading a library, preserving concurrent offline progress, and repeating sync without duplicate copies or attempts. Run the latter only on an Android emulator with the Firebase emulators running on the host:

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.example.chinlern.FirebaseSyncTest -e firebaseEmulators true \
  com.example.chinlern.test/androidx.test.runner.AndroidJUnitRunner
```

If the emulator cannot reach the host through `10.0.2.2`, forward its ports with `adb -s emulator-5554 reverse tcp:9099 tcp:9099` and `adb -s emulator-5554 reverse tcp:8080 tcp:8080`, then add `-e firebaseHost 127.0.0.1` to the instrumentation command.

The Firebase integration test is skipped without that explicit argument. Debug builds permit HTTP for local emulator endpoints; release builds retain Android's default HTTPS requirement.

## Audio and dictionaries

By default, the word is pronounced once after every submitted answer, correct or incorrect, including “I don’t know” and writing self-assessment. It uses your selected voice. Turn this off with **Настройки → Произносить слово после ответа**. The setting is saved independently of listening questions; reopening or rotating an already-answered question does not replay it.

The default uses Android `TextToSpeech` with a Mainland Chinese voice, preferring an installed offline voice. Voice availability and audio quality depend on the device's TTS engine. Download voices through Android settings. Some engines can use the network. Normal and slower playback are available.

Optional Hanping playback uses its external `com.hanpingchinese.cmn.intent.action.PLAY` intent with the word and numbered pinyin. Hanping must be installed; voice packs may require purchases. Hanping 10.5 requires both written-form fields to be non-empty. Chinlearn sends the supplied characters in both when no separate traditional form is available. This corrects the silent rejection seen on the connected phone with Hanping 10.5.2_1; its playback logs confirmed recorded audio starts for 水 and 中国. If a different device still stays silent, select the device voice in Settings or check **Разрешения Hanping**. This integration does not extract audio files. Pleco and Hanping lookup links are available after an answer.

Documentation: [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [Hanping external playback example](https://github.com/ankidroid/Anki-Android/issues/16405), [Hanping support](https://hanpingchinese.com/faq/), [Pleco URL lookup](https://android.pleco.com/manual/310/vershist.html).

## Layout

Phone: bottom navigation, one question at a time, large touch targets and scrolling around the keyboard. Wider windows (720 dp and up): persistent side navigation and a two-column practice overview. No orientation lock. Writing practice provides one square box per character, wrapping into rows to fit the screen. Each box keeps its own drawing across resizing and reopening. Previous single-box drawings are preserved in the first box. When checking an answer, any listed alternative form is acceptable. Drawing points are stored proportionally and survive resizing.

## Development

Open this folder in Android Studio. The supplied Gradle 9.6 / Android Gradle Plugin 9.4 configuration and API 37 target are preserved. Gradle's existing toolchain configuration requests Java 25; Android Studio's bundled JDK works.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

`LearningEngine.kt` is platform-independent scheduling and answer validation. `StudyStore.kt` owns local state and vocabulary import. `MainActivity.kt` contains native Material views. `Pronunciation.kt` owns TTS and dictionary intents. `WritingPad.kt` is the touch/stylus canvas.

`CloudSync.kt` owns Firebase snapshot transactions and retries. `SyncSnapshot.kt` owns deterministic merging and bounded compression. Local profiles and sync baselines are persisted by `StudyStore.kt`.

Instrumentation tests should run on an emulator/test device: they reset this app's preferences for isolation. They do not modify other apps.

### Password entry and Hanping pinyin

Login and registration passwords are masked by default; the eye icon reveals or hides the characters without hiding the input field. Choose Create account to show the confirmation field; registration requires both entries to match.

Hanping playback converts numbered pinyin ü/u: to v (for example, nü3 → nv3) for its recording interface. Displayed pinyin and answer validation are unchanged.

### Combining packs and browsing words

On **Учить**, open **Наборы для практики**, tick one or more packs, and apply. Practice combines the checked words in those packs while keeping each pack’s progress separate, including overlapping words. Pack selection is saved on this device; vocabulary and progress continue to sync. The same multiple-choice picker is available on **Слова**. It shows words from all selected packs, labels each card with its pack name, and searches across those packs (including pack names). **Выбрать все** and **Снять выбор** affect all words in the selected packs. Editing a word preserves its original pack; adding, exporting, renaming, or deleting asks which selected pack to use. Overlapping words remain separate cards with independent selection and progress. The **Прогресс** overview still inspects one pack at a time. Each word now has **Словарь** for Pleco/Hanping lookup. **＋ Новый набор** is directly visible on both learning and word-list screens.

Long paired controls stack vertically on phones, with wrapping labels. Hanping requests are spaced using an estimated recording duration, retaining only the latest rapid repeat; leaving the app or advancing to the next question cancels pending playback. This reduces overlapping requests but cannot guarantee Hanping’s own audio service never stalls. **Настройки → Восстановить звук Hanping** explains how to restart its service without clearing its data.

### Recurring Hanping silence on Xiaomi

Request spacing did not eliminate the external Hanping service failure: device logs showed old AudioTracks still started and audio binder calls blocked for up to 67 seconds. This also occurred on the speaker, so Bluetooth alone is not the cause. The system Chinese voice was tested and confirmed audible by the user; it is selected locally on the affected phone. Hanping remains an optional recording provider and dictionary lookup destination. No automatic fallback is claimed: the external PLAY interface does not report audibility to Chinlearn.

The Hanping recovery dialog now offers a direct switch to the system voice with a 你好 test, alongside the temporary force-stop instructions. The sound test always speaks the displayed test word regardless of the current pack. Hardware volume keys control media volume while Chinlearn is open.

### Email verification, sync lock, and launcher icon

The launcher uses red and gold, five stars, a book and a stylized Tiananmen gate. Adaptive, monochrome and legacy launcher resources are included; `app/icon-preview.png` previews the vector artwork.

Regression tests: `LibraryMultiSelectTest`, `MultiPackTest`, `SyncAccessTest`, and emulator-only `VerificationFlowTest` / `FirebaseSyncTest`. Supply `-e firebaseEmulators true -e syncPassword '<access password>'` to exercise verification and successful unlocking on a disposable Android emulator. Never run the preference-resetting instrumentation tests on a personal phone. `PhoneSmokeTest` is a separate opt-in (`-e phoneSmoke true`) read-only library/picker check that restores navigation and verifies unchanged pack data. `tools/test_firebase_rules.py` also verifies that unverified owners are denied access.

Publish the updated `firestore.rules` to enforce verified-email access on the server. Email verification uses Firebase's hosted verification page; no custom email provider or Cloud Function is required.

Validation for this update: debug APK builds; 28 JVM unit tests pass; Android lint reports no errors. Ten focused pack/account/access instrumentation tests, two Firebase sync integration tests, and the full registration/verification/unlock UI test passed on an Android emulator. Seven Firestore rules tests passed against local Firebase emulators. The debug APK was installed over the existing phone app with preferences backed up first; Settings was visually checked to show sync disabled. The optional phone test-helper installation was blocked by the device, so automated phone checks were not run. Production rules publication remains pending Firebase authentication.

### In-app identity and difficulty overview

The header and practice summary now reuse the red-and-gold launcher artwork in `brand_mark.xml`; green remains the primary interface color. The slogan and quotation are replaced with plain Russian labels and a count of selected words.

**Прогресс → Что сложнее запомнить** ranks word/skill pairs with recorded mistakes. Unmastered items come first, then descending `(errors + 1) / (attempts + 2) / (streak + 1)`. Smoothing avoids overreacting to one attempt, and consecutive correct answers reduce priority. Lifetime error counts break score ties, followed by a stable task key. This is an estimate from existing statistics, not a measured recall probability or a change to the exercise scheduler. The top-100 limit is applied after sorting. `RecallDifficultyTest` covers error rates, successful streaks, mastery, small samples, and stable ordering.

Validation for the in-app refinement: 33 JVM unit tests and eight learning-flow instrumentation tests pass; the debug APK builds and lint has no errors. The practice screen was visually checked on the emulator (`app/design-preview.png`), and the updated APK was installed on the connected phone without clearing app data.

### Accidental answers and adaptive tablet layouts

After revealing/scoring an answer, **Ответил случайно — не учитывать** cancels that attempt. The revealed solution stays visible with **Ответ не учтён**. Attempts, errors, consecutive-correct streak, mastery, review interval/date, and queue counters are restored to their pre-answer values. The action is one-use and remains available after rotation or reopening the app. It works for typed answers, choices, tones, and writing self-assessment, including combined packs. A visible answer-type label distinguishes Russian translation, pinyin, tones and characters before submission. Once you advance to another question, the previous attempt can no longer be cancelled. An already-open result from a version without rollback metadata cannot be cancelled.

The rollback record is device-local and is cleared when the question or affected cloud data changes. Correcting a synonym uses the same rollback before counting a single correct answer. Cancelled progress is part of the next normal sync, if sync is enabled.

Warm ivory surfaces, dark-green reading areas, muted red primary actions, and gold dividers/progress tie the interface to the launcher icon. Navigation adapts to the current window: bottom tabs on phones, a compact sidebar from 600 dp, and two-column layouts only when the remaining content width is sufficient. Wide layouts split the practice overview, question/response, and settings into columns; word and difficulty cards use a row-major grid. Portrait, split-screen, and larger fonts fall back to stacked layouts. Writing grids still reflow without losing strokes.

Validation for this update: 33 unit tests pass and Android lint reports no errors. Focused instrumentation passed at phone size (17 tests), tablet landscape (14 tests), and tablet portrait (17 tests), including rollback after reopening, mixed-pack counters/previous-task state, accidental correct answers, and rotation of the result screen. The updated overview and split exercise layout were visually inspected on the connected tablet without submitting answers. The final APK was installed over its existing app with a local preference backup. Screenshots: `app/tablet-preview.png` and `app/tablet-quiz-preview.png`.


## Full backup and manual transfer

Open **Настройки → Резервная копия → Экспорт всех данных** to save all packs, words, selections, progress and review schedules in one JSON file. On another device choose **Импорт и объединение**. Neither an account nor a sync password is required. This is distinct from vocabulary-only pack export.

Import unions new packs, words and nonconflicting exercise results with the local library. It never deletes local packs or replaces conflicting local results. Conflicting incoming packs are preserved separately as **(из файла)**; attempts are not summed because the same answer may exist on both devices. Importing the same unchanged file again does not duplicate counts or conflict copies. Imports are validated before changing data and limited to 32 MB. Account credentials, sync unlock state, device/voice settings and unfinished answers are excluded. The file contains personal learning data; share it only with intended recipients.

Quiz actions stay outside the scrolling question area, above the keyboard. Text fields use neutral borders and green focus; red indicates a validation error.

## Building your own copy

Use Android Studio with Android SDK 37 and the checked-in Gradle wrapper. The Gradle daemon configuration requests JDK 25.

1. Create your own Firebase Android app for `com.example.chinlern` and download its `google-services.json` into `app/`. This file is ignored by Git. Enable Email/Password authentication and deploy the rules described above.
2. For a local sync unlock, run `python3 tools/configure_sync.py`. It asks for your chosen password without echoing it and saves only its SHA-256 verifier in ignored `sync-access.properties`. An unset verifier leaves unlocking disabled; local study and file backups work normally. CI may supply `CHINLEARN_SYNC_PASSWORD_SHA256` instead.
3. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

The public CI uses a clearly fake Firebase configuration for compilation and unit tests; it never connects to the owner's Firebase project. Do not use that configuration for a distributed app.

See [SECURITY.md](SECURITY.md) for the limits of the local unlock and publishing precautions.
