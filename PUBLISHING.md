# Publish Chinlearn 2.2.1

The source is prepared for version **2.2.1**, version code **6**, application ID `com.example.chinlern`.

## Verified before signing

- Debug, unsigned release, and instrumentation APK builds pass.
- All 86 JVM tests and 22 focused API 37 emulator tests pass.
- Debug lint: no errors, 28 warnings.
- The public-file credential check passes.

The owner will generate the signed APK in Android Studio. The existing file in `app/release/app-release.apk` is the old 2.2 build until replaced by a freshly signed build. The unsigned build in `app/build/outputs/apk/release/` is for build verification, not distribution.

## Signing and publication

1. In Android Studio, choose **Build → Generate Signed App Bundle or APK → APK**, select the `app` module and the `release` variant, and use the existing `~/chinlearn.jks` key and alias. Keep signing passwords out of repository files.
2. Check that the generated APK is version **2.2.1 / 6** and uses the existing release certificate. Its SHA-256 fingerprint must match:

   `0536a6f4c5f618e5ef11404d894bd74186a38733523f5d87a2c5dc04db42c5e5`

3. Export a full library backup before checking an in-place update on a device. Confirm the signed update installs and preserves the library. If installation fails, investigate without uninstalling or clearing data.
4. Push the prepared source commit to GitHub and create tag **v2.2.1** at that exact commit.
5. Create a GitHub release titled **Chinlearn 2.2.1**, using **v2.2.1** and the text in [RELEASE_NOTES.md](RELEASE_NOTES.md). Attach the freshly signed APK as **Chinlearn.apk**, matching the previous release's asset name.
6. Check the tag, release notes, and attached APK version before publishing the release.

No signing credentials, private configuration, APKs, or device backups belong in the Git commit. Preparation does not publish a release, push source, or change the existing GitHub release.
