# Automatic backup

Android and PostgreSQL are independent recovery sources. Android enrolls
in Backup & Recovery after connecting a Google account. Keep the recovery
passphrase independently before enrollment. The app stores an AES-GCM
wrapped copy using an Android Keystore key. The resulting backups use the
existing portable encrypted envelope. Moving to a new tablet requires the
passphrase and access to the Drive account or a saved encrypted copy.
Changing the passphrase starts a separate managed generation. Keep the
old passphrase for old files. Pause removes the locally wrapped credential.

A 24-hour WorkManager job waits for committed writes, captures and validates
a Room snapshot, encrypts it, and verifies the local ciphertext. A separate
connected job uploads the same ciphertext and reads it back. If Google
Play services requires consent, reauthorize in Settings. The worker never
opens an authorization prompt. The interval is approximate and the
existing 16 MiB encrypted archive limit still applies.

The supplied server timers schedule capture at 09:00 and 21:00 in
Asia/Kolkata and check pending uploads every five minutes. The capture
uses the existing consistent PostgreSQL snapshot, manifest and age
encryption. Failed uploads retain their ciphertext and retry using a
persisted deadline with bounded exponential delay. Backup & Recovery
reads the authenticated server status endpoint. That status reflects
reported verification, and it does not establish that a timer is enabled.
