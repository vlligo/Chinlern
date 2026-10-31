# Security and private configuration

The repository excludes `google-services.json`, `.firebaserc`, `sync-access.properties`, environment files, signing keys, service-account files, build outputs, APKs, logs, and device screenshots. Never commit account tokens, passwords, device preference exports, backup files, or signing credentials. Review staged changes before publishing. If a credential is accidentally published, revoke or rotate it; deleting the file alone does not remove Git history.

The sync password is a first-use convenience lock. Remembered access is scoped to the signed-in account; an authenticated, verified owner can recover access from an existing cloud library after reinstalling. Only its SHA-256 verifier is embedded in a configured app build. A person who controls a modified client can bypass this lock; it is not server-side authorization. Firebase security relies on the deployed Firestore rules: authenticated, verified users can access only their own data. Keep those rules deployed and test them before production use. Do not treat the shared sync password as protection for the database or as a Firebase account password.

Firebase Android configuration is client configuration, not a service-account private key. It can be extracted from a distributed APK. We exclude the owner's configuration from this public source repository as an additional privacy measure. Admin keys and account access/refresh tokens must never be packaged into an app. See [Firebase's security checklist](https://firebase.google.com/support/guides/security-checklist).

No APKs are published by CI. Each developer should supply their own Firebase project and verifier. JSON learning backups contain vocabulary and learning results, but never login credentials or the sync password/verifier. Treat backups as personal data.

To report a vulnerability, use GitHub's private vulnerability reporting feature if enabled. Do not put credentials or personal backups in a public issue.
