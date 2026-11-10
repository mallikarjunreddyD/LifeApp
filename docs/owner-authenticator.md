# Owner sign-in with Google Authenticator

Deploy the matching server and PWA, and install the persistently signed Android
update before enrolling. Keep the Tailscale route private while verifying both
clients. After enrollment, sign in with the owner password and either a current
six-digit authenticator code or an unused recovery code. Wait for the next code
after enrollment or a successful sign-in before signing in on another device.

## Enroll and rename the current owner

On the VM, use the deployed LifeApp directory and a private terminal. Confirm
you have a verified database backup. Generate a fresh Base32 key in
`secrets/owner-totp` as root. Restrict the file to root and the container's group
10001, and keep a protected recovery copy separately from the database archive.

```sh
cd /srv/projects/lifeapp
sudo sh -c 'set -C; umask 077; python3 -c "import base64,secrets; print(base64.b32encode(secrets.token_bytes(20)).decode())" > secrets/owner-totp'
sudo chgrp 10001 secrets/owner-totp
sudo chmod 0640 secrets/owner-totp
sudoedit .env
```

Add `LIFEAPP_OWNER_TOTP_PATH=/srv/projects/lifeapp/secrets/owner-totp` to
`.env`. Run Compose with the same release and project settings as the deployment.
A rollout before enrollment keeps the existing owner login working.

```sh
sudo docker compose -p lifeapp --env-file .env --env-file release.env up -d --wait
sudo cat secrets/owner-totp
sudo docker compose -p lifeapp --env-file .env --env-file release.env \
  exec api /opt/lifeapp/bin/server enroll-owner-authenticator
```

In Google Authenticator, add a key using manual setup, name the entry LifeApp,
and select **Time based**. Enter the displayed Base32 key on your device and
enter the current six-digit code at the enrollment prompt. On this deployment,
enter `mallikarjun` as the current username and `rummuer` as the new username.
Store the printed recovery codes offline. Keep the seed and recovery codes out
of logs, screenshots, issue trackers and shell command arguments.

Enrollment preserves the owner ID, password, dataset, and recovery epoch. It
revokes previous sessions and the previous username can no longer sign in.
An unknown username receives a generic rejection. The existing temporary
throttle stays in effect. Authenticator code replay and recovery code use
are checked transactionally. Missing seed material fails closed for
authenticator codes, while unused recovery codes still work with the password.

If a device is lost, restore the authenticator entry from the protected seed
copy or use a recovery code. PostgreSQL backups contain the enrollment state
and recovery-code hashes. Retain the corresponding seed separately and restore
it with the database on a clean deployment. Recovery-code state is restored
to the backup checkpoint. Before exposing a restored service, run the
`rotate-owner-authenticator` CLI command using the current username and
a current code. It replaces recovery codes and revokes sessions. To rotate a
lost or disclosed seed, first replace the protected seed and recreate the API
container on the private route, then run that command with a code from the new
seed.

Verify both clients on private HTTPS, the rejection of the old username, and
the rejection of a missing or reused factor before approving public Funnel
access. [TOTP specification](https://www.rfc-editor.org/rfc/rfc6238.html)
