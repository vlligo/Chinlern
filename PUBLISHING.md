# Publish Chinlearn 2.3

The source is prepared for version **2.3**, version code **7**, application ID `com.example.chinlern`.

## Verified before signing

- Debug, unsigned release, and instrumentation APK builds pass.
- All 89 JVM tests pass.
- All 45 focused API 37 emulator tests pass, covering nested folders, drag/bulk organization, practice selection, backup round-trips, local sync merging, review persistence, learning, session refresh, and exact answer undo.
- Production cloud sync and a signed in-place device update have not been exercised for this release.
- Debug lint: no errors, 39 warnings.
- The public-file credential check passes.

The owner will generate the signed APK in Android Studio. Any existing file in `app/release/app-release.apk` must be treated as an old build until replaced and verified. The unsigned build in `app/build/outputs/apk/release/` is for build verification, not distribution.

## Signing and publication

1. In Android Studio, choose **Build → Generate Signed App Bundle or APK → APK**, select the `app` module and the `release` variant, and use the existing `~/chinlearn.jks` key and alias. Keep signing passwords out of repository files.
2. Check that the generated APK is version **2.3 / 7** and uses the existing release certificate. Its SHA-256 fingerprint must match:

   `0536a6f4c5f618e5ef11404d894bd74186a38733523f5d87a2c5dc04db42c5e5`

3. Export a full library backup before checking an in-place update on a device. Confirm the signed update installs and preserves the library. If installation fails, investigate without uninstalling or clearing data.
4. Source is pushed to `main` with tag **v2.3** at the release commit. Confirm the signed APK was built from that commit, with no source changes.
5. Open the draft GitHub release titled **Chinlearn 2.3**, using **v2.3** and the text in [RELEASE_NOTES.md](RELEASE_NOTES.md). Attach the freshly signed APK as **Chinlearn.apk**, matching the previous release's asset name.
6. Check the tag, release notes, and attached APK version before publishing the release.

No signing credentials, private configuration, APKs, or device backups belong in the Git commit. Source and the version tag are pushed to GitHub. The 2.3 release stays in draft until a freshly signed APK is attached and checked; the existing published release remains available.
