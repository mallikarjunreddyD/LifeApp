# Shared workout models

The common domain defines reusable exercise definitions, planned routines,
recorded sessions, exercise entries and sets. Definitions can be renamed or
archived while retaining their UUID. An archived definition remains a valid
reference for recorded history. A routine stores an ordered exercise template.
Actual measured values belong only to a session.

Sessions carry the user's calendar date independently of UTC start and end
instants. Active duration is stored in integer seconds and may be shorter than
elapsed time. Weight uses an explicit KG or LB unit with a finite nonnegative
value. A completed set requires positive repetitions or a positive measured
duration. Optional rest uses seconds. Calories are optional user-supplied kcal.
No estimate is calculated. Notes and titles are bounded.

Entry positions are contiguous from zero. Set numbers are contiguous from one.
Parent IDs and record IDs are checked together, and exercise references can be
validated against a repository's definitions. Cross-platform API fixtures use
calendar date strings, UTC timestamps, explicit units and decimal string revisions.

## Phase 6D local logging

Android writes workout records through Room. The PWA uses IndexedDB and can
log workouts offline. Both use the existing exercise, routine, session, entry
and set fields. A session is saved as a local aggregate with an expected
revision so a stale editor cannot overwrite an updated session. Entries and
sets retain their own IDs. The Android structured export and ZIP backup
include workouts in portable schema 7 when workout data exists. Readers still
accept schemas 4–6. Existing journal and To-do records remain in the same
archive. Browser workouts are stored only in that browser profile.

Open **Workouts** to create an exercise, record a session or start from a
routine. Optional planned set counts create empty sets to fill during a workout.
Enter weight and its unit per set. Time and rest are in seconds.
The finish summary accepts active minutes and optional average heart rate.
It uses elapsed duration when the minutes field is blank. A manually entered
duration can differ from elapsed time. Calendar date, notes and optional
calories are also editable. Exercise history shows recorded
completed sets in their original units.

Cross-device workout sync remains open. The existing PostgreSQL tables and
read endpoints are retained. Before connecting the local editors to them,
add versioned mutations and a durable outbox per record. Apply edits to
different sets independently and retain competing versions of the same set.
Validate concurrent insertion and ordering, authenticated
Android/PWA convergence and server recovery before advertising sync.
