#!/usr/bin/env python3
"""Capture independently, retry the same encrypted generation and prune verified copies."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from uuid import UUID, uuid4

NAME = re.compile(r"server-(\d{8}T\d{6}Z)-([a-f0-9]{32})\.tar\.age")
HASH = re.compile(r"[a-f0-9]{64}")


def run(*command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def put(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        json.dump(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
        temporary = Path(stream.name)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def records(directory):
    result = {}
    for path in directory.glob("*.json"):
        if path.name == "scope.json":
            continue
        record = json.loads(path.read_text())
        name = path.name.removesuffix(".json")
        match = NAME.fullmatch(name)
        if not match or record["name"] != name or not HASH.fullmatch(record["sha256"]):
            raise ValueError("Invalid managed backup record. Retention stopped.")
        datetime.strptime(match[1], "%Y%m%dT%H%M%SZ")
        if record["job"] != str(UUID(hex=match[2])) or record["size"] < 1:
            raise ValueError("Invalid managed backup identity. Retention stopped.")
        result[name] = record
    return result


def newest(names):
    return sorted(names, reverse=True)[:2]


def local_expired(entries, local, remote):
    keep = set(newest(local))
    return sorted(name for name in local - keep
                  if entries[name]["uploaded"] or len([other for other in remote if other > name]) >= 2)


def verified_remote(entries, listing):
    names = set()
    for item in listing:
        name = item["Name"]
        if name in names:
            raise ValueError("Duplicate Drive object name. Retention stopped.")
        names.add(name)
        if name in entries and item["Size"] != entries[name]["size"]:
            raise ValueError("Managed Drive object size mismatch. Retention stopped.")
    return {name for name in names & entries.keys() if entries[name]["uploaded"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["capture", "retry"])
    args = parser.parse_args()
    os.umask(0o077)
    identity = str(UUID(os.environ["LIFEAPP_BACKUP_MANAGED_ID"]))
    parent = Path(os.environ["LIFEAPP_BACKUP_DIRECTORY"]).resolve()
    directory = parent / f"automatic-server-{identity}"
    directory.mkdir(mode=0o700, parents=False, exist_ok=True)
    remote = f"{os.environ['LIFEAPP_BACKUP_DRIVE_REMOTE'].rstrip('/')}/{directory.name}"
    scope_path = directory / "scope.json"
    deploy = Path(__file__).resolve().parents[1]
    os.chdir(deploy)
    project = os.environ.get("LIFEAPP_BACKUP_PROJECT", "lifeapp")
    if not re.fullmatch(r"lifeapp(?:-smoke-[a-z0-9]+)?", project):
        raise ValueError("Use a LifeApp deployment or disposable smoke project.")
    release = Path(os.environ.get("LIFEAPP_BACKUP_RELEASE_FILE", "release.env"))
    db = [sys.executable, "backup/database-backup.py", "--project", project, "--release", str(release)]
    drive = ["rclone", "--config", os.environ["LIFEAPP_BACKUP_RCLONE_CONFIG"], "--retries", "2"]
    lock = (directory / ".lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX)
    with (release.parent / ".release.lock").open("a") as release_lock:
        fcntl.flock(release_lock, fcntl.LOCK_SH)
        dataset = run(*db, "scope", capture_output=True, text=True).stdout.strip()
        scope = hashlib.sha256(f"{remote}\n{dataset}".encode()).hexdigest()
        if scope_path.exists():
            if json.loads(scope_path.read_text()) != scope:
                raise ValueError("Managed destination changed. Configure a new managed identity.")
        else:
            put(scope_path, scope)
        entries = records(directory)
        if args.mode == "capture":
            commit = next(line.split("=", 1)[1] for line in release.read_text().splitlines()
                          if line.startswith("LIFEAPP_RELEASE_COMMIT="))
            job = uuid4()
            name = f"server-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{job.hex}.tar.age"
            output = directory / name
            run(*db, "report", "--id", str(job), "--state", "running")
            try:
                run(*db, "capture", "--commit", commit, "--recipients", os.environ["LIFEAPP_BACKUP_RECIPIENTS"],
                    "--output", str(output))
                record = {"name": name, "job": str(job), "sha256": digest(output), "size": output.stat().st_size,
                          "uploaded": False, "reported": False, "attempts": 0, "retryAt": 0}
                put(directory / f"{name}.json", record)
                entries[name] = record
            except Exception:
                run(*db, "report", "--id", str(job), "--state", "failed")
                raise

        def readback(name, record):
            process = subprocess.Popen([*drive, "cat", f"{remote}/{name}"], stdout=subprocess.PIPE)
            with process.stdout as source:
                checksum = hashlib.file_digest(source, "sha256").hexdigest()
            if process.wait() != 0 or checksum != record["sha256"]:
                raise ValueError("Drive read-back did not match the encrypted archive.")

        pending = [name for name, record in entries.items() if not record["uploaded"]]
        candidates = newest(pending + [name for name, record in entries.items() if record["uploaded"]])
        verified_now = False
        for name in candidates:
            record = entries[name]
            if record["uploaded"] or record["retryAt"] > time.time():
                continue
            path = directory / name
            try:
                if digest(path) != record["sha256"]:
                    raise ValueError("Managed local ciphertext failed its checksum.")
                run(*drive, "copyto", "--immutable", str(path), f"{remote}/{name}")
                readback(name, record)
                record["uploaded"] = True
                put(directory / f"{name}.json", record)
                verified_now = True
                print(f"Verified Drive backup {name}")
            except Exception:
                record["attempts"] += 1
                record["retryAt"] = time.time() + min(3600, 300 * 2 ** min(record["attempts"] - 1, 4))
                put(directory / f"{name}.json", record)
                run(*db, "report", "--id", record["job"], "--state", "failed")
                raise

        for name, record in entries.items():
            if record["uploaded"] and not record["reported"]:
                run(*db, "report", "--id", record["job"], "--state", "verified")
                record["reported"] = True
                put(directory / f"{name}.json", record)

        if not verified_now and args.mode == "retry" and not (directory / ".prune-pending").exists():
            return
        if os.environ.get("LIFEAPP_BACKUP_PRUNE") != "1":
            return
        (directory / ".prune-pending").touch()
        listing = json.loads(run(*drive, "lsjson", "--files-only", remote, capture_output=True, text=True).stdout)
        remote_verified = verified_remote(entries, listing)
        local = {name for name in entries if (directory / name).is_file()}
        remote_keep = newest(remote_verified)
        if len(remote_keep) == 2:
            for name in remote_keep:
                readback(name, entries[name])
            for name in sorted(remote_verified - set(remote_keep)):
                run(*drive, "deletefile", f"{remote}/{name}")
                print(f"Pruned verified Drive backup {name}")
        if len(local) >= 2:
            for name in newest(local):
                if digest(directory / name) != entries[name]["sha256"]:
                    raise ValueError("Local replacement did not verify. Retention stopped.")
            for name in local_expired(entries, local, set(remote_keep)):
                (directory / name).unlink()
                print(f"Pruned managed local backup {name}")
        (directory / ".prune-pending").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
