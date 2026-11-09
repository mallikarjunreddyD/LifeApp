"""Print exact verified Drive object names that have aged out of retention."""
import argparse
from datetime import datetime
from pathlib import Path
import re

NAME = re.compile(r"lifeapp-(\d{8}T\d{6}Z)-[a-f0-9]{12}\.tar\.age")
LEDGER = re.compile(r"[a-f0-9]{64}  (.+)")


def expired(verified, remote, daily, weekly, monthly):
    entries = []
    for name in verified & remote:
        match = NAME.fullmatch(name)
        if match:
            try:
                entries.append((datetime.strptime(match[1], "%Y%m%dT%H%M%SZ"), name))
            except ValueError:
                continue
    entries.sort(reverse=True)
    # Keep the latest two verified objects even when every timestamp shares a bucket.
    keep = {name for _, name in entries[:2]}
    for limit, key in (
        (daily, lambda at: at.date()),
        (weekly, lambda at: at.isocalendar()[:2]),
        (monthly, lambda at: (at.year, at.month)),
    ):
        kept_buckets = set()
        for at, name in entries:
            bucket = key(at)
            if bucket not in kept_buckets:
                keep.add(name)
                kept_buckets.add(bucket)
                if len(kept_buckets) == limit:
                    break
    return sorted(name for _, name in entries if name not in keep)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ledger", type=Path)
    parser.add_argument("remote_listing", type=Path)
    parser.add_argument("--daily", type=int, default=7)
    parser.add_argument("--weekly", type=int, default=4)
    parser.add_argument("--monthly", type=int, default=3)
    args = parser.parse_args()
    if min(args.daily, args.weekly, args.monthly) < 1:
        parser.error("Retention periods must each keep at least one generation.")
    verified = {match[1] for line in args.ledger.read_text().splitlines() if (match := LEDGER.fullmatch(line))}
    remote = set(args.remote_listing.read_text().splitlines())
    for name in expired(verified, remote, args.daily, args.weekly, args.monthly):
        print(name)


if __name__ == "__main__":
    main()
