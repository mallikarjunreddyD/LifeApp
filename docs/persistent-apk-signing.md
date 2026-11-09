# Persistent installable APK signing

The dedicated LifeApp update identity is retained in encrypted GitHub Actions secrets.
The workflow restores that existing key; it never generates a replacement on a runner.
Only config/android-signing-certificate.sha256 is committed, containing the public
certificate fingerprint. Private keys/passwords are never committed or uploaded as
workflow artifacts. Base64 transports the keystore into the encrypted secret; Base64
itself is not encryption.

Public signer SHA-256:

`27688897e503889b893458c3dfa53a05268e9255a32f612ec702d9f6900f5cdd`

## Download the correct artifact

After the Android verification workflow finishes successfully, download
**my-journal-installable** and extract MyJournal.apk. It also contains public signer,
APK SHA-256 and package/version information. The artifact named
**my-journal-ci-debug-ephemeral** is for CI diagnostics and is not the install/update
channel. Installable APKs are currently development/debug builds, preserving the
Phase3.1 input diagnostic; this is not a Google Play release pipeline.

The signed job waits for the full JVM/build/lint and Android emulator jobs. It runs
on pushes/manual runs for main and on the authorized phase-5-server-ready-refactor
branch for pre-merge verification. It does not
run for pull_request events or unrelated branches. Ordinary verification/emulator
jobs receive no signing secrets. No pull_request_target workflow is used.

## Provisioned secrets and fail-closed behavior

- LIFEAPP_SIGNING_KEYSTORE_BASE64
- LIFEAPP_SIGNING_STORE_PASSWORD
- LIFEAPP_SIGNING_KEY_ALIAS
- LIFEAPP_SIGNING_KEY_PASSWORD

prepare-signing.py writes the decoded key under RUNNER_TEMP in owner-only storage,
checks its public certificate against the committed pin and exports only its path.
Gradle uses the existing LIFEAPP_SIGNING_* configuration. The signed build disables
configuration/build caches and its separate Gradle cache is not uploaded. Cleanup
removes runner-temporary signing material even on failure. Private values are scoped
to the key-preparation/build steps and never printed.

verify-installable-apk.py runs apksigner verification, checks the actual APK signer
against the same pin, and verifies the unchanged com.myjournal.android package ID.
Only then can the installable artifact upload. Missing secrets, invalid credentials,
wrong certificates or an ephemeral-key fallback fail the job instead of publishing
an APK that silently changes identity. Do not change the pin to make an unexpected
key pass: investigate and recover the original signing identity.

## Key recovery and future builds

A protected local recovery set is retained outside Git at
`~/.local/share/lifeapp-signing/`: lifeapp-updates.p12, credentials.json and non-secret
instructions/public metadata. The directory is mode700 and private files mode600.
Back up the keystore AND credentials to separate protected storage. Do not paste
these files into chat, commit them or attach them to an issue. GitHub secrets cannot
be downloaded later as a recovery backup. Losing both the recovery set and GitHub
secrets would lose this identity; never rotate/regenerate it automatically.

Local builds can use the same keystore through the four Gradle LIFEAPP_SIGNING_*
environment variables described in safe-android-upgrade.md. Future code updates must
keep this key/pin and use an appropriate versionCode. This signing correction uses
versionCode6/versionName0.4.2; data schema remains5 and Phase3 backup readers remain.

## The existing Phase3 installation

This new identity cannot update the old ephemeral CI-signed installation in place.
The original private key is still required for that route. The persistent identity
prevents recurrence after the first transition; it does not bypass Android's existing
signature check. Keep the old app and verified backup intact until a separate safe
backup/restore migration is planned. This task does not uninstall/clear the tablet.

[GitHub binary secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
[Android app signing](https://developer.android.com/studio/publish/app-signing)

## Phase 4 update

Version 0.5.0 uses version code 7 and the same package and pinned certificate as
0.4.2. It migrates Room from schema 5 to 6 on the next database open. Install
`MyJournal.apk` from `my-journal-installable` over the persistent-key build to
preserve local data. The diagnostic APK has its own signing identity.

Phase 5 uses version 0.6.0 and version code 8 with the same package and signing pin.
