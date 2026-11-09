# Encrypted Google Drive backup

Android manual backup remains available in Phase 5. The future server recovery
pipeline is separate. [Server backup design](server-backup-design.md)

## Configure a dedicated test project

1. In Google Cloud, create or select the LifeApp project and enable Google Drive API.
2. Configure Google Auth Platform branding, audience and the consent screen. In
   testing mode add a dedicated Google account to the test users. Add the non-sensitive
   scope `https://www.googleapis.com/auth/drive.appdata`.
3. Create an Android OAuth client for package `com.myjournal.android`. Register
   the installable APK's SHA-1 signing certificate
   `23:A8:8A:DA:37:5A:12:46:51:53:1B:29:78:81:CF:80:56:9D:77:76`.
   Its SHA-256 is recorded in the repository's public signing pin. Use the actual
   fingerprint of a local debug APK if testing it instead. An ephemeral CI APK
   has a different certificate. Keep any future platform OAuth clients in this
   same project when validating access to the same app data.
4. Install the persistent signed build on a device with Google Play services
   and Android 8 or later. Open Settings → Google Drive backup → Connect Google
   Account, choose the test account and approve the requested scope.

Google Play services AuthorizationClient returns a short-lived token for the
explicitly chosen account. The app remembers the account name locally. Tokens
and passphrases remain in memory for the operation or active Settings session.
Reauthorize when prompted, when you return to Settings after the token was
discarded, or after an HTTP 401. Disconnect clears local account state and requests
grant revocation. The app holds no service-account key, OAuth client secret,
refresh token or backend account. Play services auth 21.5.0 preserves the app's
API 23 minimum. The later 22.0.0 library requires API 24.

## Backup and restore

Each upload captures committed local records, creates and validates a standard
LifeApp ZIP, then encrypts it before contacting the upload API. An immutable
binary file with an opaque name is created in appDataFolder. The app reads the
file back and compares every encrypted byte before reporting success. It preserves
prior versions. Open List Drive backups to see creation times and choose a version.
Enter the original passphrase. Authentication, ZIP checksums and record validation
precede the existing restore preview. Confirm replacement and create a separately
verified recovery ZIP whenever the database has data.

Encrypted backups accept ZIPs up to 16 MiB to bound the memory used by authenticated
encryption. Local backups still support the documented 64 MiB limit. Drive backup
operations are manual. If an upload is interrupted, the app verifies its generated
ID before reporting success. If verification is unavailable, retrying manually may
create an extra version. A partial upload never replaces a prior successful file.
There is no automatic remote deletion.

## Recovery and envelope

Keep the original passphrase in a password manager or an independent protected
record. Use a strong passphrase with at least 12 characters. Exact characters,
including whitespace, matter. The passphrase cannot be recovered through Google
or LifeApp. Backups are separate from live synchronization.

Envelope v1 contains the eight ASCII bytes `LIFEBAK1`, a random 16-byte salt and a
random 12-byte nonce. The entire 36-byte header is AES-GCM additional authenticated
data. The remaining bytes are ciphertext followed by the 16-byte authentication
tag. Derive a 256-bit AES key using PBKDF2-HMAC-SHA256 over the UTF-8 passphrase with
the given salt and 600,000 iterations. Platform cryptography supplies SecureRandom,
PBKDF2 and AES-GCM. The receiver must authenticate before opening the plaintext
archive. The contents and original file names remain encrypted. Drive still sees
file sizes, random names and creation timestamps. Plain local recovery backups
continue to use the existing ZIP format and need a private location.

## Authenticated acceptance test

Use disposable data and a test account. Create two different backups and verify
both appear after restart and reauthorization. Disconnect and test another account
without uploading the first account's data. Return to the original account and
attempt restore with a wrong passphrase, then with the correct one. A wrong
passphrase must leave the local database unchanged. Validate a restore into a clean
database and a confirmed restore requiring a separate recovery file. Interrupt a
backup network request and verify a prior version still opens. Verify journal ink,
archived pages, planner/habit/notebook records and tasks after restore. This test
requires a configured OAuth project and authenticated Android device.

- [Google authorization on Android](https://developers.google.com/identity/authorization/android)
- [Google Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
