"""Restore the established signing identity into private runner-temporary storage."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess

required = ("LIFEAPP_SIGNING_KEYSTORE_BASE64", "LIFEAPP_SIGNING_STORE_PASSWORD", "LIFEAPP_SIGNING_KEY_ALIAS")
if any(not os.environ.get(name) for name in required):
    raise SystemExit("Persistent signing secrets are missing. Refusing an ephemeral-key fallback.")
expected = Path("config/android-signing-certificate.sha256").read_text().strip()
if not re.fullmatch(r"[0-9a-f]{64}", expected):
    raise SystemExit("Invalid public signing certificate pin.")
private_dir = Path(os.environ["RUNNER_TEMP"]) / "lifeapp-signing"
private_dir.mkdir(mode=0o700, exist_ok=True)
private_dir.chmod(0o700)
key = private_dir / "updates.p12"
try:
    data = base64.b64decode(os.environ["LIFEAPP_SIGNING_KEYSTORE_BASE64"], validate=True)
    if not 100 <= len(data) <= 16 * 1024:
        raise ValueError("Invalid keystore size")
    fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
    keytool = Path(os.environ["JAVA_HOME"]) / "bin" / "keytool"
    result = subprocess.run([str(keytool), "-exportcert", "-keystore", str(key),
        "-alias", os.environ["LIFEAPP_SIGNING_KEY_ALIAS"],
        "-storepass:env", "LIFEAPP_SIGNING_STORE_PASSWORD"], capture_output=True)
    if result.returncode or hashlib.sha256(result.stdout).hexdigest() != expected:
        raise ValueError("Signing certificate does not match the established identity")
    with open(os.environ["GITHUB_ENV"], "a") as stream:
        stream.write("LIFEAPP_SIGNING_STORE_FILE=" + str(key) + "\n")
    print("Established signing identity restored and public certificate verified.")
except Exception:
    key.unlink(missing_ok=True)
    raise SystemExit("Persistent signing setup failed. Private values were not logged; no installable APK will be published.")
