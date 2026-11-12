#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ $# != 4 ]]; then
  echo 'Usage: download-verified.sh RCLONE_CONFIG REMOTE_FOLDER TRUSTED_LEDGER BACKUP_NAME' >&2
  exit 2
fi
config=$1 remote=$2 ledger=$3 name=$4
[[ $name =~ ^(lifeapp-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}|server-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{32})\.tar\.age$ ]] || exit 2
expected=$(awk -v name="$name" '$2 == name {print $1}' "$ledger")
[[ $expected =~ ^[a-f0-9]{64}$ ]] || { echo 'The trusted ledger must contain exactly one digest.' >&2; exit 1; }
[[ ! -e "$name" ]] || { echo 'The output archive already exists.' >&2; exit 1; }
temp=$(mktemp -d .lifeapp-download.XXXXXX)
trap 'rm -rf "$temp"' EXIT
rclone --config "$config" copyto "${remote%/}/$name" "$temp/archive"
actual=$(sha256sum "$temp/archive" | cut -d ' ' -f 1)
[[ "$actual" == "$expected" ]] || { echo 'Downloaded backup does not match the trusted digest.' >&2; exit 1; }
ln "$temp/archive" "$name"
printf 'Verified %s\n' "$name"
