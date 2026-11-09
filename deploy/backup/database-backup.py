#!/usr/bin/env python3
"""Capture and restore the LifeApp PostgreSQL database with an age recipient."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
from uuid import UUID

DEPLOY = Path(__file__).resolve().parents[1]
CHECKPOINT = """
SELECT json_build_object(
    'epochs', (SELECT coalesce(json_agg(row_to_json(e) ORDER BY e.dataset_id), '[]')
               FROM (SELECT dataset_id, id, last_commit FROM recovery_epochs WHERE active) e),
    'counts', json_build_object('accounts', (SELECT count(*) FROM accounts),
        'operations', (SELECT count(*) FROM sync_operations),
        'heads', (SELECT count(*) FROM todo_heads),
        'workoutSessions', (SELECT count(*) FROM workout_sessions)),
    'schema', (SELECT max(installed_rank) FROM flyway_schema_history WHERE success)
);
"""


def compose(project, release, *args):
    result = ["docker", "compose", "-p", project, "-f", str(DEPLOY / "compose.yaml")]
    settings = DEPLOY / ".env"
    if settings.exists():
        result += ["--env-file", str(settings)]
    if release:
        result += ["--env-file", str(release)]
    return result + list(args)


def db_command(args, *command):
    return compose(args.project, args.release, "exec", "-T", "db", *command)


def psql(args, *options):
    return db_command(args, "psql", "-XqAt", "-U", "lifeapp_admin", "-d", "lifeapp", "-v", "ON_ERROR_STOP=1", *options)


def run(command, **options):
    return subprocess.run(command, check=True, **options)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate(condition, message):
    if not condition:
        raise RuntimeError(message)


def capture(args, work):
    validate(re.fullmatch(r"[a-f0-9]{40}", args.commit), "Expected a full server commit SHA.")
    archive = work / "backup.tar"
    dump = work / "database.dump"
    with subprocess.Popen(psql(args), stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as session:
        session.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n")
        session.stdin.write(CHECKPOINT + "\n")
        session.stdin.flush()
        snapshot = session.stdout.readline().strip()
        validate(re.fullmatch(r"[A-F0-9-]+", snapshot), "Could not acquire a PostgreSQL snapshot.")
        checkpoint = json.loads(session.stdout.readline())
        with dump.open("wb") as output:
            run(db_command(args, "pg_dump", "-U", "lifeapp_admin", "-d", "lifeapp", "--format=custom",
                           "--no-owner", "--no-privileges", f"--snapshot={snapshot}"), stdout=output)
        session.communicate("ROLLBACK;\n")
        validate(session.returncode == 0, "Could not close the database snapshot.")
    manifest = {
        "format": "LifeAppServerBackup",
        "version": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "commit": args.commit,
        "checkpoint": checkpoint,
        "database": {"bytes": dump.stat().st_size, "sha256": sha256(dump)},
    }
    (work / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with tarfile.open(archive, "w") as bundle:
        bundle.add(work / "manifest.json", arcname="manifest.json")
        bundle.add(dump, arcname="database.dump")
    # A hard link on the destination filesystem publishes a complete file without replacing an older backup.
    with tempfile.TemporaryDirectory(dir=args.output.resolve().parent) as encrypted:
        candidate = Path(encrypted) / "backup.age"
        run(["age", "--encrypt", "--recipients-file", str(args.recipients), "-o", str(candidate), str(archive)])
        os.link(candidate, args.output)
    print(f"Encrypted backup created at {args.output}")


def restore(args, work):
    validate(re.fullmatch(r"lifeapp-restore-[a-z0-9-]+", args.project), "Use a dedicated lifeapp-restore-* project.")
    running = run(compose(args.project, args.release, "ps", "-q", "api", "web"),
                  capture_output=True, text=True).stdout.strip()
    validate(not running, "Stop the restore project's API and web containers before importing.")
    validate(re.fullmatch(r"[a-f0-9]{64}", args.sha256) and sha256(args.input) == args.sha256,
             "Encrypted backup does not match its trusted checksum.")
    archive = work / "backup.tar"
    run(["age", "--decrypt", "--identity", str(args.identity), "-o", str(archive), str(args.input)])
    with tarfile.open(archive, "r:") as bundle:
        entries = bundle.getmembers()
        validate([entry.name for entry in entries] == ["manifest.json", "database.dump"] and
                 all(entry.isfile() for entry in entries), "Unexpected archive contents.")
        validate(entries[0].size <= 65_536, "Manifest is too large.")
        manifest = json.load(bundle.extractfile(entries[0]))
        validate(manifest["format"] == "LifeAppServerBackup" and manifest["version"] == 1,
                 "Unsupported backup format.")
        dump = work / "database.dump"
        with dump.open("wb") as target:
            shutil.copyfileobj(bundle.extractfile(entries[1]), target)
    validate(manifest["database"] == {"bytes": dump.stat().st_size, "sha256": sha256(dump)},
             "Database size or checksum mismatch.")
    empty = run(psql(args, "-c", "SELECT count(*) FROM pg_tables WHERE schemaname='public'"),
                capture_output=True, text=True).stdout.strip()
    validate(empty == "0", "Restore requires a clean database.")
    with dump.open("rb") as source:
        run(db_command(args, "pg_restore", "-U", "lifeapp_admin", "--role=lifeapp", "-d", "lifeapp",
                       "--single-transaction", "--no-owner", "--no-privileges"), stdin=source)
    checkpoint = json.loads(run(psql(args, "-c", CHECKPOINT), capture_output=True, text=True).stdout)
    validate(checkpoint == manifest["checkpoint"], "Restored dataset identities or counts differ from the manifest.")
    with (DEPLOY / "restore/new-epoch.sql").open("rb") as sql:
        run(psql(args, "--single-transaction", "--file=-"), stdin=sql)
    print(f"Restored commit {manifest['commit']} into {args.project}. Sessions revoked and recovery epochs rotated.")


def report(args):
    job = str(UUID(args.id))
    if args.state == "running":
        statement = (
            "INSERT INTO backup_jobs (id, dataset_id, started_at, state) "
            f"SELECT '{job}', id, now(), 'running' FROM datasets RETURNING id"
        )
    else:
        verified = "now()" if args.state == "verified" else "NULL"
        statement = (
            f"UPDATE backup_jobs SET state = '{args.state}', verified_at = {verified} "
            f"WHERE id = '{job}' AND state = 'running' RETURNING id"
        )
    result = run(psql(args, "-c", statement), capture_output=True, text=True).stdout.strip()
    validate(result == job, "Could not record the backup state for the owner dataset.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="lifeapp")
    parser.add_argument("--release", type=Path, default=DEPLOY / "release.env")
    sub = parser.add_subparsers(dest="mode", required=True)
    backup = sub.add_parser("capture")
    backup.add_argument("--commit", required=True)
    backup.add_argument("--recipients", required=True, type=Path)
    backup.add_argument("--output", required=True, type=Path)
    recovery = sub.add_parser("restore")
    recovery.add_argument("--identity", required=True, type=Path)
    recovery.add_argument("--sha256", required=True)
    recovery.add_argument("--input", required=True, type=Path)
    status = sub.add_parser("report")
    status.add_argument("--id", required=True)
    status.add_argument("--state", required=True, choices=["running", "verified", "failed"])
    args = parser.parse_args()
    if not args.release.is_file():
        args.release = None
    os.umask(0o077)
    if args.mode == "report":
        report(args)
        return
    with tempfile.TemporaryDirectory() as temporary:
        (capture if args.mode == "capture" else restore)(args, Path(temporary))


if __name__ == "__main__":
    main()
