"""Protect known recovery points from pruning regressions."""
from retention import expired


def name(date, suffix="a" * 12):
    return f"lifeapp-{date}T000000Z-{suffix}.tar.age"


old = name("20260201")
recent = {name("20261109"), name("20261108")}
unverified = name("20261107", "b" * 12)
assert expired(recent | {old}, recent | {old, unverified}, 1, 1, 1) == [old]
assert expired(recent | {old}, recent | {unverified}, 1, 1, 1) == []
assert expired(recent, recent | {unverified}, 1, 1, 1) == []
weekly = name("20261024")
monthly = name("20260904")
backups = recent | {weekly, monthly, old}
assert expired(backups, backups, 1, 2, 3) == [old]
print("Retention preserved daily, weekly and monthly recovery points and ignored unverified objects.")
