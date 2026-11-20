# Phase 9D unified synchronization

Settings → Synchronization provides one Sync now button, one automatic
synchronization switch and a combined status for To-do, Expenses, Workouts and
Habits. The preference belongs to the device or browser installation. Automatic
synchronization is enabled by default. Pausing it keeps local changes queued.
Sync now still runs a manual pass while automatic synchronization is paused.

A pass checks each connected module independently. Pending changes, conflicts,
connection reviews and failures prevent an Up to date result. Successful completion
records the last successful check. Errors identify the affected module. The existing
reviewed bindings, immutable operations, receipts, account scope and recovery epoch
remain the authority for applying changes and clearing pending work.

## Triggers

Local commits, opening the app, returning to the foreground and reconnecting request
a pass. Active clients check every five minutes. Android also schedules one
WorkManager task every fifteen minutes for all four modules. Android controls the
actual background execution time. Closed browser and PWA instances resume
synchronization when reopened.

The controller serializes passes and schedules a follow-up when a new commit arrives
during a pass. Each module has its own retry deadline and backoff. A deferred or
failed module allows the other adapters to run. Android replaces older module-specific
work schedules while retaining the corresponding worker classes for queued upgrades.

## Habit and draft safety

The web controller refreshes the Habit inventory and caches current versions for
the current month and previously opened months. The active grid follows the current
version. Historical versions remain explicitly selectable.

Android applies incoming Habit records through the existing transactional download
path. It waits for editor writes, checks expected local records, and rebuilds editor
caches after a successful apply. A backup, restore or import review defers the pass.
Open Workout drafts are protected and reported as requiring attention when they
prevent a download.

Automatic backup and export scheduling remain independent. Journal, Planner and
Notebook are excluded from this sync controller. This phase adds no database or
transport schema changes.

## Acceptance

Automated regressions cover repeated polling, a paused automatic setting across
reloads, a manual pass while paused, independent module failure, an external Habit
update in the open grid, and protected Workout drafts. The Android bridge also
exercises a shared manual pass while an intentional To-do conflict exists.

Xiaomi Pad 7 acceptance should check saving in each module, receiving changes from
another device, reconnecting, background operation under Xiaomi battery settings,
pausing and resuming automatic synchronization, and one manual pass while paused.
Production activation requires a separately approved rollout.
