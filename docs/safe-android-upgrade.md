# Data-preserving Android update

The application ID remains com.myjournal.android; versionCode5/versionName0.4.1
updates Phase3's versionCode4. Schema4→5 adds only journal_pages.archived DEFAULT0.
Do not uninstall the existing application as part of upgrade or validation.

## Before installing

On the installed Phase3 app, use Settings → Create full local backup. Wait for Backup
created and verified, save it to a local user-chosen location, and copy it off the tablet.
Keep that original file. Phase3.1 accepts the original format1/schema4 archive; frozen
Phase3-generated ZIP/JSON fixtures are validated/restored in automated tests. Also keep
any source APK and its signing keystore from the original builder if available.

## Verify the signing identity

Android only permits an in-place update with a matching signing identity (or an
appropriately configured signing lineage; no key rotation is implemented here).
An APK's public signing certificate does not contain the private signing key.

Read the installed APK path with `adb shell pm path com.myjournal.android`, pull the
reported base.apk to a temporary local file, then compare certificate SHA-256 digests:

```sh
apksigner verify --print-certs installed-phase3.apk
apksigner verify --print-certs android-debug.apk
```

Use apksigner from the installed Android Build Tools. Match the actual installed
certificate, not merely the alias or filename. If the digests differ, stop before
installation. Do not use uninstall, adb install -d, package-ID changes or data clearing
as an upgrade workaround.

## Build with the original key

Keep a private keystore outside the repository and use the same one for every update.
Gradle accepts these optional environment variables, all together:

- LIFEAPP_SIGNING_STORE_FILE: original keystore path.
- LIFEAPP_SIGNING_STORE_PASSWORD: keystore password.
- LIFEAPP_SIGNING_KEY_ALIAS: original alias.
- LIFEAPP_SIGNING_KEY_PASSWORD: key password.

Supply secrets through a local secret manager, protected environment/IDE configuration
or approved CI secrets, not committed files or command-line password arguments.
The build fails if this optional configuration is partial. Then run
`./gradlew :apps:android:assembleDebug` (gradlew.bat on Windows), verify its certificate
against the installed APK and use `adb install -r android-debug.apk`. The same source
code/tests build under either signing configuration. Back up the keystore securely;
private signing material/passwords must never be committed or uploaded as artifacts.

Without these variables, local builds use the builder's default Android debug key.
GitHub Actions also uses each runner's debug key; its APK is a test artifact and is
not promised to match the tablet's installed certificate. If Phase3 was installed from
an ephemeral CI artifact and its original key was not retained, an in-place update
cannot be produced from that artifact's public APK alone. Preserve the installed app
and its verified backup; recover the original private key/builder environment before
attempting an update. Signing compatibility with the physical tablet remains unverified
until its installed certificate is compared. No uninstall is required by this phase.

After a matching-key update, reopen and check existing journal/planner/habits/notebooks,
archival/restore, PDF and a new backup. Do not downgrade the migrated database to an
older app. Keep the original Phase3 backup for independent recovery and future testing.

[Android signing documentation](https://developer.android.com/studio/publish/app-signing)
[apksigner](https://developer.android.com/tools/apksigner)

## Certificate comparison performed for this correction

Read-only apksigner verification of the previously linked Phase3 CI artifact and the
first correction CI artifact confirms they have different signing identities:

| APK | Signer certificate SHA-256 |
| --- | --- |
| Phase3 run41630043074, artifact13228505396 | `10db264ffbedc183b8a28a5045938f0640d13ca819fcda4306fd26ad6e2f8028` |
| Phase3.1 run41977721629, artifact13392209511 | `3606f48136d434cf4ab73e3e7de8f16ac121995bf580d70dff1c8c771b8ac3ac` |

These are public certificate digests, not private keys or APK-file checksums. If the
installed tablet app is that Phase3 CI APK, that correction CI APK cannot update it
in place. The old private key/builder identity is still required; source code and a
verified backup alone cannot recreate a signing key. Future CI runs may have yet another
key. The optional Gradle signing configuration was separately tested using the existing
local debug key and produced the same certificate as its default builder configuration.
That does not establish a match to the tablet. Compare the actual installed APK before
installing any candidate, and preserve the app/backup if its key is unavailable.
