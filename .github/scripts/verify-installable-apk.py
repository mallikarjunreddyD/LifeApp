"""Publish an APK only if its verified public signer equals the committed pin."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

apk = Path("apps/android/build/outputs/apk/debug/android-debug.apk")
expected = Path("config/android-signing-certificate.sha256").read_text().strip()
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
result = subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True)
actual = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", result.stdout, re.MULTILINE)
if actual != [expected]:
    raise SystemExit("APK signer mismatch. Refusing to publish an APK with an unexpected signing identity.")
package = subprocess.run([str(tools / "aapt2"), "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout.splitlines()[0]
if "name='com.myjournal.android'" not in package:
    raise SystemExit("Unexpected application ID; refusing artifact publication.")
output = Path("dist/installable")
output.mkdir(parents=True, exist_ok=True)
shutil.copyfile(apk, output / "MyJournal.apk")
(output / "signer-certificate-sha256.txt").write_text(expected + "\n")
(output / "apk-sha256.txt").write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "  MyJournal.apk\n")
(output / "package-info.txt").write_text(package + "\n")
print("Installable APK signature/package verified. Public signer SHA-256:", expected)
