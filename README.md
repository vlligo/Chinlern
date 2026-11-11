# Chinlearn

Native Android vocabulary practice in Kotlin, built on the supplied Android Studio project. The interface and starter translations are in Russian, following the quiz sample. No account or paid API key is required.

## Use

1. Open **Слова** and select the words you want to practise. Sixteen beginner cards are included.
2. Open **Учить → Продолжить обучение** for recognition, recall, tones and listening.
3. Open **Написание** to draw with a finger/stylus or write on paper. Reveal the answer and assess yourself. This is not handwriting recognition or stroke-order validation.
4. If listening is unavailable, open **Настройки → Настройки голоса Android** and download a Chinese voice. You can choose Hanping instead, or temporarily turn off listening questions.
5. Use **Прогресс** to inspect difficulties. Due reviews appear automatically when you start studying; you do not need to finish a cycle to unlock them.

## Exercises

Nine knowledge directions: character → Russian; character → pinyin; Russian → pinyin; pinyin → Russian; Russian → character; pinyin → character; character → tones; audio → Russian; audio → character. Writing is tracked separately.

Pinyin accepts tone marks or digits, including ü/v/u:. Every non-neutral syllable needs its correct tone. Neutral tones accept an unmarked syllable or 5. Dictionary tones are used; spoken tone sandhi is explained on relevant cards. Meaning checks accept listed synonyms, ignore case/extra whitespace/е versus ё, and offer a user-confirmed synonym override.

Each word/direction has an independent FSRS-6 memory state, alongside cumulative attempts, errors and streaks. Typed answers map to Good or Again. New skills use 1-minute/10-minute learning steps; a forgotten review uses a 10-minute relearning step. Long-term intervals adapt to memory stability, difficulty, actual elapsed days and the chosen retention target. Due learning/relearning comes first, then due reviews, then new skills. Unseen prompts for a word wait ten minutes after another answer on that word to reduce answer leakage. No mastered filler questions are used. Directions with ambiguous pronunciation or meaning remain excluded.

## Your own vocabulary

### Use an external LLM

Open **Набор → Добавить набор** (also available under **Слова → Импорт / экспорт**).

1. Choose **Правила для модели → Скопировать правила**.
2. Paste the rules into any external LLM and append your words and context.
3. Copy its response back to **Вставить ответ модели**, or choose its downloaded JSON file.
4. Check the preview, edit the pack name if needed, and tap **Создать набор**.

The app accepts plain JSON and a response with one JSON code block. Invalid content stays in the editor so you can correct it; per-card errors include the card number. Multiple code blocks, trailing text after plain JSON, invalid tones, and oversized input are rejected. The app does not send data to an LLM itself. Review model-generated meanings and pronunciation before practising.


Use **Набор → Добавить набор → Или выбрать файлы / ZIP** on the learning or words screen. Each file becomes a new named pack with independent word selection. Cards with identical written forms, pronunciation and the complete set of meanings share learning progress across packs. Your existing vocabulary and results are retained in **Мои слова**. Select one or more packs with **Наборы для практики** on either **Учить** or **Слова**. **Управление набором** lets you choose a pack to rename or delete; the last pack cannot be deleted. **＋ Добавить** adds a word to the current pack.

Select several files at once, or a ZIP containing `.json` / `.txt` packs (including subfolders). The batch preview lists each pack and its word count; uncheck any packs you do not want. Invalid packs are reported by filename without blocking valid ones. Files are read in the background directly from Android storage providers, regardless of their reported MIME type. Limits per selection: 100 files/archive entries, 20 MB total expanded data, and 2 MB per entry. Nested archives are not imported. Archive size/count limit failures stop reading that archive; previously validated packs remain available for review.

Leading uppercase Latin initialisms are supported in mixed words, for example `CD pán` / `CD pan2` for CD盘. Chinese syllables still require matching tones.

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

Older JSON arrays and files with `version` and `words` also work. A name can be entered before import. The whole file is validated before creating the pack. Importing the same file again creates another pack with its own selection and context; identical words reuse existing learning progress. Limits: 2 MB and 2,000 words per file. Long lists provide search. Export saves the current pack, including its name.

JSON export contains vocabulary and accepted synonyms, not study results. Progress and unfinished questions persist locally, including typed answers, tone selections, feedback and drawing strokes. Android's standard backup rules also apply. Uninstalling the app can remove local data.

## Mark words you already know

On **Слова**, each word has independent **Узнаю слово** (recognition/knowledge exercises) and **Умею писать** (writing from memory) checkboxes. Marking a category masters its skills without adding attempts or deleting error history. Newly marked skills are scheduled for review in one day; already-mastered skills retain their review date. Unchecking makes that category available to study again. A wrong practice answer can also remove mastery.

Marks apply to every identical occurrence of the word across packs, including unchecked or hidden packs. They persist across restarts and use the existing progress sync and full-library backup. Known words bypass the new-word preview/daily introduction limit. Vocabulary-only JSON export still excludes progress.

## Optional account sync

Open **Настройки → Аккаунт и синхронизация** to create an email/password account or sign in. Registration automatically sends a Firebase verification link in Russian. Follow the link and return to the app; verification is refreshed on return or with **Я подтвердил почту**. Failed email delivery leaves the created account usable locally, with a resend button and a one-minute resend cooldown. Existing unverified accounts must also verify before syncing. Use the same account on each device.

Sync starts off for accounts that have never unlocked it. The separate access password is required only for the first unlock. Access and the on/off preference are stored per account, survive updates/sign-out, and participate in Android backup/device transfer. Turning sync off does not revoke remembered access. Existing successful-sync history migrates automatically. After reinstalling, a verified account can recover access from its existing cloud sync manifest; **Проверить прежний доступ** retries that check after network failures. The app never needs the original shared password to resume a previously unlocked account. Disabling closes listeners and cancels scheduled retries; a server request already committed cannot be undone. Local study remains available without an account, verification, or sync.

`SyncAccess.kt` reads a SHA-256 verifier from a generated build constant. The verifier is supplied through ignored local configuration and is not published in this repository. It is a local feature gate, not a server authorization secret against a modified app. Firestore authorization separately requires the account owner and a verified email token. The first account used on a device receives its existing local library. Additional accounts have separate local libraries; signing out retains each account's pending changes for its next sign-in. The original guest library remains available when signed out.

Packs, vocabulary, accepted synonyms, selected practice packs and word checkboxes, knowledge and writing progress, and review dates sync. Update the app on every device to sync practice pack choices; older snapshots retain the device's existing choices. Voice settings, navigation and unfinished answers/drawings remain local. Practice works without a connection. Changes sync after a short delay while the app is running, when it returns to the foreground, or with **Синхронизировать сейчас**, only while sync is enabled and email is verified. Failed syncs preserve local data and retry with backoff; Android need not keep a stopped app running.

Sync stores compressed current state, not an ever-growing answer history. Concurrent app changes merge within the original pack by word, field, selection and skill; sync no longer creates conflict-copy packs. Independent vocabulary edits and skill results survive together. A simultaneous edit to the exact same scalar field uses the uploading value. A concurrent deletion versus an edit retains the edited item. For simultaneous changes to one skill, attempts/errors/confusions use maxima (snapshots lack unique answer IDs, so summing would double-count shared history); disputed mastery remains unmastered. Retry merges do not inflate counters. Queue delays are rebased to the merged step. Existing conflict copies are left intact because older copies do not record their source pack. If a device edits data during upload, those changes are retained for the next sync. Server transactions prevent partial snapshot uploads. Successful sync and its local baseline are saved together.

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
  com.example.chinlern.test/com.example.chinlern.SafeTestRunner
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

Current version: **2.2** (version code **5**). See [release notes](RELEASE_NOTES.md).

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

Hanping playback expands erhua pinyin (for example, `zher4` → `zhe4 r5`) without changing the displayed pinyin or answers. On the connected phone, Hanping rejected `zher4` for 这儿 because its phonetic count did not match the two characters; `zhe4 r5` selected the `zhe4r5` recording. A separate audio-service stall delayed that recording by about 18 seconds. Listening questions and answer cards now offer **Нет звука? Голос устройства**, which cancels queued requests, saves the system voice preference, and speaks the current word. This is a manual recovery option: Chinlearn cannot detect silent external playback.

Listening tasks respect the selected provider. With device speech selected, erhua listening tasks remain available. With Hanping selected, only erhua syllables with verified complete recordings (`na3 r5` and `zhe4 r5`) are eligible; missing or unverified erhua recordings are skipped. The installed voice logged `erhua variant unavailable: na4r5` and substituted `na4`, so 那儿 cannot be a fair listening prompt with that voice. This conservative recording list is based on the connected phone, not a live Hanping capability query: PLAY exposes no availability callback, and coverage may differ between voice packs. The filter applies to ordinary, mixed-pack, and focused practice without changing saved progress or the chosen voice. Non-erhua tasks retain their existing eligibility rules.

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

Import unions new packs, words and nonconflicting exercise results with the local library without deleting local packs. Conflicting incoming packs are kept as **(из файла)**; identical cards then share the latest learning schedule across those packs. Attempts use maxima rather than sums because the same answer may exist on both devices. Importing the same unchanged file again does not duplicate counts or conflict copies. Imports are validated before changing data and limited to 32 MB. Account credentials, sync unlock state, device/voice settings and unfinished answers are excluded. The file contains personal learning data; share it only with intended recipients.

Quiz actions stay outside the scrolling question area, above the keyboard. Text fields use neutral borders and green focus; red indicates a validation error.

## Building your own copy

Use Android Studio with Android SDK 37 and the checked-in Gradle wrapper. The Gradle daemon configuration requests JDK 25.

1. Create your own Firebase Android app for `com.example.chinlern` and download its `google-services.json` into `app/`. This file is ignored by Git. Enable Email/Password authentication and deploy the rules described above.
2. For a local sync unlock, run `python3 tools/configure_sync.py`. It asks for your chosen password without echoing it and saves only its SHA-256 verifier in ignored `sync-access.properties`. An unset verifier leaves first-time password unlocking disabled; previously unlocked accounts can still resume; local study and file backups work normally. CI may supply `CHINLEARN_SYNC_PASSWORD_SHA256` instead.
3. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

The public CI uses a clearly fake Firebase configuration for compilation and unit tests; it never connects to the owner's Firebase project. Do not use that configuration for a distributed app.

See [SECURITY.md](SECURITY.md) for the limits of the local unlock and publishing precautions.

### Short sessions and first-time learning

**Учить → 10 вопросов / 5 минут** starts a bounded session with a separate answer/correct/error summary. The five-minute limit uses elapsed wall-clock time (including time away from the app); the current answer can be finished and reviewed before advancing to results. Sessions can also be ended early. Brief breaks preserve the session, feedback, and counters; stale sessions refresh as described below. Undo and synonym correction update session totals as well as lifetime progress. Completing a short session never resets memory or due dates. Ten questions is a maximum: a session ends sooner when no eligible questions are due. Changing packs or receiving changed practice data ends the local session.

**Прогресс → Практиковать трудные слова** offers all difficult exercises, tones/pinyin, listening, meaning, or writing. The session uses up to 100 ranked exercises from the inspected pack, including mastered exercises with historical errors. Explicit focused sessions allow an early review of each selected skill once; the actual answer updates its memory state without resetting previous history. The practice pack becomes the inspected pack; checked word selections remain unchanged.

Previously unattempted words appear first as a preview with characters, marked/numbered pinyin, meanings, audio, and a hint. **Настройки → Новых слов в день** selects 0, 3, 5 (default), 10, or 20. Acknowledging a preview counts the word toward the local calendar-day limit across packs. The limit applies to regular and short daily practice; explicitly chosen focused drills can bypass it. Previously attempted words remain available without consuming that limit. Preview history, daily preferences, onboarding, and sessions are local to the profile/device, not included in cloud sync or manual vocabulary/progress backups.

**Хочу заниматься ещё** on the Practice page offers **Учить ещё: 5 новых слов** (or the number remaining) and **Практиковать знакомые слова**. Extra learning admits up to five untouched selected words beyond the daily allowance; familiar practice uses selected words already introduced. Both use the normal question scheduler, including learning/relearning priority, real due times, and spacing after an answer reveals a word. There is no extra-session rotation, equal-turn quota, or forced early question. A session ends after ten answers or when no eligible question is due. An already answered skill can return when its normal learning delay expires. Reopening, stale-session refresh, and **Ещё 10 вопросов** retain these rules, including older extra-learning sessions saved with the former balancing flag. Explicit difficult-word drills retain their early-review behavior.

The Practice overview continues to show the complete selected vocabulary while an extra session is active. Extra previews count as introductions today; regular practice retains the daily allowance, and the daily setting is unchanged. Only selected packs/words participate, and writing results continue in writing mode.

Normal-scheduling validation (2026-11-10): debug and instrumentation builds, Android lint, all 96 JVM tests, and 29 focused API 37 emulator checks pass. Coverage includes learning-step priority, sibling spacing, no forced early questions, mixed packs, reopening/refresh, older saved extra sessions, and the results-screen continuation. Personal-device installs were not changed.

Original extra-study validation: debug and instrumentation builds, all 89 JVM tests, and Android lint pass. All 16 focused Android tests passed in a separate app on the connected phone, covering zero daily allowance, repeated extra sessions, preview and results navigation, early familiar-word practice, writing/selection, mixed-pack origin persistence, and existing session regressions. Practice and result screens were visually inspected. The update was installed over the personal app after a private backup; all six saved preference files were verified byte-for-byte unchanged immediately after installation.

The first-session card checks Chinese playback, explains `shui3`, and starts ten beginner questions across up to three selected words. Users can start without listening exercises. The guide can be reopened in Settings. Pinyin feedback identifies affected syllables and distinguishes spelling from tone errors, including compact input (`ni3hao2`) and tone marks; tone-only questions show each incorrect tone. Existing translation synonym acceptance remains available.

Validation: debug and test APKs build; 45 JVM tests pass; Android lint passes. Twenty focused emulator tests cover the new preview/session flow plus existing learning, accidental-answer undo, and fixed keyboard actions. The new-word preview was visually inspected at phone size.


### Updates and account access

Install updates with `adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`. Keep the same application ID and signing key. If installation fails, stop and investigate; never uninstall or clear data as an automatic fallback. Release and debug builds can use different signing keys. Back up the library through Settings before switching build/signing variants.

The test APK now registers `SafeTestRunner`, which rejects data-resetting instrumentation on physical devices before any tests run. Only the explicit read-only `PhoneSmokeTest` invocation is allowed there.

Validation: build, 45 unit tests, and lint pass; 14 focused emulator tests cover account-specific access persistence, migration from successful sync, library state, and short sessions. An in-place phone update was verified to preserve every existing preference file byte-for-byte.

### Usage examples, comparisons, and pack updates

Cards accept optional `example`, `examplePinyin`, and `exampleTranslation` strings (Chinese sentence, pinyin, and Russian translation). Supply all three or omit all three. Examples appear after answering and can be played with the device's Chinese voice.

Wrong character choices are saved per word and skill, included in backups and sync, and reused as distractors in comparison practice. The Progress screen offers a focused session for these pairs. Ignoring an accidental answer also rolls back its recorded confusion.

Progress includes a library-wide overview of introduced words, learned words, mastered skills, due reviews, and difficulties by skill. A learned word has all applicable skills mastered, including writing and audio. Counts are per pack; repeated vocabulary in separate packs is counted separately.

Import previews warn about likely duplicates within the file and across packs. Choose “Обновить существующий набор…” to review additions and before/after card changes. Updates match the same hanzi to existing local IDs, preserve progress and selection, and retain words omitted from the file. Reusing an ID for a different word is rejected to prevent transferring unrelated progress.

### Pack order and tablet typing

All pack lists use alphabetical ordering, ignoring case, including practice selection, editing, update targets, and library summaries. Sorting retains pack IDs, selection, and progress.

When the tablet keyboard is open during an exercise, a compact copy of the question stays above the scrolling answer area. Listening prompts retain a replay button. The answer field and fixed submit controls remain above the keyboard in portrait and landscape.

### Fresh sessions after a break

An unfinished practice session refreshes on the next local calendar day or after four hours without practice activity. The old question, draft, feedback, and short-session counters are cleared. Ten-question and five-minute sessions restart with their original mode and focus. Recorded answers, mistakes, mastery, vocabulary selection, and introduction history remain; unfinished skills remain scheduled, and due skills are selected automatically. Refreshing never resets memory strength, learning steps or due times. Brief breaks still resume the current question. Progress displays and queue selection no longer populate empty progress records for every candidate skill in large packs.


### Adaptive scheduling and persistence

The scheduler uses the published FSRS-6 21-weight defaults from [py-fsrs](https://github.com/open-spaced-repetition/py-fsrs/blob/main/fsrs/scheduler.py), with its MIT notice bundled in `app/src/main/assets/fsrs-license.txt`. It runs entirely on-device. Default desired retention is 90%; Settings offers 85%, 90%, and 95%. A higher target produces shorter intervals and more reviews. Changes apply to subsequent answers, leaving existing appointments intact. Intervals can grow beyond 30 days, up to 36,500 days. Interval fuzzing is disabled for reproducible scheduling.

Memory states adapt per skill; model weights are not automatically fitted to the individual learner. The four FSRS ratings are implemented in the core; the current objective-answer and writing UI use Again/Good. “Learned” means the skill has graduated to scheduled review, not that it can never be forgotten. Recognition, recall, tones, listening, and writing remain independent. The app does not infer knowledge of one skill from success on another.

Existing progress migrates lazily on the next answer. Old counters and due dates remain unchanged on load. Because old versions did not record review timestamps, initial stability is estimated from the stored interval, difficulty starts at 5, and the last review is estimated from due minus interval where available. No historical answers are invented. New reviews carry unique IDs, timestamps, ratings and elapsed days; the most recent 32 per skill are retained to bound snapshot size. Lifetime statistics remain cumulative. This is a bounded audit history, not a complete dataset for training a personalized model.

Memory state, due date and recent history travel together through local saves, full backups and cloud snapshots. Undo restores the exact pre-answer state, including history and short-session counters; synonym correction replaces the mistaken answer. In concurrent edits to the same skill, sync selects the newest whole scheduler snapshot (timestamp, then revision ID), retaining retry-safe maxima for historical attempt/error counters. It does not splice memory parameters or sum shared answers. Independent skills still merge separately. A concurrent edit from an older app falls back to the conservative legacy merge and migrates at the next review. Update all syncing devices: older builds reject intervals above their previous 30-day cap.


Version 2.2 validation: Gradle debug and release APK builds, debug lint, and Android instrumentation-test source compilation pass. All 84 JVM tests pass, covering reference-equation numerical values, long absences, learning and relearning, retention, sibling spacing, migration, bounded history, backup round-trips and atomic/retry-safe sync. Lint reports 28 warnings and no errors. Android regression tests cover store reload, exact undo, duplicate submission, daily refresh and early session completion; they have been compiled but not run on an emulator. Screen redraws now save only screen state instead of serializing the complete library and its review histories.

### Version 2.2.1 fixes

Previously mastered skills with a correct-answer streak now migrate with a valid review state. Loading also repairs the unused learning step in affected saved review records without changing counters, due dates, or recent history. Restored questions are rechecked against current eligibility even when feedback is visible, clearing unavailable listening prompts after a voice change.

Validation: debug, unsigned release, and instrumentation APK builds pass; all 86 JVM tests pass. Debug lint reports 28 warnings and no errors. All 22 focused Android tests pass on the API 37 emulator, covering review persistence, exact undo, duplicate submissions, learning flows, session refresh, Hanping request cancellation, and clearing restored unavailable listening feedback while preserving progress. The public-file credential check passes. Signing and distribution remain separate steps; see [PUBLISHING.md](PUBLISHING.md).

### Organizing word lists

Open **Слова → Управление наборами и папками** to manage every list, including lists outside your practice selection. The organizer itself is an expandable folder tree. Long-press a list’s six-dot handle and drop it on a folder header (including «Без папки»); a collapsed folder accepts drops too. Check several lists and use «Переместить выбранные» to move them together or create a destination folder. Dragging a checked list moves the checked group. Moving lists preserves vocabulary, progress, visibility and practice selection; lists remain alphabetical within folders. Folder menus let you rename or remove folders; removing a folder keeps its lists. Empty folders disappear automatically. The practice selector uses a native expandable folder tree. Tap a folder to show or hide its lists, then check individual lists. Folder headers show selected/total counts; selected folders open initially. Collapsing a folder preserves selection, Cancel discards changes, and Apply requires at least one selected list. Lists without a folder appear under «Без папки».

Hide a list to remove it from practice without losing vocabulary or progress. Enable **Показывать скрытые наборы** in the organizer, open the list’s actions and choose **Показать**, then select it for practice when needed. At least one list must remain visible. Rename or permanently delete a list from its management menu; deletion requires confirmation and removes that list’s vocabulary and progress. Folder assignments and visibility persist locally and travel with full backups and sync. Vocabulary-only exports still contain vocabulary only.

Phone validation: 18 focused Android tests passed on the connected physical phone using an isolated application ID and fake Firebase configuration. Coverage includes the end-to-end folder/hide/restore/delete flow, persistence, list ordering, mixed practice, backup import, and local sync merging. The personal app and its library were not modified. Production cloud sync was not exercised.

Folder-tree phone validation: both `PracticeTreeTest` and the existing `LibraryMultiSelectTest` passed in the isolated phone app. Checks cover expansion/collapse, draft selection retention, Cancel, empty-selection rejection, hidden-list exclusion, and selection persistence after activity recreation. The tree screenshot was visually inspected on the phone layout.

Organizer-tree validation: four focused tests passed on the isolated physical-phone app, including a real long-press/drop into a collapsed folder, bulk movement into a new folder, folder rename/removal, hide/restore/delete actions, unchanged practice selection, atomic rejection of invalid bulk moves, and the practice selector regression. The organizer screenshot was visually inspected.

### Nested folders

In **Наборы и папки**, open a folder’s three-dot menu and choose **Переместить папку**, then choose its parent (or **В корень**). The entire branch moves together. Both the organizer and practice selector show indented, expandable nested branches; collapsing a parent hides all descendants without changing selections. Folder counts include descendants. Up to eight levels are supported.

Renaming a folder updates all descendant paths. Removing a folder promotes its lists and subfolders to its parent; it does not delete vocabulary or progress. Moves into the same folder or its descendants are rejected, as are destination-name collisions and excessive depth, before any changes are saved. Paths are stored with packs and included in full backups and sync. The destination picker displays complete folder paths to distinguish identically named subfolders.

Nested-folder validation: build, lint, all 89 JVM tests, and six focused tests on the isolated phone app pass. Phone checks cover full-branch moves and renames, collision/cycle rejection, backup round-trips and reload, expansion/collapse in both trees, existing drag/bulk organization, and unchanged practice selection.


### Version 2.3 release validation

Version 2.3 (Android version code 7) includes folder organization, nested folder trees, list visibility, bulk and drag moves, and refreshed navigation with scroll restoration between main screens. Debug, unsigned release, and instrumentation APK builds pass. All 89 JVM tests and 45 focused API 37 emulator tests pass. Emulator checks cover nested branch operations, drag/bulk moves, practice selection, backup round-trips, local sync merges, review persistence, learning, session refresh, and answer undo. Debug lint reports 39 warnings and no errors. The staged public-file credential check passes. Production cloud sync and a signed in-place device update were not exercised in this release check. See [PUBLISHING.md](PUBLISHING.md) for signing and the draft-release checklist.

### Faster word-library navigation

The Words page uses one native recycled list for its controls and word cards, creating only visible rows instead of constructing 80 complete cards before showing the page. All words are now reachable by scrolling; the previous 80-word display limit is removed. Search filters one in-memory snapshot and reuses normalized search text. Selection counters update without rebuilding the library. Rebinding a recycled card suppresses checkbox callbacks and restores that word’s selection and independent recognition/writing marks. The list retains its position when switching bottom tabs and preserves the two-column layout on wider windows.

Reading library cards no longer saves the entire library or serializes review histories. It reads current active-pack and mixed-practice progress without writing preferences, including unsaved in-memory marks.

Validation: debug and instrumentation APK builds, all 89 JVM tests, and debug lint pass (0 errors). All 15 focused Android tests passed in a separate application on the connected phone, covering 87- and 1,000-word lists, reaching the last word, recycled checkbox state, search, navigation, recreation, mixed progress, read-only library snapshots, multi-pack selection, folder organization, and pack flows. With a fresh 87-word fixture on that phone, four baseline openings took 321–372 ms in the navigation handler and 773–978 ms to the next idle; the updated version took 11–13 ms and 23–27 ms respectively. These are fixture measurements, not a guarantee for every library/device. The update was installed over the personal app with a private preference backup; all six saved preference files were verified byte-for-byte unchanged immediately after installation.


### Practice responsiveness and extra-word coverage

Moving to the next question and acknowledging a word preview now persist only local session state. Scoring captures the prior skill directly for undo and saves learning data once, instead of saving the entire library before and after every answer. Sync caches canonical pack snapshots and acknowledges unchanged uploads without rebuilding the library; newer answers and unfinished questions remain intact.

Validation: debug and instrumentation builds, Android lint, and all 89 JVM tests pass. Forty-four Android regression checks passed in a separate app on the connected tablet, covering complete five-word batches, reload/refresh, undo, mixed-pack progress, sync snapshots/acknowledgments, and keyboard controls. A separate timing run used a private copy of the existing learning data with account/sync settings excluded. Question selection and saving took 6–9 ms versus 43–53 ms before; scoring took 38–42 ms versus 66–87 ms; unchanged-upload acknowledgment took 21–27 ms versus 336–360 ms. These are action measurements on that device, not a guarantee for every library or a live cloud-network benchmark.

The fix was installed over the personal tablet app after a private backup; all saved preference files were verified byte-for-byte unchanged immediately after installation.


### Offline stroke-order viewer

**Порядок черт** is available on word cards, new-word previews, revealed writing answers, and answer feedback. Select individual characters (including alternate written forms), play/pause/resume, replay, slow down, or advance one stroke at a time. The viewer uses the app's colors and works without a network connection. Selection and speed survive activity recreation; playback restarts from the character preview. Returning to a writing question retains the drawing, reveal state, and self-assessment. Stroke viewing does not score a review.

**Номера и стрелки** shows numbered starting points and direction paths, including the final turn of hooked strokes. Guides are hidden by default. When enabled, they appear for the whole character; playback and manual steps show only the current stroke's guide until the character is complete. The toggle works during playback without restarting it, and its state survives character changes, speed changes, and activity recreation. Guides use the bundled stroke medians and resize with the drawing; unsupported characters clear them.

Guide validation: debug and instrumentation builds and debug lint pass (0 errors). The 13-test stroke-viewer suite reports no failures on a fresh API 37 phone emulator; its tablet-only layout check is skipped there. Phone and tablet browser previews were inspected, including a 29-stroke character and narrow-screen fallback. All 9,574 bundled characters have matching stroke/median counts and at least two median points per stroke. Personal device installs were not changed.

The APK bundles Hanzi Writer 3.7.3 and all 9,574 characters in Hanzi Writer Data 2.0.1. Unsupported characters display an explicit message and disable playback; the app never substitutes a different written form. Data coverage is checked by Unicode code point, including supplementary characters. The corrected vocabulary export available during development contained 361 distinct Chinese characters across 399 unique words; all 361 were covered. This is an export audit, not a claim that every Chinese character is supported.

`StrokeOrderActivity` serves only whitelisted bundled files through `WebViewAssetLoader`. Network loads, file/content access, and navigation are disabled; there is no JavaScript/native bridge. Vocabulary is passed as JSON and inserted as text. Sources and full licenses are available offline in the viewer. Hanzi Writer code is MIT licensed; the unmodified stroke data has the separate Arphic Public License. See `app/src/main/assets/strokes/README.md` and `tools/vendor_strokes.py` for pinned versions, integrity checks, and reproducible asset generation. No HanziStroke website content is copied or requested at runtime.

Validation: debug and unsigned release builds pass, all 89 JVM tests pass, and debug lint reports 0 errors (45 warnings). Seven focused checks passed in an isolated app on the connected phone: six viewer/integration checks plus the existing drawing reflow test. The connected phone library also contained 361 distinct Chinese characters, all covered. The tested debug update was installed in place after a private preference backup; all six preference files were byte-for-byte unchanged immediately after installation.

The stroke viewer now adapts to its available window: tablets use a word/character panel beside a larger drawing area, with four playback controls in a row when space permits. Narrow windows stack the panels. Drawing size responds to both width and height and updates when the window changes. A native toolbar keeps the back arrow at the top-left with a 48 dp or larger touch target. The word and pinyin appear together on compact screens; the screen title lives in the toolbar.

Swipe left/right anywhere in the viewer content to move through characters in the current written form. Swiping over a button does not activate it; ordinary taps still work. Swiping at a word boundary stays on that character rather than changing to an alternative form. Vertical gestures still scroll. **Следующая черта** remains enabled whenever stroke data is available, including during animation and pause: it switches to manual stepping, finishes the current stroke, and honors rapid taps in order. At the end, **К первой черте** starts the first stroke again. Switching characters cancels pending steps so they cannot affect the next character.

Tablet/swipe validation: debug build and lint pass. All nine stroke-viewer checks passed in the isolated app on the connected tablet, including real touch swipes, paused/rapid stepping, back navigation, adaptive window bounds, offline rendering, alternate forms, and writing-state preservation. Three corresponding layout/gesture/stepping checks passed on the phone emulator. Full tablet landscape and narrow-window screenshots were visually inspected; the tablet's UiAutomation screenshot API crops the display, so the visual check uses full `screencap` captures instead.

Viewer-wide swipe validation: the debug build and two focused physical-tablet checks pass. Touch swipes were exercised on the drawing, word panel, position label, and playback button; button taps after swiping, vertical scrolling, and written-form boundaries were also verified.

Portrait tablets now use a compact word/character header above a full-width drawing instead of the landscape sidebar. Alternate written forms share the header when space permits, and the four playback controls sit in one row. The larger drawing is bounded by the available window height. Landscape keeps its side-by-side layout. Three checks passed on the connected tablet in its actual full-screen portrait configuration (777 dp wide), covering the drawing/header/control bounds, viewer-wide swipes and vertical scrolling, and selection persistence. A full-screen portrait capture was visually inspected.

### Progress and handwriting responsiveness

Preparing the Progress page no longer saves and reloads the entire library. It reads current active-pack and mixed-practice results directly, preserving unsaved results without writing preferences or triggering sync. The difficult-word section indexes words by ID instead of repeatedly scanning the pack.

The writing pad retains Android's batched touch samples and appends them to cached stroke paths. Each frame draws one path per stroke instead of issuing a drawing call for every segment. Normalized saved coordinates remain compatible with existing drawings and resize correctly; taps remain visible, and cancelled gestures save their strokes without triggering a click.

Validation: debug and instrumentation APK builds, all 91 JVM tests, and 21 focused API 37 emulator tests pass. Debug lint reports 0 errors and 42 warnings. In the same emulator with 500 words and 10,000 review records, the median overview calculation fell from 118.3 ms to 7.6 ms (seven measured runs after two warm-ups). This measures the overview calculation, not complete screen rendering or physical-device frame rates. Tests cover read-only overview access, current and mixed progress, restored drawings, batched samples, resizing, library recycling, and section swipes. Personal device installs and data were not modified.

### Shared words across packs

Identical cards share each skill’s progress, manual knowledge marks and new-word introduction. Mixed practice schedules one entry if any selected pack has that word checked. Library cards keep separate checkboxes, hints, examples and pack membership; the card label lists the packs sharing its progress. Overall progress counts unique words and skills, while pack summaries retain their own word counts.

Matching requires the same complete written-form set, normalized numbered pronunciation and normalized full meaning set; meaning order, case, surrounding punctuation and whitespace do not matter. Different readings or meanings remain separate. Editing only notes/examples preserves progress. Changing learning content through the word editor detaches that card; importing a matching card reuses progress. Accepting a synonym after an answer updates all identical occurrences while preserving their notes/examples.

When existing copies disagree, each skill uses the latest timestamped scheduler snapshot (with deterministic revision ties), maximum counters rather than sums, and a bounded union of compatible review history. Legacy states without timestamps use a deterministic fallback. The first upgrade with duplicates keeps the original packed library in the private `beforeSharedWords` preference, migrates introductions, and restarts old prompts whose mixed IDs may have changed. Undo restores every affected occurrence, including after reopening. Shared results remain in the existing per-pack backup/sync format; repeated conflicting backup imports recognize previously created copies after their shared progress changes.

Validation: debug and instrumentation APK builds, all 96 JVM tests and 42 focused API 37 emulator checks pass; debug lint reports 0 errors (52 warnings). Coverage includes shared queues and knowledge marks, independent selections/context, migration and recovery copy, unchecked/hidden occurrences, exact undo after reopening, synonym correction and joining, unique totals, local sync merging, backup retries and the existing practice flows. The shared-word card was visually inspected. Cached matching keeps the 500-word/10,000-review overview fixture at a 12.1 ms median in this disposable emulator run; this is not a physical-device frame-time measurement. Production cloud sync and personal phone/tablet installs were not exercised or modified.
