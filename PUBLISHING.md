# Publish Chinlearn 2.5

The release uses application ID `com.example.chinlern`, version **2.5**, version code **9**, and Git tag **v2.5**.

## Build and verify

1. Use the production Firebase configuration and sync access verifier from the ignored local files. Keep credentials and private device exports out of Git.
2. Run `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` and `python3 tools/check_public_files.py`. The Gradle release output is unsigned.
3. In Android Studio, use **Build → Generate Signed App Bundle or APK → APK**. Select the `app` module, the `release` variant, and the existing `~/chinlearn.jks` key and alias. Keep signing passwords out of repository files.
4. Use `aapt dump badging` to confirm the application ID and version **2.5 / 9**. Use `apksigner verify --print-certs` to check the release certificate SHA-256 fingerprint.

   `0536a6f4c5f618e5ef11404d894bd74186a38733523f5d87a2c5dc04db42c5e5`

5. Export a full library backup from a device running the published 2.4 APK. Install the signed 2.5 APK over that installation, then check vocabulary, learning progress, practice selections, and settings. Check that identical cards share progress while their individual checkboxes and notes are retained. If the update fails, keep the installation and its data for diagnosis.

## GitHub release

1. Confirm that tag **v2.5** points to the source used to build the signed APK and that GitHub checks pass.
2. Use the text in [RELEASE_NOTES.md](RELEASE_NOTES.md) for the **Chinlearn 2.5** draft. Attach the verified signed APK as **Chinlearn.apk**.
3. Check the tag, release notes, and attached APK before publishing.
